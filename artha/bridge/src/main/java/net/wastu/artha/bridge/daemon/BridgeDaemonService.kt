package net.wastu.artha.bridge.daemon

import net.wastu.artha.core.bridge.BridgeTransactionSerializer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.wastu.artha.bridge.BuildConfig
import net.wastu.artha.bridge.crypto.BridgeCrypto
import net.wastu.artha.bridge.crypto.BridgeMessage
import net.wastu.artha.bridge.crypto.BridgeMessageType
import net.wastu.artha.bridge.crypto.BridgeSettings
import net.wastu.artha.bridge.crypto.EncryptedMessage
import net.wastu.artha.bridge.server.NostrRelayClient
import net.wastu.artha.bridge.ui.MainActivity
import org.json.JSONObject

class BridgeDaemonService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val relayClient = NostrRelayClient()
    private val answerLock = Mutex()
    private val publishLock = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "artha:bridge_daemon_wakelock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }

        updateNotification("Ready")

        // Artha devices ask for a sync by publishing their settings to the relays. A live
        // subscription answers within seconds; the poll catches anything it missed.
        val wake = Channel<Unit>(Channel.CONFLATED)
        for (relay in (application as BridgeApplication).preferences.getRelayUrls()) {
            serviceScope.launch { listenForRequests(relay) { wake.trySend(Unit) } }
        }
        serviceScope.launch {
            while (isActive) {
                runCatching { pullRequestsFromRelays()?.let { answerRequest(it) } }
                    .onFailure { Log.w(TAG, "Relay check failed", it) }
                withTimeoutOrNull(REQUEST_POLL_MS) { wake.receive() }
            }
        }

        // Updates install between syncs, never in the middle of one.
        serviceScope.launch {
            delay(UPDATE_FIRST_CHECK_MS)
            while (isActive) {
                val updater = (application as BridgeApplication).updater
                if (updater.isCheckDue(UPDATE_INTERVAL_MS)) {
                    answerLock.withLock { updater.update(install = updater.autoUpdate.value) }
                }
                delay(UPDATE_POLL_MS)
            }
        }

        // Publish regularly even without requests, so a fresh snapshot is always waiting.
        serviceScope.launch {
            while (isActive) {
                runCatching { publishToRelays() }.onFailure { Log.w(TAG, "Publish failed", it) }
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    /**
     * The service is not exported, so only this app and root can send [ACTION_SYNC_NOW]:
     * `am start-foreground-service -a <action> -n net.wastu.artha.bridge/.daemon.BridgeDaemonService`
     * runs the same sync an Artha request would, for checking the bridge from a computer.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC_NOW) {
            serviceScope.launch {
                runCatching { answerRequest(System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "Manual sync failed", it) }
            }
        }
        return START_STICKY
    }

    /** Reconnects whenever the relay drops us; only settings messages are requests. */
    private suspend fun listenForRequests(relay: String, onRequest: () -> Unit) {
        while (serviceScope.isActive) {
            val key = (application as BridgeApplication).preferences.getSecretKey()
            if (key != null) {
                runCatching {
                    relayClient.listen(relay, key) { dTag -> if (":settings-" in dTag) onRequest() }
                }.onFailure { Log.w(TAG, "$relay subscription dropped: $it") }
            }
            delay(LISTEN_RETRY_MS)
        }
    }

    /**
     * Sends what the ledger has now, refreshes the sources, then sends again. Both
     * status messages name the request they answer, so Artha knows when to stop waiting.
     */
    private suspend fun answerRequest(requestedAt: Long) = answerLock.withLock {
        _isSyncing.value = true
        try {
            updateNotification("Syncing")
            val app = application as BridgeApplication
            val hasSnapshot = app.importer.hasSnapshot
            publishToRelays(answering = requestedAt, final = false, includeTransactions = hasSnapshot)

            val lastSync = app.preferences.getLastSyncMillis() ?: 0L
            val isStale = (System.currentTimeMillis() - lastSync) > 3 * 60_000L
            if (isStale || !hasSnapshot) {
                app.runBackgroundSync()
                publishToRelays(answering = requestedAt, final = true, includeTransactions = true)
            } else {
                publishToRelays(answering = requestedAt, final = true, includeTransactions = false)
            }
            updateNotification("Ready")
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun publishToRelays(answering: Long? = null, final: Boolean = true, includeTransactions: Boolean = true) = publishLock.withLock {
        val app = application as BridgeApplication
        val prefs = app.preferences
        val key = prefs.getSecretKey() ?: return@withLock
        val channelId = prefs.getChannelId() ?: return@withLock
        val relays = prefs.getRelayUrls()
        if (includeTransactions) publishTransactions(app, relays, channelId, key)
        val status = JSONObject()
            .put("device", deviceDisplayName())
            .put("version", BuildConfig.VERSION_NAME)
            .put("chunks", prefs.publishedChunks)
            .put("final", final)
            .put("jagoPinConfigured", !prefs.getJagoPin().isNullOrBlank())
            .apply { answering?.let { put("answered", it) } }
            .apply { prefs.getPinCheck()?.let { put("jagoPin", it.toJson()) } }
            .put("sources", net.wastu.artha.core.bridge.BridgeSourceStatus.listToJson(app.importer.sourceStatuses()))
            .toString()
        publish(relays, channelId, key, "status", BridgeMessage(BridgeMessageType.ACK, System.currentTimeMillis(), status))
        prefs.recordSync()
    }

    private suspend fun publishTransactions(app: BridgeApplication, relays: List<String>, channelId: String, key: ByteArray) {
        val prefs = app.preferences

        // A fresh install has nothing to send yet, so it imports once before the first publish.
        if (!app.importer.hasSnapshot) app.importer.importAll()
        val txs = app.importer.load()
        val chunks = BridgeTransactionSerializer.pack(txs)
        BridgeLog.i(TAG, "Publishing ${txs.size} transactions in ${chunks.size} chunks, largest ${chunks.maxOfOrNull { it.length } ?: 0} chars")
        // Slots replace earlier events of the same name; empties overwrite any left from a bigger publish.
        val slots = chunks + List((prefs.publishedChunks - chunks.size).coerceAtLeast(0)) { "[]" }
        // Every chunk gets its own timestamp, which Artha uses to tell chunks apart.
        val publishedAt = System.currentTimeMillis()
        val events = slots.mapIndexed { index, payload ->
            "tx-$index" to seal(BridgeMessage(BridgeMessageType.TRANSACTIONS, publishedAt + index, payload), key)
        }
        val accepted = coroutineScope {
            relays.map { relay -> async { relayClient.publishAll(relay, channelId, events, key) } }.awaitAll()
        }
        BridgeLog.i(TAG, "Published ${events.size} transaction slots; relays accepted ${accepted.joinToString { it.size.toString() }}")
        prefs.publishedChunks = chunks.size
    }

    private fun seal(msg: BridgeMessage, key: ByteArray): String {
        val encrypted = BridgeCrypto.encrypt(msg.toJson().toByteArray(Charsets.UTF_8), key)
        return android.util.Base64.encodeToString(
            "${encrypted.iv}:${encrypted.ciphertext}".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
    }

    private suspend fun publish(relays: List<String>, channelId: String, key: ByteArray, slot: String, msg: BridgeMessage) {
        val blob = seal(msg, key)
        val ok = coroutineScope {
            relays.map { relay -> async { relayClient.publish(relay, channelId, blob, key, slot) } }.awaitAll().count { it }
        }
        Log.i(TAG, "Published $slot to $ok of ${relays.size} relays")
    }

    /** Applies settings from the relays; the newest unanswered request's time, if any. */
    private suspend fun pullRequestsFromRelays(): Long? {
        val app = application as BridgeApplication
        val prefs = app.preferences
        val key = prefs.getSecretKey() ?: return null
        val channelId = prefs.getChannelId() ?: return null
        val relays = prefs.getRelayUrls()
        val primary = relays.firstOrNull()
        val others = relays.drop(1)
        val primaryRaws = if (primary != null) {
            runCatching { relayClient.fetch(primary, channelId, key, timeoutMs = 2500L) }.getOrDefault(emptyList())
        } else emptyList()
        val raws = if (primaryRaws.isNotEmpty()) {
            primaryRaws
        } else {
            coroutineScope {
                others.map { relay ->
                    async { runCatching { relayClient.fetch(relay, channelId, key, timeoutMs = 4000L) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten()
            }
        }
        val messages = raws
            .mapNotNull { raw ->
                runCatching {
                    val decoded = String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    val parts = decoded.split(":")
                    if (parts.size != 2) return@runCatching null
                    val plain = BridgeCrypto.decrypt(EncryptedMessage(parts[0], parts[1]), key)
                    BridgeMessage.fromJson(String(plain, Charsets.UTF_8))
                }.getOrNull()
            }
            .filter { it.type == BridgeMessageType.SETTINGS }
            .distinctBy { it.timestamp to it.payload }
            .sortedBy { it.timestamp }
        if (messages.isNotEmpty()) {
            BridgeLog.i(TAG, "Received ${messages.size} settings from relays")
        }
        var newest: Long? = null
        for (msg in messages) {
            val settings = runCatching { BridgeSettings.fromJson(msg.payload) }.getOrNull() ?: continue
            if (settings.deviceId?.let(prefs::isRemoved) == true) continue
            val known = settings.deviceId?.let { id -> prefs.devices.value.firstOrNull { it.id == id } }
            if (settings.deviceId != null && msg.timestamp > (known?.lastSeenMillis ?: 0L)) newest = msg.timestamp
            app.applySettings(settings, msg.timestamp)
        }
        return newest
    }

    private fun deviceDisplayName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun createNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Artha Bridge")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Artha Bridge ready to sync"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private val _isSyncing = MutableStateFlow(false)
        val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
        const val ACTION_SYNC_NOW = "net.wastu.artha.bridge.action.SYNC_NOW"
        private const val CHANNEL_ID = "artha_bridge_daemon_channel"
        private const val NOTIFICATION_ID = 9876
        private const val TAG = "ArthaBridge"
        private const val REQUEST_POLL_MS = 60_000L
        private const val PUBLISH_INTERVAL_MS = 15 * 60_000L
        private const val LISTEN_RETRY_MS = 15_000L
        private const val UPDATE_FIRST_CHECK_MS = 2 * 60_000L
        private const val UPDATE_POLL_MS = 30 * 60_000L
        private const val UPDATE_INTERVAL_MS = 6 * 60 * 60_000L
    }
}
