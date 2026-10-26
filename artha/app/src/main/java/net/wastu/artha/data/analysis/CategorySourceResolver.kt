package net.wastu.artha.data.analysis

import java.time.YearMonth
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

/** Where one payment stands in AI categorization. Queues, counts and labels all derive from [categoryStatus]. */
enum class CategoryStatus {
    /** Income, transfers and other rows that are not spending. */
    NOT_SPENDING,
    MANUAL,
    /** No category, reason or pick yet. The only status automatic runs pick up. */
    NEW,
    /** Needs an AI label: the payment changed since the AI saw it, or its label came from elsewhere. Manual runs only. */
    STALE,
    AI,
    MERCHANT,
    /** The AI was 90% sure or less, or answered Uncategorized. */
    REVIEW,
    FAILED,
}

/** [fingerprint] is only called for settled AI labels, so callers can pass a cached hash. */
fun categoryStatus(
    tx: UnifiedTransaction,
    snapshot: AnalysisSnapshot,
    fingerprint: () -> String? = { AiCategorization.fingerprint(tx, snapshot) },
): CategoryStatus {
    if (!BudgetAnalyzer.isSpending(tx)) return CategoryStatus.NOT_SPENDING
    if (tx.classificationIsManual || tx.key in snapshot.manualCategories) return CategoryStatus.MANUAL
    val record = snapshot.aiRecords[tx.key]
    if (record?.failure != null) return CategoryStatus.FAILED
    if (record?.confidence?.let { it <= TRUSTED_ABOVE_PERCENT } == true) return CategoryStatus.REVIEW
    val ownReason = snapshot.reasons[tx.key]
    // Grouped rows can inherit a label saved under one of their linked records.
    val reason = ownReason ?: tx.aiReasoning?.takeIf { tx.key !in snapshot.categories }
    val fromAi = reason?.startsWith(AI_REASON_PREFIX) == true
    val fromMerchant = reason?.startsWith(MERCHANT_REASON_PREFIX) == true
    if (!fromAi && !fromMerchant) {
        return if (tx.key !in snapshot.categories && ownReason == null) CategoryStatus.NEW else CategoryStatus.STALE
    }
    if (fromMerchant) return CategoryStatus.MERCHANT
    // Changed evidence goes back to the model before an old answer is judged.
    if (ownReason != null && record?.fingerprint != fingerprint()) return CategoryStatus.STALE
    val category = if (ownReason != null) snapshot.categories[tx.key] else tx.category
    return if (category == CategoryConstants.UNCATEGORIZED) CategoryStatus.REVIEW else CategoryStatus.AI
}

fun needsReview(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): Boolean =
    categoryStatus(tx, snapshot) { "" } == CategoryStatus.REVIEW

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

/** Display form of [categoryStatus] for the transaction detail and lists. */
fun categorySource(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): CategorySource {
    // Display never needs the evidence hash: a stale AI label still reads as the AI's.
    return when (categoryStatus(tx, snapshot) { snapshot.aiRecords[tx.key]?.fingerprint }) {
        CategoryStatus.MANUAL -> CategorySource.MANUAL
        CategoryStatus.FAILED -> CategorySource.FAILED
        CategoryStatus.REVIEW -> CategorySource.REVIEW
        CategoryStatus.MERCHANT -> CategorySource.MERCHANT
        CategoryStatus.AI -> CategorySource.AI
        CategoryStatus.NEW, CategoryStatus.STALE -> {
            val config = snapshot.aiConfig
            val configured = config.baseUrl.isNotBlank() && config.model.isNotBlank() && config.apiKey.isNotBlank()
            if (snapshot.analysisEnabled && configured) CategorySource.PENDING else CategorySource.NONE
        }
        CategoryStatus.NOT_SPENDING -> CategorySource.NONE
    }
}

/** Payments the user has to look at: the AI was 90% sure or less (REVIEW) or could not decide (FAILED). */
data class CategoryAttention(val review: Int = 0, val failed: Int = 0) {
    val total: Int get() = review + failed
}

fun attentionSource(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): CategorySource? =
    when (categoryStatus(tx, snapshot) { "" }) {
        CategoryStatus.REVIEW -> CategorySource.REVIEW
        CategoryStatus.FAILED -> CategorySource.FAILED
        else -> null
    }

fun inMonth(tx: UnifiedTransaction, month: YearMonth?): Boolean =
    month == null || YearMonth.from(tx.occurredAt.atZone(net.wastu.artha.core.model.AccountingTime.zone)) == month

fun categoryAttention(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot, month: YearMonth?): CategoryAttention {
    var review = 0
    var failed = 0
    for (tx in rows) {
        if (!inMonth(tx, month)) continue
        when (attentionSource(tx, snapshot)) {
            CategorySource.REVIEW -> review++
            CategorySource.FAILED -> failed++
            else -> Unit
        }
    }
    return CategoryAttention(review, failed)
}
