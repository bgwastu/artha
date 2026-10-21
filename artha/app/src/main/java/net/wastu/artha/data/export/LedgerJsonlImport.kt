package net.wastu.artha.data.export

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject

data class LedgerImportResult(
    val transactions: List<UnifiedTransaction>,
    val meta: JSONObject?,
    val labels: List<ImportedLabels>,
)

data class ImportedLabels(
    val key: String,
    val category: String?,
    val userNote: String?,
    val reason: String?,
    val classificationIsManual: Boolean,
)

object LedgerJsonlImport {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_LINES = 50_000

    fun parse(body: String): LedgerImportResult {
        require(body.length <= MAX_BYTES) { "Import file is too large." }
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES + 1).toList()
        require(lines.size <= MAX_LINES) { "Import file has too many lines." }
        var meta: JSONObject? = null
        val transactions = mutableListOf<UnifiedTransaction>()
        val labels = mutableListOf<ImportedLabels>()
        for (line in lines) {
            val row = JSONObject(line)
            when (row.optString("record")) {
                "meta" -> meta = row
                "transaction" -> {
                    val tx = parseTransaction(row)
                    transactions += tx
                    labels += ImportedLabels(
                        key = row.optString("key").ifBlank { tx.key },
                        category = row.optString("category").takeIf { it.isNotBlank() },
                        userNote = row.optString("userNote").takeIf { it.isNotBlank() },
                        reason = row.optString("aiReasoning").takeIf { it.isNotBlank() }
                            ?: row.optString("categoryReason").takeIf { it.isNotBlank() },
                        classificationIsManual = row.optBoolean("classificationIsManual"),
                    )
                }
            }
        }
        require(transactions.isNotEmpty()) { "No transactions found in import file." }
        return LedgerImportResult(transactions, meta, labels)
    }

    private fun parseTransaction(row: JSONObject): UnifiedTransaction {
        val provider = Provider.valueOf(row.getString("provider"))
        val direction = Direction.valueOf(row.getString("direction"))
        val amountMinor = row.getLong("amountMinor")
        val currency = row.optString("currencyCode", "IDR")
        val occurredAt = Instant.parse(row.getString("occurredAt"))
        val description = row.getString("description")
        val rawPayload = row.getString("rawPayload")
        val pocketId = row.optString("pocketId").takeIf { it.isNotBlank() }
        val pocketName = row.optString("pocketName").takeIf { it.isNotBlank() }
        val pocket = if (!pocketId.isNullOrBlank() && !pocketName.isNullOrBlank()) Pocket(pocketId, pocketName) else null
        return UnifiedTransaction(
            provider = provider,
            providerTransactionId = row.getString("providerTransactionId"),
            direction = direction,
            money = Money(amountMinor, currency),
            occurredAt = occurredAt,
            description = description,
            rawPayload = rawPayload,
            pocket = pocket,
            isQris = row.optBoolean("isQris"),
            cardLast4 = row.optString("cardLast4").takeIf { it.isNotBlank() },
            isReversal = row.optBoolean("isReversal"),
            isConfirmed = row.optBoolean("isConfirmed", true),
            canonicalKey = row.optString("canonicalKey").takeIf { it.isNotBlank() },
            userNote = row.optString("userNote").takeIf { it.isNotBlank() },
            category = row.optString("category").takeIf { it.isNotBlank() },
            classificationIsManual = row.optBoolean("classificationIsManual"),
            aiReasoning = row.optString("aiReasoning").takeIf { it.isNotBlank() },
        )
    }
}
