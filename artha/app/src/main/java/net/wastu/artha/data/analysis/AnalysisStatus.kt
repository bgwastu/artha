package net.wastu.artha.data.analysis

/** Why an automatic monthly analysis is held back. */
enum class AnalysisWait { IMPORT, CATEGORIES }

/** User-visible state of the AI commentary for one month. Every early return in the pipeline maps to one of these. */
sealed interface MonthAnalysisStatus {
    data object Off : MonthAnalysisStatus
    data object NotConfigured : MonthAnalysisStatus
    data object NoSpending : MonthAnalysisStatus
    data object NeverRun : MonthAnalysisStatus
    data class Waiting(val reason: AnalysisWait) : MonthAnalysisStatus
    data class Running(val stage: AnalysisStage?) : MonthAnalysisStatus
    data class UpToDate(val savedAt: Long?) : MonthAnalysisStatus
    /** A summary exists but payments, notes, context or categories changed since it was made. */
    data class Outdated(val savedAt: Long?) : MonthAnalysisStatus
    data class Failed(val message: String, val at: Long, val hasSummary: Boolean) : MonthAnalysisStatus
}

/** Saved spending payments the AI has labeled, out of those it is allowed to label (manual picks excluded). */
data class CategoryProgress(
    val state: CategoryAutomationState = CategoryAutomationState.NOT_CONFIGURED,
    val labeled: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    /** Payments marked "Needs review". */
    val review: Int = 0,
    /** Payments the AI gave up on. */
    val failed: Int = 0,
    /** Auto-categorize switched off; manual runs still work. */
    val paused: Boolean = false,
    /** Payment keys in a request right now, for per-payment spinners. */
    val activeKeys: Set<String> = emptySet(),
    /** Payment keys waiting their turn in a run. */
    val queuedKeys: Set<String> = emptySet(),
    /** The user pressed Stop; cleared by Retry, a manual run, or the next new payment. */
    val stopped: Boolean = false,
)

/** Per-payment lock while AI categorization owns it: editing that payment is disabled. */
enum class PaymentCategoryState { IDLE, QUEUED, CATEGORIZING }

fun paymentCategoryState(key: String, progress: CategoryProgress): PaymentCategoryState = when (key) {
    in progress.activeKeys -> PaymentCategoryState.CATEGORIZING
    in progress.queuedKeys -> PaymentCategoryState.QUEUED
    else -> PaymentCategoryState.IDLE
}

fun paymentCategoryStateLabel(state: PaymentCategoryState): String? = when (state) {
    PaymentCategoryState.IDLE -> null
    PaymentCategoryState.QUEUED -> "Queued"
    PaymentCategoryState.CATEGORIZING -> "Categorizing"
}

/** Where a payment's category came from, for the transaction detail. */
enum class CategorySource { MANUAL, AI, MERCHANT, REVIEW, FAILED, PENDING, NONE }

/** Pure derivation so the ViewModel and tests agree on precedence. */
fun monthAnalysisStatus(
    enabled: Boolean,
    configured: Boolean,
    hasSpending: Boolean,
    hasSummary: Boolean,
    savedAt: Long?,
    upToDate: Boolean,
    running: Boolean,
    stage: AnalysisStage?,
    waiting: AnalysisWait?,
    failure: Pair<String, Long>?,
): MonthAnalysisStatus = when {
    !enabled -> MonthAnalysisStatus.Off
    !configured -> MonthAnalysisStatus.NotConfigured
    running -> MonthAnalysisStatus.Running(stage)
    !hasSpending -> MonthAnalysisStatus.NoSpending
    failure != null -> MonthAnalysisStatus.Failed(failure.first, failure.second, hasSummary)
    waiting != null && !(hasSummary && upToDate) -> MonthAnalysisStatus.Waiting(waiting)
    !hasSummary -> MonthAnalysisStatus.NeverRun
    upToDate -> MonthAnalysisStatus.UpToDate(savedAt)
    else -> MonthAnalysisStatus.Outdated(savedAt)
}

internal fun relativeTime(at: Long, now: Long): String {
    val minutes = (now - at).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** One-line status shown under "AI analysis" and in the settings sheet. */
fun analysisStatusLabel(status: MonthAnalysisStatus, now: Long): String = when (status) {
    MonthAnalysisStatus.Off -> "Analysis is off"
    MonthAnalysisStatus.NotConfigured -> "Add an API key to analyze"
    MonthAnalysisStatus.NoSpending -> "No spending to analyze"
    MonthAnalysisStatus.NeverRun -> "Not analyzed yet"
    is MonthAnalysisStatus.Waiting -> when (status.reason) {
        AnalysisWait.IMPORT -> "Waiting for import to finish"
        AnalysisWait.CATEGORIES -> "Waiting for categories"
    }
    is MonthAnalysisStatus.Running -> status.stage?.label ?: "Preparing analysis"
    is MonthAnalysisStatus.UpToDate -> status.savedAt?.let { "Up to date, ${relativeTime(it, now)}" } ?: "Up to date"
    is MonthAnalysisStatus.Outdated -> "New activity since this analysis"
    is MonthAnalysisStatus.Failed -> status.message
}

/** Primary action label; null when no action applies. */
fun analysisActionLabel(status: MonthAnalysisStatus): String? = when (status) {
    MonthAnalysisStatus.Off, MonthAnalysisStatus.NoSpending, is MonthAnalysisStatus.Running -> null
    MonthAnalysisStatus.NotConfigured -> "Set up AI"
    MonthAnalysisStatus.NeverRun, is MonthAnalysisStatus.Waiting -> "Analyze now"
    is MonthAnalysisStatus.UpToDate, is MonthAnalysisStatus.Outdated -> "Re-analyze"
    is MonthAnalysisStatus.Failed -> "Retry"
}

fun categoryProgressLabel(progress: CategoryProgress): String? = when (progress.state) {
    CategoryAutomationState.NOT_CONFIGURED -> null
    CategoryAutomationState.WORKING -> "Categorizing ${progress.labeled} of ${progress.total}"
    CategoryAutomationState.READY -> if (progress.total == 0) null else "All ${progress.total} payments categorized"
    CategoryAutomationState.ERROR -> progress.error ?: "Categorizing stopped"
}
