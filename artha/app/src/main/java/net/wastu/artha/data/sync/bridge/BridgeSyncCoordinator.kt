package net.wastu.artha.data.sync.bridge

import net.wastu.artha.core.bridge.BridgeTransactionSerializer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.ingestion.IngestionCoordinator
import net.wastu.artha.data.providers.ProviderPreferences
import net.wastu.artha.data.sync.AppSessionWakeSync
import net.wastu.artha.data.sync.GojekFlashController
import net.wastu.artha.data.sync.JagoScreenSync

class BridgeSyncCoordinator(
    private val bridgePreferences: BridgePreferences,
    private val providerPreferences: ProviderPreferences,
    private val ingestion: IngestionCoordinator,
    private val loadLedgerTransactions: suspend () -> List<UnifiedTransaction>,
    private val jagoScreenSync: JagoScreenSync?,
    private val sessionWake: AppSessionWakeSync?,
    private val gojekFlash: GojekFlashController?,
    private val relayClient: NostrRelayClient = NostrRelayClient(),
    private val onNewTransactions: suspend (List<UnifiedTransaction>) -> Unit = {},
) {
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val key = bridgePreferences.getSecretKey() ?: return@withContext false
        val channelId = bridgePreferences.getChannelId() ?: return@withContext false
        val relays = bridgePreferences.getRelayUrls()

        if (bridgePreferences.isHelper.value) {
            syncAsHelper(key, channelId, relays)
        } else {
            syncAsMain(key, channelId, relays)
        }
    }

    private suspend fun syncAsHelper(
        key: ByteArray,
        channelId: String,
        relays: List<String>,
    ): Boolean {
        // 1. Check for incoming settings from Main
        for (relay in relays) {
            val events = relayClient.fetch(relay, channelId, key)
            for (raw in events) {
                runCatching {
                    val msgJson = decryptRaw(raw, key) ?: return@runCatching
                    val msg = BridgeMessage.fromJson(msgJson)
                    if (msg.type == BridgeMessageType.SETTINGS) {
                        val settings = BridgeSettings.fromJson(msg.payload)
                        applySettings(settings)
                    }
                }
            }
        }

        // 2. Ingest transactions from all enabled providers
        val pin = providerPreferences.getJagoPin()
        if (!pin.isNullOrBlank()) {
            jagoScreenSync?.run()
        }

        ingestion.ingestAll()

        val freshTransactions = loadLedgerTransactions()
        if (freshTransactions.isNotEmpty()) {
            val chunks = BridgeTransactionSerializer.pack(freshTransactions)
            chunks.forEachIndexed { index, chunk ->
                val msg = BridgeMessage(BridgeMessageType.TRANSACTIONS, System.currentTimeMillis(), chunk)
                val encrypted = BridgeCrypto.encrypt(msg.toJson().toByteArray(Charsets.UTF_8), key)
                val encryptedJson = android.util.Base64.encodeToString(
                    "${encrypted.iv}:${encrypted.ciphertext}".toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )

                for (relay in relays) {
                    relayClient.publish(relay, channelId, encryptedJson, key, slot = "tx-$index")
                }
            }
        }

        bridgePreferences.recordSync()
        return true
    }

    /**
     * Publishes this device's settings, which doubles as a sync request, then reads the
     * relays until the bridge has answered it, so the transactions that come back are
     * fresh rather than whatever the bridge last published. Returns false when the
     * bridge did not answer within [ANSWER_TIMEOUT_MS].
     */
    private suspend fun syncAsMain(
        key: ByteArray,
        channelId: String,
        relays: List<String>,
    ): Boolean {
        val requestedAt = System.currentTimeMillis()
        pushSettings(currentSettings(), sentAt = requestedAt)
        val appliedChunks = mutableSetOf<Long>()
        val answerDeadline = System.currentTimeMillis() + ANSWER_TIMEOUT_MS
        var finalDeadline = Long.MAX_VALUE
        while (true) {
            val status = readRelays(key, channelId, relays, skip = appliedChunks) { appliedChunks += it }
            val answered = status != null && status.answered >= requestedAt
            if (answered && status!!.final) break
            // Once the bridge has taken the request, its source walk gets longer to finish.
            if (answered && finalDeadline == Long.MAX_VALUE) finalDeadline = System.currentTimeMillis() + FINAL_TIMEOUT_MS
            val now = System.currentTimeMillis()
            if ((!answered && now >= answerDeadline) || now >= finalDeadline) {
                bridgePreferences.recordSync()
                return answered
            }
            delay(if (answered) FINAL_POLL_MS else ANSWER_POLL_MS)
        }
        bridgePreferences.recordSync()
        return true
    }

    private data class BridgeStatus(val answered: Long, val final: Boolean)

    /** Reads every relay at once; hands over transaction chunks whose timestamps are not in [skip]. */
    private suspend fun readRelays(
        key: ByteArray,
        channelId: String,
        relays: List<String>,
        skip: Set<Long>,
        onTransactionsAt: (Long) -> Unit,
    ): BridgeStatus? {
        val raws = coroutineScope {
            relays.map { relay ->
                async { runCatching { relayClient.fetch(relay, channelId, key) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }
        val messages = raws.mapNotNull { raw ->
            runCatching { decryptRaw(raw, key)?.let(BridgeMessage::fromJson) }.getOrNull()
        }.distinctBy { it.type to it.timestamp to it.payload }
        var status: BridgeStatus? = null
        for (msg in messages.sortedBy { it.timestamp }) {
            runCatching {
                when (msg.type) {
                    BridgeMessageType.TRANSACTIONS -> {
                        bridgePreferences.recordBridgeSeen(msg.timestamp)
                        if (msg.timestamp !in skip) {
                            // Legacy chunks carry no raw payloads, which the ledger needs, so only packed ones import.
                            val transactions = BridgeTransactionSerializer.unpack(msg.payload).orEmpty()
                            if (transactions.isNotEmpty()) onNewTransactions(transactions)
                            onTransactionsAt(msg.timestamp)
                        }
                    }
                    BridgeMessageType.ACK -> {
                        bridgePreferences.recordBridgeSeen(msg.timestamp)
                        val json = org.json.JSONObject(msg.payload)
                        val device = json.optString("device")
                        if (device.isNotBlank()) bridgePreferences.setBridgeDeviceName(device)
                        val version = json.optString("version")
                        if (version.isNotBlank()) bridgePreferences.setBridgeVersion(version)
                        json.optJSONObject("jagoPin")?.let { check ->
                            providerPreferences.recordJagoPinCheck(
                                check.optString("fp"), check.optString("result"), check.optString("detail"),
                            )
                        }
                        providerPreferences.applyBridgeStatuses(
                            net.wastu.artha.core.bridge.BridgeSourceStatus.listFromJson(json.optJSONArray("sources")))
                        status = BridgeStatus(json.optLong("answered", 0L), json.optBoolean("final", true))
                    }
                    else -> Unit
                }
            }.onFailure { android.util.Log.w(TAG, "Could not apply bridge ${msg.type} message", it) }
        }
        return status
    }

    suspend fun pushSettings(
        settings: BridgeSettings,
        sentAt: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        val key = bridgePreferences.getSecretKey() ?: return@withContext false
        val channelId = bridgePreferences.getChannelId() ?: return@withContext false
        val relays = bridgePreferences.getRelayUrls()

        val msg = BridgeMessage(BridgeMessageType.SETTINGS, sentAt, settings.toJson())
        val encrypted = BridgeCrypto.encrypt(msg.toJson().toByteArray(Charsets.UTF_8), key)
        val encryptedJson = android.util.Base64.encodeToString(
            "${encrypted.iv}:${encrypted.ciphertext}".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val slot = "settings-${bridgePreferences.deviceId}"
        relays.map { relay -> async { relayClient.publish(relay, channelId, encryptedJson, key, slot = slot) } }
            .awaitAll().any { it }
    }

    /** What the bridge needs from this device: the Jago PIN and who is asking. */
    private fun currentSettings() = BridgeSettings(
        jagoPin = providerPreferences.getJagoPin(),
        jagoPinChangedAt = providerPreferences.getJagoPinChangedAt(),
        deviceId = bridgePreferences.deviceId,
        deviceName = deviceDisplayName(),
    )

    private fun deviceDisplayName(): String {
        val maker = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = android.os.Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    private fun decryptRaw(raw: String, key: ByteArray): String? {
        return runCatching {
            val decoded = String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val parts = decoded.split(":")
            if (parts.size != 2) return null
            val decryptedBytes = BridgeCrypto.decrypt(EncryptedMessage(parts[0], parts[1]), key)
            String(decryptedBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        /** The bridge hears a request within seconds, but a Jago walk takes a while. */
        private const val TAG = "ArthaBridgeSync"
        const val ANSWER_TIMEOUT_MS = 120_000L
        private const val ANSWER_POLL_MS = 3_000L
        private const val FINAL_POLL_MS = 5_000L
        private const val FINAL_TIMEOUT_MS = 300_000L
    }

    private fun applySettings(settings: BridgeSettings) {
        settings.jagoPin?.let { providerPreferences.setJagoPin(it) }
        for (provider in Provider.entries) {
            providerPreferences.setEnabled(provider, settings.enabledProviders.contains(provider))
        }
    }
}

/** Artha Bridge did not answer a sync request in time; it is probably offline. */
class BridgeNoAnswerException : Exception("Artha Bridge did not answer")
