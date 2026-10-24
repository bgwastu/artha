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
    /** Payments in [month] that have never been categorized (no category, AI, merchant or manual label). */
    data class New(val month: YearMonth) : RecategorizeScope
}

fun recategorizeLabel(scope: RecategorizeScope): String = when (scope) {
    is RecategorizeScope.Payment -> "Re-categorize with AI"
    is RecategorizeScope.CategoryInMonth -> "Re-categorize these payments with AI"
    is RecategorizeScope.Month -> "Re-categorize this month"
    RecategorizeScope.Uncategorized -> "Re-categorize Uncategorized"
    RecategorizeScope.NeedsReview -> "Re-check payments to review"
    RecategorizeScope.All -> "Re-categorize all"
    is RecategorizeScope.New -> "Categorize new payments"
}

/** Confirmation text for runs that relabel many payments; null when no confirmation is needed. */
fun recategorizeConfirmation(scope: RecategorizeScope, count: Int, monthLabel: String? = null): String? = when (scope) {
    is RecategorizeScope.Month -> "Re-categorize $count payments in ${monthLabel ?: scope.month}? Your own picks stay the same."
    is RecategorizeScope.CategoryInMonth -> "Re-categorize $count ${scope.category} payments? Your own picks stay the same."
    RecategorizeScope.Uncategorized -> "Re-categorize $count Uncategorized payments? Your own picks stay the same."
    RecategorizeScope.All -> "Re-categorize all $count payments? Your own picks stay the same."
    is RecategorizeScope.Payment, RecategorizeScope.NeedsReview, is RecategorizeScope.New -> null
}

/** Process-wide in-flight keys so home-screen widgets can show "Categorizing" without the ViewModel. */
object CategoryActivity {
    private val _keys = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val keys: kotlinx.coroutines.flow.StateFlow<Set<String>> = _keys
    fun publish(keys: Set<String>) { _keys.value = keys }
}
