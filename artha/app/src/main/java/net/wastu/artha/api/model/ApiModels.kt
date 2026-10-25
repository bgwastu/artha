package net.wastu.artha.api.model

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.formatMinorAmount
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategoryConstants
import net.wastu.artha.data.analysis.CategoryContext
import net.wastu.artha.data.analysis.MerchantMemory
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

data class TransactionSummaryDto(
    val key: String,
    val provider: String,
    val providerName: String,
    val providerTransactionId: String,
    val direction: String,
    val amountMinor: Long,
    val amountRupiah: Long,
    val amountFormatted: String,
    val currency: String,
    val occurredAt: String,
    val description: String,
    val merchant: String?,
    val category: String,
    val classificationIsManual: Boolean,
    val userNote: String?,
    val aiReasoning: String?,
    val isQris: Boolean,
    val cardLast4: String?,
    val pocketName: String?,
    val isReversal: Boolean,
    val isConfirmed: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("provider", provider)
        put("providerName", providerName)
        put("providerTransactionId", providerTransactionId)
        put("direction", direction)
        put("amountMinor", amountMinor)
        put("amountRupiah", amountRupiah)
        put("amountFormatted", amountFormatted)
        put("currency", currency)
        put("occurredAt", occurredAt)
        put("description", description)
        put("merchant", merchant ?: JSONObject.NULL)
        put("category", category)
        put("classificationIsManual", classificationIsManual)
        put("userNote", userNote ?: JSONObject.NULL)
        put("aiReasoning", aiReasoning ?: JSONObject.NULL)
        put("isQris", isQris)
        put("cardLast4", cardLast4 ?: JSONObject.NULL)
        put("pocketName", pocketName ?: JSONObject.NULL)
        put("isReversal", isReversal)
        put("isConfirmed", isConfirmed)
    }

    companion object {
        fun fromDomain(tx: UnifiedTransaction, snapshot: AnalysisSnapshot? = null): TransactionSummaryDto {
            val key = tx.key
            val note = snapshot?.userNotes?.get(key) ?: tx.userNote
            val cat = snapshot?.categories?.get(key) ?: tx.category ?: CategoryConstants.UNCATEGORIZED
            val manual = tx.classificationIsManual || (snapshot?.manualCategories?.contains(key) == true)
            val reason = snapshot?.reasons?.get(key)?.ifBlank { tx.aiReasoning } ?: tx.aiReasoning
            val merchant = MerchantMemory.displayMerchant(tx)

            return TransactionSummaryDto(
                key = key,
                provider = tx.provider.name,
                providerName = tx.provider.displayName,
                providerTransactionId = tx.providerTransactionId,
                direction = tx.direction.name,
                amountMinor = tx.money.amountMinor,
                amountRupiah = tx.money.amountMinor / 100,
                amountFormatted = formatMinorAmount(BigInteger.valueOf(tx.money.amountMinor), tx.money.currencyCode),
                currency = tx.money.currencyCode,
                occurredAt = tx.occurredAt.toString(),
                description = tx.description,
                merchant = merchant,
                category = cat,
                classificationIsManual = manual,
                userNote = note,
                aiReasoning = reason,
                isQris = tx.isQris,
                cardLast4 = tx.cardLast4,
                pocketName = tx.pocket?.name,
                isReversal = tx.isReversal,
                isConfirmed = tx.isConfirmed,
            )
        }
    }
}

data class TransactionDetailDto(
    val summary: TransactionSummaryDto,
    val rawPayload: String,
    val sourceData: Any?,
    val canonicalKey: String?,
) {
    fun toJson(includeRaw: Boolean = false): JSONObject {
        val json = summary.toJson()
        json.put("canonicalKey", canonicalKey ?: JSONObject.NULL)
        if (includeRaw) json.put("rawPayload", rawPayload)
        json.put("sourceData", sourceData ?: JSONObject.NULL)
        return json
    }

    companion object {
        fun fromDomain(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): TransactionDetailDto {
            val summary = TransactionSummaryDto.fromDomain(tx, snapshot)
            val contextJson = CategoryContext.transaction(tx, snapshot)
            val sourceData = contextJson.opt("sourceData")
            return TransactionDetailDto(
                summary = summary,
                rawPayload = tx.rawPayload,
                sourceData = sourceData,
                canonicalKey = tx.canonicalKey,
            )
        }
    }
}

data class UpdateTransactionRequest(
    val category: String?,
    val userNote: String?,
    val reason: String?,
) {
    companion object {
        fun fromJson(json: JSONObject): UpdateTransactionRequest = UpdateTransactionRequest(
            category = if (json.has("category") && !json.isNull("category")) json.optString("category").takeIf { it.isNotBlank() } else null,
            userNote = if (json.has("userNote") && !json.isNull("userNote")) json.optString("userNote") else null,
            reason = if (json.has("reason") && !json.isNull("reason")) json.optString("reason").takeIf { it.isNotBlank() } else null,
        )
    }
}

data class BatchUpdateItem(
    val key: String,
    val category: String? = null,
    val userNote: String? = null,
    val reason: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): BatchUpdateItem? {
            val key = json.optString("key").takeIf { it.isNotBlank() } ?: return null
            return BatchUpdateItem(
                key = key,
                category = if (json.has("category") && !json.isNull("category")) json.optString("category").takeIf { it.isNotBlank() } else null,
                userNote = if (json.has("userNote") && !json.isNull("userNote")) json.optString("userNote") else null,
                reason = if (json.has("reason") && !json.isNull("reason")) json.optString("reason").takeIf { it.isNotBlank() } else null,
            )
        }
    }
}

data class BatchUpdateRequest(
    val updates: List<BatchUpdateItem>,
) {
    companion object {
        fun fromJson(json: JSONObject): BatchUpdateRequest {
            val arr = json.optJSONArray("updates") ?: JSONArray()
            val list = mutableListOf<BatchUpdateItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                BatchUpdateItem.fromJson(obj)?.let { list.add(it) }
            }
            return BatchUpdateRequest(list)
        }
    }
}

data class BatchUpdateResponse(
    val totalReceived: Int,
    val successCount: Int,
    val failureCount: Int,
    val updatedKeys: List<String>,
    val errors: Map<String, String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("totalReceived", totalReceived)
        put("successCount", successCount)
        put("failureCount", failureCount)
        put("updatedKeys", JSONArray(updatedKeys))
        put("errors", JSONObject(errors as Map<*, *>))
    }
}

data class CategoryDto(
    val name: String,
    val isCustom: Boolean,
    val icon: String?,
    val count: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("isCustom", isCustom)
        put("icon", icon ?: JSONObject.NULL)
        put("count", count)
    }
}

data class MonthSummaryDto(
    val month: String,
    val totalSpendingMinor: Long,
    val totalSpendingFormatted: String,
    val previousMonthTotalMinor: Long,
    val previousMonthTotalFormatted: String,
    val deltaPercent: Float?,
    val localSummary: String,
    val categories: List<CategorySpendingDto>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("month", month)
        put("totalSpendingMinor", totalSpendingMinor)
        put("totalSpendingFormatted", totalSpendingFormatted)
        put("previousMonthTotalMinor", previousMonthTotalMinor)
        put("previousMonthTotalFormatted", previousMonthTotalFormatted)
        put("deltaPercent", deltaPercent?.let { java.lang.Double.valueOf(it.toDouble()) } ?: JSONObject.NULL)
        put("localSummary", localSummary)
        val catArr = JSONArray()
        categories.forEach { catArr.put(it.toJson()) }
        put("categories", catArr)
    }

    companion object {
        fun fromDomain(analysis: MonthSpendingAnalysis): MonthSummaryDto = MonthSummaryDto(
            month = analysis.month.toString(),
            totalSpendingMinor = analysis.totalPersonalSpendingMinor,
            totalSpendingFormatted = formatMinorAmount(BigInteger.valueOf(analysis.totalPersonalSpendingMinor)),
            previousMonthTotalMinor = analysis.previousMonthTotalMinor,
            previousMonthTotalFormatted = formatMinorAmount(BigInteger.valueOf(analysis.previousMonthTotalMinor)),
            deltaPercent = analysis.totalDeltaPercent,
            localSummary = analysis.localSummary,
            categories = analysis.categories.map { cat ->
                CategorySpendingDto(
                    category = cat.category,
                    amountMinor = cat.amountMinor,
                    amountFormatted = formatMinorAmount(BigInteger.valueOf(cat.amountMinor)),
                    sharePercent = cat.sharePercent,
                    previousMonthAmountMinor = cat.previousMonthAmountMinor,
                    deltaPercent = cat.deltaPercent,
                    transactionCount = cat.transactions.size,
                )
            },
        )
    }
}

data class CategorySpendingDto(
    val category: String,
    val amountMinor: Long,
    val amountFormatted: String,
    val sharePercent: Float,
    val previousMonthAmountMinor: Long,
    val deltaPercent: Float?,
    val transactionCount: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("category", category)
        put("amountMinor", amountMinor)
        put("amountFormatted", amountFormatted)
        put("sharePercent", sharePercent.toDouble())
        put("previousMonthAmountMinor", previousMonthAmountMinor)
        put("deltaPercent", deltaPercent?.let { java.lang.Double.valueOf(it.toDouble()) } ?: JSONObject.NULL)
        put("transactionCount", transactionCount)
    }
}

data class KnowledgeDto(
    val knowledge: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("knowledge", knowledge)
    }
}

data class SyncRequest(
    val provider: Provider?,
) {
    companion object {
        fun fromJson(json: JSONObject?): SyncRequest {
            if (json == null) return SyncRequest(null)
            val pStr = json.optString("provider").takeIf { it.isNotBlank() } ?: return SyncRequest(null)
            val provider = runCatching { Provider.valueOf(pStr.uppercase()) }.getOrNull()
            return SyncRequest(provider)
        }
    }
}
