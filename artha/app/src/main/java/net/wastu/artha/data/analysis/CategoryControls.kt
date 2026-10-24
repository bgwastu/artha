package net.wastu.artha.data.analysis

import java.time.YearMonth

enum class CategoryConfidence { HIGH, MEDIUM, LOW }

/** Reason prefixes stored in AnalysisSnapshot.reasons; they identify where a non-manual category came from. */
const val AI_REASON_PREFIX = "AI suggestion: "
const val MERCHANT_REASON_PREFIX = "Same merchant: "

/** What a manual "Re-categorize" run covers. Manual picks are never touched except by Payment(replaceManual = true). */
sealed interface RecategorizeScope {
    data class Payment(val key: String, val replaceManual: Boolean = false) : RecategorizeScope
    data class CategoryInMonth(val category: String, val month: YearMonth) : RecategorizeScope
    data class Month(val month: YearMonth) : RecategorizeScope
    data object Uncategorized : RecategorizeScope
    data object NeedsReview : RecategorizeScope
    data object All : RecategorizeScope
}

fun recategorizeLabel(scope: RecategorizeScope): String = when (scope) {
    is RecategorizeScope.Payment -> "Re-categorize with AI"
    is RecategorizeScope.CategoryInMonth -> "Re-categorize these payments with AI"
    is RecategorizeScope.Month -> "Re-categorize this month"
    RecategorizeScope.Uncategorized -> "Re-categorize Uncategorized"
    RecategorizeScope.NeedsReview -> "Re-check payments to review"
    RecategorizeScope.All -> "Re-categorize all"
}
