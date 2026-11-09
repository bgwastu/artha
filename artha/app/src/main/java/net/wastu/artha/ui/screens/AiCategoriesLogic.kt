package net.wastu.artha.ui.screens

import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.categoryProgressLabel

internal object AiCategoriesLogic {
    fun headerStatusText(progress: CategoryProgress): String? = when {
        progress.stopped -> "Stopped"
        progress.state == CategoryAutomationState.ERROR -> progress.error ?: "Categorizing stopped"
        else -> categoryProgressLabel(progress)
    }

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
}
