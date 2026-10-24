package net.wastu.artha.ui

import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.ui.screens.AiCategoriesLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun headerActionVisibilityRules() {
        val working = CategoryProgress(state = CategoryAutomationState.WORKING)
        val ready = CategoryProgress(state = CategoryAutomationState.READY)
        val error = CategoryProgress(state = CategoryAutomationState.ERROR)
        val stopped = CategoryProgress(state = CategoryAutomationState.READY, stopped = true)
        val withFailed = CategoryProgress(state = CategoryAutomationState.READY, failed = 2)

        // Stop action
        assertTrue(AiCategoriesLogic.showStopAction(working))
        assertFalse(AiCategoriesLogic.showStopAction(ready))
        assertFalse(AiCategoriesLogic.showStopAction(error))
        assertFalse(AiCategoriesLogic.showStopAction(stopped))

        // Retry action
        assertTrue(AiCategoriesLogic.showRetryAction(error))
        assertTrue(AiCategoriesLogic.showRetryAction(stopped))
        assertTrue(AiCategoriesLogic.showRetryAction(withFailed))
        assertFalse(AiCategoriesLogic.showRetryAction(ready))
        assertFalse(AiCategoriesLogic.showRetryAction(working.copy(failed = 3, stopped = true)))

        // Categorize new action
        assertTrue(AiCategoriesLogic.showCategorizeNewAction(ready, newCount = 5))
        assertFalse(AiCategoriesLogic.showCategorizeNewAction(ready, newCount = 0))
        assertFalse(AiCategoriesLogic.showCategorizeNewAction(working, newCount = 5))
    }

    @Test
    fun iconContentDescriptions() {
        assertEquals("Categorizing", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.CATEGORIZING, CategorySource.AI))
        assertEquals("Queued", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.QUEUED, CategorySource.NONE))
        assertEquals("Set by you", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.IDLE, CategorySource.MANUAL))
        assertEquals("Suggested by AI", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.IDLE, CategorySource.AI))
        assertEquals("Same merchant", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.IDLE, CategorySource.MERCHANT))
        assertEquals("Needs review", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.IDLE, CategorySource.REVIEW))
        assertEquals("Couldn't categorize", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.IDLE, CategorySource.FAILED))
        assertEquals("Not categorized", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.IDLE, CategorySource.PENDING))
        assertEquals("Not categorized", AiCategoriesLogic.iconContentDescription(PaymentCategoryState.IDLE, CategorySource.NONE))
    }

    @Test
    fun paymentRowSupportingText() {
        assertEquals("Categorizing", AiCategoriesLogic.paymentRowSupportingText(PaymentCategoryState.CATEGORIZING, "Groceries"))
        assertEquals("Queued", AiCategoriesLogic.paymentRowSupportingText(PaymentCategoryState.QUEUED, "Groceries"))
        assertEquals("Groceries", AiCategoriesLogic.paymentRowSupportingText(PaymentCategoryState.IDLE, "Groceries"))
        assertEquals("Uncategorized", AiCategoriesLogic.paymentRowSupportingText(PaymentCategoryState.IDLE, null))
    }
}
