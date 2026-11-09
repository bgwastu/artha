package net.wastu.artha.ui

import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.ui.screens.AiCategoriesLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiCategoriesLogicTest {
    @Test
    fun headerStatusTextVariants() {
        assertEquals("Stopped", AiCategoriesLogic.headerStatusText(CategoryProgress(stopped = true)))
        assertEquals("Stopped", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.WORKING, stopped = true)))
        assertEquals("API limit reached", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.ERROR, error = "API limit reached")))
        assertEquals("Categorizing stopped", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.ERROR, error = null)))
        assertEquals("Categorizing 4 of 12", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.WORKING, labeled = 4, total = 12)))
        assertEquals("All 12 categorized", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.READY, labeled = 12, total = 12)))
        assertNull(AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.NOT_CONFIGURED)))
    }

    @Test
    fun historyRowCountsPendingPaymentsAndSaysWhyItCannotStart() {
        val idle = CategoryProgress(state = CategoryAutomationState.READY)
        assertEquals("All payments categorized", AiCategoriesLogic.historyHeadline(0, idle))
        assertEquals("1 payment not categorized", AiCategoriesLogic.historyHeadline(1, idle))
        assertEquals("1225 payments not categorized", AiCategoriesLogic.historyHeadline(1225, idle))
        val working = CategoryProgress(state = CategoryAutomationState.WORKING, runDone = 99, runTotal = 1225)
        assertEquals("Categorizing 99 of 1225", AiCategoriesLogic.historyHeadline(1126, working))
        assertEquals(null, AiCategoriesLogic.historySupporting(working, configured = true))
        assertEquals("Add a provider below", AiCategoriesLogic.historySupporting(idle, configured = false))
        assertEquals("Stopped", AiCategoriesLogic.historySupporting(idle.copy(stopped = true), configured = true))
    }
}
