@file:android.annotation.SuppressLint("SdCardPath") // Provider sandboxes are intentionally read as root.

package net.wastu.artha.data.ingestion

import android.util.Base64
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.historyCoverageHint
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One decrypted bank history snapshot per ingestion batch. */
class JagoTransactionReader(private val files: RootFileReader) {
    private var snapshot: List<UnifiedTransaction>? = null
    fun invalidate() { snapshot = null }

    suspend fun transactions(): List<UnifiedTransaction> {
        currentCoroutineContext().ensureActive()
        snapshot?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val base = "/data/data/com.jago.digitalBanking"
                val xml = files.read("$base/shared_prefs/FlutterSharedPreferences.xml").toString(Charsets.UTF_8)
                coroutineContext.ensureActive()
                val encoded = Regex("<string\\s+name=\"flutter\\.jago\\.sharedpref\\.hivekey\">([^<]+)</string>")
                    .find(xml)?.groupValues?.get(1)
                    ?: throw ProviderReadException(AccountStatus.ERROR, "Jago cached history key unavailable. Open Jago to populate its saved history.")
                val master = HiveBox.parseKey(HiveBox.decrypt(Base64.decode(encoded.trim(), Base64.DEFAULT), ByteArray(16)).toString(Charsets.UTF_8))
                coroutineContext.ensureActive()
                val snapshot = files.stableBatch(listOf("$base/app_flutter/shared_preference.hive", "$base/app_flutter/pot.hive", "$base/app_flutter/list_transaction_history.hive"))
                fun boxBytes(name: String): ByteArray = snapshot["$base/app_flutter/$name.hive"]
                    ?: throw ProviderReadException(AccountStatus.ERROR, "Jago cached history file unavailable. Open Jago to populate its saved history.")
                val preferences = HiveBox.read(boxBytes("shared_preference"), master)
                coroutineContext.ensureActive()
                fun boxKey(name: String): ByteArray = HiveBox.parseKey(
                    (preferences[name] as? Map<*, *>)?.get("f2") as? String
                        ?: throw ProviderReadException(AccountStatus.ERROR, "Jago cached history box key unavailable. Open Jago to populate its saved history."),
                )
                val pockets = HiveBox.read(boxBytes("pot"), boxKey("pot"))
                coroutineContext.ensureActive()
                val history = HiveBox.read(boxBytes("list_transaction_history"), boxKey("list_transaction_history"))
                val transactions = JagoCachedHistory.parse(pockets, history) { coroutineContext.ensureActive() }
                coroutineContext.ensureActive()
                this@JagoTransactionReader.snapshot = transactions
                transactions
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (partial: PartialImportException) { throw partial }
            catch (typed: ProviderReadException) { throw typed }
            catch (_: Exception) {
                coroutineContext.ensureActive()
                throw ProviderReadException(AccountStatus.ERROR, "Jago cached history snapshot could not be read. Check root access, the primary Android profile and Jago's saved history.")
            }
        }
    }
}

/** Hive f3 amounts use provider-specific minor-unit scales, not JVM [Currency] defaults (IDR reports 0). */
internal object JagoHiveMinorUnits {
    fun minorUnitScale(currencyCode: String): Int? = when (currencyCode.uppercase(Locale.ROOT)) {
        "IDR", "USD" -> 2
        else -> null
    }
}

/** Row and container shapes verified against the retained native Hive cache. */
internal object JagoCachedHistory {
    fun parse(
        pockets: Map<String, Any?>,
        history: Map<String, Any?>,
        checkpoint: () -> Unit = {},
    ): List<UnifiedTransaction> {
        checkpoint()
        if (history.isEmpty()) {
            throw ProviderReadException(AccountStatus.ERROR, "Jago cached history containers unavailable. Open Jago to populate its saved history.")
        }
        val seen = mutableSetOf<String>()
        val transactions = mutableListOf<UnifiedTransaction>()
        var malformedRows = 0
        var malformedContainers = 0
        var firstRowFailure: String? = null
        fun noteRowFailure(error: Exception) {
            if (firstRowFailure != null) return
            firstRowFailure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                .lineSequence().first().trim().take(240)
        }
        fun failureSuffix() = firstRowFailure?.let { "; $it" }.orEmpty()
        // Native lists without pocket metadata can repeat bank rows with different unverified f13.
        // Prefer usable pocket enrichment so existing identities and payloads remain unchanged.
        for (withPocketName in listOf(true, false)) {
            for ((pocketId, value) in history) {
                checkpoint()
                val pocket = pockets[pocketId] as? Map<*, *>
                val pocketName = if (pocketId.isBlank()) null else pocketName(pocket)
                if ((pocketName != null) != withPocketName) continue
                val list = (value as? Map<*, *>)?.get("f2") as? List<*>
                if (list == null) {
                    malformedContainers++
                    continue
                }
                for (raw in list) {
                    checkpoint()
                    try {
                        val transaction = row(
                            raw as? Map<*, *> ?: error("Unsupported Jago row"),
                            pocketId,
                            pocket,
                            pocketName,
                            ::noteRowFailure,
                        )
                        // A rejected or zero-value observation must never reserve the source identity.
                        if (transaction != null && seen.add(transaction.providerTransactionId)) transactions += transaction
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { checkpoint(); noteRowFailure(error); malformedRows++ }
                }
            }
        }
        checkpoint()
        val ordered = transactions.sortedWith { first, second ->
            checkpoint()
            second.occurredAt.compareTo(first.occurredAt)
        }
        checkpoint()
        if (malformedRows > 0 || malformedContainers > 0) {
            val message = "$malformedRows malformed bank rows and $malformedContainers unsupported cached containers skipped${failureSuffix()}"
            if (ordered.isEmpty()) throw ProviderReadException(AccountStatus.ERROR, "$message; no valid cached bank rows available")
            throw PartialImportException(ordered, "$message; valid cached rows imported")
        }
        return ordered
    }

    private fun pocketName(pocket: Map<*, *>?): String? {
        val name = (pocket?.get("f4") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (name.equals("MAIN_ACCOUNT", ignoreCase = true)) "Main Pocket" else name
    }

    private fun row(
        fields: Map<*, *>,
        pocketId: String,
        pocket: Map<*, *>?,
        pocketName: String?,
        noteRowFailure: (Exception) -> Unit,
    ): UnifiedTransaction? {
        fun text(index: Int): String = when (val value = fields["f$index"]) {
            null -> ""
            is String -> value.takeUnless { it == "null" }.orEmpty()
            else -> error("Unsupported Jago text field")
        }
        val id = text(47).ifBlank { text(6) }
        require(id.isNotBlank()) { "Jago transaction identity unavailable" }
        val rawDate = text(5)
        val sourceAmount = fields["f3"] as? Number ?: error("Jago numeric amount unavailable")
        val currency = text(41).ifBlank {
            (pocket?.get("f44") as? String).orEmpty()
        }
        val scale = JagoHiveMinorUnits.minorUnitScale(currency)
        require(currency.isNotBlank() && scale != null) {
            "Jago currency or supported minor-unit scale unavailable"
        }
        val amount = BigDecimal(sourceAmount.toString()).abs().movePointRight(scale).longValueExact()
        if (amount == 0L) return null
        // A floating source may already have lost cents before decimal conversion.
        require(sourceAmount !is Double || amount < (1L shl 53)) { "Jago floating amount exceeds exact minor-unit precision" }
        require(sourceAmount !is Float || amount < (1L shl 24)) { "Jago floating amount exceeds exact minor-unit precision" }
        val money = Money(amount, currency)
        val direction = Direction.valueOf(text(10).uppercase(Locale.ROOT))
        // Native f13 differs from the payment date in most retained rows; its
        // temporal role is unverified and must not replace a missing payment date.
        val date = OffsetDateTime.parse(rawDate).toInstant()
        // Room stores epoch millis. Reject finer source precision instead of changing bank time.
        require(date == Instant.ofEpochMilli(date.toEpochMilli())) { "Jago source timestamp exceeds ledger precision" }
        val localized = try { JSONObject(text(14)).let { it.optString("en").ifBlank { it.optString("id") } } }
        catch (_: org.json.JSONException) { "" }
        val deciphered = TransactionDecipherer.decipher(
            code = text(1),
            merchantOrCounterpart = text(2),
            description = localized.ifBlank { text(2) }.ifBlank { text(4) },
            notes = text(4),
            direction = direction,
        )
        return UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:$id:$rawDate",
            direction = direction,
            money = money,
            occurredAt = date,
            description = deciphered.title,
            pocket = pocketName?.let { Pocket(pocketId, it) },
            isQris = deciphered.isQris,
            cardLast4 = deciphered.cardLast4,
            rawPayload = jagoRawPayload(
                fields = fields,
                pocketId = pocketId,
                pocketName = pocketName,
                code = text(1),
                counterpart = text(2),
                merchantName = if (deciphered.isQris) deciphered.title else null,
                notes = text(4),
                localized = localized,
                deciphered = deciphered,
                noteRowFailure = noteRowFailure,
            ),
        )
    }

    private fun jagoRawPayload(
        fields: Map<*, *>,
        pocketId: String,
        pocketName: String?,
        code: String,
        counterpart: String,
        merchantName: String?,
        notes: String,
        localized: String,
        deciphered: TransactionDecipherer.Deciphered,
        noteRowFailure: (Exception) -> Unit,
    ): String {
        fun corePayload(includeFields: Boolean): JSONObject = JSONObject().apply {
            put("origin", "jago-local-hive")
            put("provider", Provider.JAGO.name)
            put("cardLast4", deciphered.cardLast4)
            put("isQris", deciphered.isQris)
            put("paymentMethod", deciphered.paymentMethod)
            put("code", code)
            // A card mask ("•••• •••• •••• 8384") is not a counterparty; keep it as `cardMask`
            // instead so display code cannot mistake the last digits for a merchant title.
            if (counterpart.contains("••")) put("cardMask", counterpart) else put("counterpart", counterpart)
            put("notes", notes)
            put("localized", localized)
            put("pocketId", pocketId)
            put("pocketName", pocketName)
            if (includeFields) hiveFieldsJson(fields)?.let { put("fields", it) }
        }
        var payload = corePayload(includeFields = true)
        if (!payloadEncodes(payload)) {
            noteRowFailure(IllegalStateException("Jago cached row fields could not be encoded"))
            payload = corePayload(includeFields = false)
        }
        return nonBlankJson(payload)
    }

    private fun payloadEncodes(json: JSONObject): Boolean = !json.toString().isNullOrBlank()

    private fun nonBlankJson(json: JSONObject): String {
        json.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return """{"origin":"jago-local-hive","provider":"${Provider.JAGO.name}"}"""
    }

    private fun hiveFieldsJson(fields: Map<*, *>): JSONObject? {
        val json = JSONObject()
        for ((rawKey, value) in fields) {
            val key = rawKey as? String ?: rawKey?.toString() ?: continue
            try {
                when (val encoded = hiveJsonValue(value) ?: return null) {
                    is JSONObject -> json.put(key, encoded)
                    is JSONArray -> json.put(key, encoded)
                    else -> json.put(key, encoded)
                }
            } catch (_: Exception) {
                return null
            }
        }
        return json
    }

    private fun hiveJsonValue(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is String, is Boolean, is Number -> value
            is Map<*, *> -> hiveFieldsJson(value)
            is List<*> -> hiveListJson(value)
            else -> null
        }
    }

    private fun hiveListJson(list: List<*>): JSONArray? {
        val json = JSONArray()
        for (item in list) {
            when (val encoded = hiveJsonValue(item) ?: return null) {
                is JSONObject -> json.put(encoded)
                is JSONArray -> json.put(encoded)
                else -> json.put(encoded)
            }
        }
        return json
    }
}

class JagoProviderAdapter(private val reader: JagoTransactionReader) : ProviderAdapter {
    override val coverageHint = Provider.JAGO.historyCoverageHint
    override val provider = Provider.JAGO
    override suspend fun ingest(): List<UnifiedTransaction> = reader.transactions()
}

class ShopeeProviderAdapter(private val files: RootFileReader) : ProviderAdapter {
    override val coverageHint = Provider.SHOPEE.historyCoverageHint
    override val provider = Provider.SHOPEE
    private val reader = ShopeeOrderReader(files)
    override val accountIdentifier get() = reader.accountUsername
    override suspend fun ingest(): List<UnifiedTransaction> = reader.transactions()
}


class GojekProviderAdapter(private val files: RootFileReader) : ProviderAdapter {
    override val coverageHint = Provider.GOJEK.historyCoverageHint
    override val provider = Provider.GOJEK
    override suspend fun ingest(): List<UnifiedTransaction> = GojekOrderReader(files).transactions()
    override suspend fun ingestInteractive(): List<UnifiedTransaction> = GojekOrderReader(files).transactions()
}

class GrabProviderAdapter(private val files: RootFileReader) : ProviderAdapter {
    override val coverageHint = Provider.GRAB.historyCoverageHint
    override val provider = Provider.GRAB
    override suspend fun ingest(): List<UnifiedTransaction> = GrabOrderReader(files).transactions()
}
