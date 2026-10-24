package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val itemKeys = setOf("items", "orderitems", "products", "menu")

fun hasItemDetails(tx: UnifiedTransaction): Boolean {
    val obj = runCatching { JSONObject(tx.rawPayload) }.getOrNull() ?: return false
    return inspectForItems(obj, 0)
}

private fun inspectForItems(value: Any?, depth: Int): Boolean {
    if (depth > 16 || value == null) return false
    when (value) {
        is JSONObject -> {
            for (key in value.keys()) {
                val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
                if (normalized in itemKeys) {
                    val arr = value.optJSONArray(key)
                    if (arr != null && arr.length() > 0) return true
                }
                val child = value.get(key)
                if (normalized == "rawpayload" && child is String) {
                    val nested = runCatching { JSONObject(child) }.getOrNull()
                    if (nested != null && inspectForItems(nested, depth + 1)) return true
                } else if (child is JSONObject || child is JSONArray) {
                    if (inspectForItems(child, depth + 1)) return true
                }
            }
        }
        is JSONArray -> {
            for (i in 0 until value.length()) {
                val child = value.opt(i)
                if ((child is JSONObject || child is JSONArray) && inspectForItems(child, depth + 1)) return true
            }
        }
    }
    return false
}

fun needsReview(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): Boolean {
    val isManual = tx.classificationIsManual || tx.key in snapshot.manualCategories
    if (isManual) return false
    val confidence = snapshot.aiConfidence[tx.key] ?: return false
    return when (confidence) {
        CategoryConfidence.LOW -> true
        CategoryConfidence.MEDIUM -> !hasItemDetails(tx)
        CategoryConfidence.HIGH -> false
    }
}

fun aiCategoryReason(snapshot: AnalysisSnapshot, key: String): String? {
    if (key in snapshot.manualCategories) return null
    val reason = snapshot.reasons[key] ?: return null
    return if (reason.startsWith(AI_REASON_PREFIX)) reason.removePrefix(AI_REASON_PREFIX).trim() else null
}

fun categoryReasonText(snapshot: AnalysisSnapshot, key: String): String? {
    if (key in snapshot.manualCategories) return null
    val reason = snapshot.reasons[key] ?: return null
    return when {
        reason.startsWith(AI_REASON_PREFIX) -> reason.removePrefix(AI_REASON_PREFIX).trim()
        reason.startsWith(MERCHANT_REASON_PREFIX) -> reason.removePrefix(MERCHANT_REASON_PREFIX).trim()
        else -> null
    }
}

fun categorySource(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): CategorySource {
    if (tx.classificationIsManual || tx.key in snapshot.manualCategories) return CategorySource.MANUAL
    if (tx.key in snapshot.categoryFailures) return CategorySource.FAILED
    if (needsReview(tx, snapshot)) return CategorySource.REVIEW
    val reason = snapshot.reasons[tx.key] ?: tx.aiReasoning
    if (reason?.startsWith(MERCHANT_REASON_PREFIX) == true) return CategorySource.MERCHANT
    if (reason?.startsWith(AI_REASON_PREFIX) == true) return CategorySource.AI
    val aiConfigured = snapshot.aiConfig.baseUrl.isNotBlank() &&
        snapshot.aiConfig.model.isNotBlank() &&
        snapshot.aiConfig.apiKey.isNotBlank()
    if (BudgetAnalyzer.isSpending(tx) && snapshot.analysisEnabled && aiConfigured) return CategorySource.PENDING
    return CategorySource.NONE
}
