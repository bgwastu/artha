package net.wastu.artha.ui.screens

import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.categoryProgressLabel

internal object AiCategoriesLogic {
    fun headerStatusText(progress: CategoryProgress): String? = when {
        progress.stopped -> "Stopped"
        progress.state == CategoryAutomationState.ERROR -> progress.error ?: "Categorizing stopped"
        else -> categoryProgressLabel(progress)
    }

    fun showStopAction(progress: CategoryProgress): Boolean =
        progress.state == CategoryAutomationState.WORKING

    fun showRetryAction(progress: CategoryProgress): Boolean =
        progress.state != CategoryAutomationState.WORKING &&
            (progress.state == CategoryAutomationState.ERROR || progress.stopped || progress.failed > 0)

    fun showCategorizeNewAction(progress: CategoryProgress, newCount: Int): Boolean =
        progress.state != CategoryAutomationState.WORKING && newCount > 0

    fun historyHeadline(pending: Int, progress: CategoryProgress): String = when {
        progress.state == CategoryAutomationState.WORKING -> categoryProgressLabel(progress) ?: "Categorizing"
        pending == 0 -> "All payments categorized"
        pending == 1 -> "1 payment not categorized"
        else -> "$pending payments not categorized"
    }

    fun historySupporting(progress: CategoryProgress, configured: Boolean): String? = when {
        !configured -> "Add a provider below"
        progress.stopped || progress.state == CategoryAutomationState.ERROR -> headerStatusText(progress)
        else -> null
    }

    fun iconContentDescription(state: PaymentCategoryState, source: CategorySource): String = when (state) {
        PaymentCategoryState.CATEGORIZING -> "Categorizing"
        PaymentCategoryState.QUEUED -> "Queued"
        PaymentCategoryState.IDLE -> when (source) {
            CategorySource.MANUAL -> "Set by you"
            CategorySource.AI -> "Suggested by AI"
            CategorySource.MERCHANT -> "Same merchant"
            CategorySource.REVIEW -> "Needs review"
            CategorySource.FAILED -> "Couldn't categorize"
            CategorySource.PENDING, CategorySource.NONE -> "Not categorized"
        }
    }

    fun paymentRowSupportingText(state: PaymentCategoryState, category: String?): String = when (state) {
        PaymentCategoryState.CATEGORIZING -> "Categorizing"
        PaymentCategoryState.QUEUED -> "Queued"
        PaymentCategoryState.IDLE -> category ?: "Uncategorized"
    }
}
