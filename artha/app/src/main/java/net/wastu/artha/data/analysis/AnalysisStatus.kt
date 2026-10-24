package net.wastu.artha.data.analysis

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

fun categoryProgressLabel(progress: CategoryProgress): String? = when (progress.state) {
    CategoryAutomationState.NOT_CONFIGURED -> null
    CategoryAutomationState.WORKING -> "Categorizing ${progress.labeled} of ${progress.total}"
    CategoryAutomationState.READY -> when {
        progress.stopped -> "Stopped"
        progress.total == 0 -> null
        progress.labeled >= progress.total -> "All ${progress.total} categorized"
        else -> "${progress.labeled} of ${progress.total} categorized"
    }
    CategoryAutomationState.ERROR -> progress.error ?: "Categorizing stopped"
}
