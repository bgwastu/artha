@file:android.annotation.SuppressLint("SdCardPath") // Provider sandboxes are intentionally read as root.

package net.wastu.artha.data.ingestion

import android.util.Base64
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.historyCoverageHint
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One decrypted bank history snapshot per ingestion batch. */
class JagoTransactionReader(private val files: RootFileReader) {
    private var snapshot: List<UnifiedTransaction>? = null
    fun invalidate() { snapshot = null }

    suspend fun transactions(): List<UnifiedTransaction> {
        snapshot?.let { return it }
        return withContext(Dispatchers.IO) {
            val base = "/data/data/com.jago.digitalBanking"
            val xml = files.read("$base/shared_prefs/FlutterSharedPreferences.xml").toString(Charsets.UTF_8)
            val encoded = requireNotNull(Regex("<string\\s+name=\"flutter\\.jago\\.sharedpref\\.hivekey\">([^<]+)</string>")
                .find(xml)?.groupValues?.get(1)) { "Jago master key unavailable" }
            val master = HiveBox.parseKey(HiveBox.decrypt(Base64.decode(encoded.trim(), Base64.DEFAULT), ByteArray(16)).toString(Charsets.UTF_8))
            val snapshot = files.stableBatch(listOf("$base/app_flutter/shared_preference.hive", "$base/app_flutter/pot.hive", "$base/app_flutter/list_transaction_history.hive"))
            val preferences = HiveBox.read(requireNotNull(snapshot["$base/app_flutter/shared_preference.hive"]), master)
            fun boxKey(name: String): ByteArray = HiveBox.parseKey(
                requireNotNull((preferences[name] as? Map<*, *>)?.get("f2") as? String) { "Jago box key unavailable" },
            )
            val pockets = HiveBox.read(requireNotNull(snapshot["$base/app_flutter/pot.hive"]), boxKey("pot"))
            val history = HiveBox.read(requireNotNull(snapshot["$base/app_flutter/list_transaction_history.hive"]), boxKey("list_transaction_history"))
            val seen = mutableSetOf<String>()
            var rejected = 0
            val transactions = buildList {
                history.forEach { (pocketId, value) ->
                    val pocket = pockets[pocketId] as? Map<*, *>
                    val rawPocketName = (pocket?.get("f4") as? String).orEmpty().trim()
                    val pocketName = if (rawPocketName.isBlank() || rawPocketName.equals("MAIN_ACCOUNT", ignoreCase = true)) {
                        "Main Pocket"
                    } else {
                        rawPocketName
                    }
                    val list = (value as? Map<*, *>)?.get("f2") as? List<*> ?: return@forEach
                    for (raw in list) {
                        try {
                        val fields = raw as? Map<*, *> ?: error("Invalid Jago transaction")
                        fun text(index: Int) = fields["f$index"]?.toString().orEmpty().takeUnless { it == "null" }.orEmpty()
                        val id = text(47).ifBlank { text(6) }
                        require(id.isNotBlank()) { "Jago transaction identity unavailable" }
                        val identity = "$id:${text(5)}"
                        if (!seen.add(identity)) continue
                        val amount = BigDecimal(text(3)).abs().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
                        if (amount == 0L) continue
                        val direction = Direction.valueOf(text(10).uppercase(Locale.ROOT))
                        val date = text(5).let {
                            try { OffsetDateTime.parse(it).toInstant() }
                            catch (_: java.time.format.DateTimeParseException) {
                                Instant.ofEpochMilli(((fields["f13"] as? Map<*, *>)?.get("millis") as? Number ?: fields["f13"] as Number).toLong())
                            }
                        }
                        val localized = try { JSONObject(text(14)).let { it.optString("en").ifBlank { it.optString("id") } } }
                            catch (_: org.json.JSONException) { "" }

                        val deciphered = TransactionDecipherer.decipher(
                            code = text(1),
                            merchantOrCounterpart = text(2),
                            description = localized.ifBlank { text(2) }.ifBlank { text(4) },
                            notes = text(4),
                            direction = direction,
                        )

                        add(UnifiedTransaction(
                            provider = Provider.JAGO,
                            providerTransactionId = "jago:$identity",
                            direction = direction,
                            money = Money(amount, text(41).ifBlank { (pocket?.get("f44") as? String).orEmpty().ifBlank { "IDR" } }),
                            occurredAt = date,
                            description = deciphered.title,
                            pocket = Pocket(pocketId, pocketName),
                            isQris = deciphered.isQris,
                            cardLast4 = deciphered.cardLast4,
                            rawPayload = JSONObject()
                                .put("origin", "jago-local-hive")
                                .put("provider", Provider.JAGO.name)
                                .put("cardLast4", deciphered.cardLast4)
                                .put("isQris", deciphered.isQris)
                                .put("paymentMethod", deciphered.paymentMethod)
                                .put("counterpart", text(2))
                                .put("code", text(1))
                                .put("notes", text(4))
                                .put("localized", localized)
                                .put("pocketId", pocketId)
                                .put("pocketName", pocketName)
                                .put("fields", JSONObject(fields))
                                .toString(),
                        ))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { rejected++ }
                    }
                }
            }.sortedByDescending { it.occurredAt }
            if (rejected > 0) throw PartialImportException(transactions, "$rejected malformed bank rows skipped; valid cached rows imported")
            this@JagoTransactionReader.snapshot = transactions
            transactions
        }
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

class TokopediaProviderAdapter(private val files: RootFileReader) : ProviderAdapter {
    override val coverageHint = Provider.TOKOPEDIA.historyCoverageHint
    override val provider = Provider.TOKOPEDIA
    override suspend fun ingest(): List<UnifiedTransaction> = TokopediaOrderReader(files).transactions()
}

class GojekProviderAdapter(private val files: RootFileReader) : ProviderAdapter {
    override val coverageHint = Provider.GOJEK.historyCoverageHint
    override val provider = Provider.GOJEK
    override suspend fun ingest(): List<UnifiedTransaction> = GojekOrderReader(files).transactions()
}

class GrabProviderAdapter(private val files: RootFileReader) : ProviderAdapter {
    override val coverageHint = Provider.GRAB.historyCoverageHint
    override val provider = Provider.GRAB
    override suspend fun ingest(): List<UnifiedTransaction> = GrabOrderReader(files).transactions()
}
