package net.wastu.artha.ui

import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.ui.screens.AiCategoriesLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategoriesLogicTest {
    @Test
    fun statusSupportingTextPaused() {
        val progress = CategoryProgress(
            state = CategoryAutomationState.WORKING,
            labeled = 5,
            total = 10,
            paused = true,
        )
        assertEquals("Paused", AiCategoriesLogic.statusSupportingText(progress))
    }

    @Test
    fun statusSupportingTextWorking() {
        val progress = CategoryProgress(
            state = CategoryAutomationState.WORKING,
            labeled = 3,
            total = 8,
            paused = false,
        )
        assertEquals("Categorizing 3 of 8", AiCategoriesLogic.statusSupportingText(progress))
    }

    @Test
    fun statusSupportingTextReady() {
        val progress = CategoryProgress(
            state = CategoryAutomationState.READY,
            labeled = 10,
            total = 10,
            paused = false,
        )
        assertEquals("All 10 payments categorized", AiCategoriesLogic.statusSupportingText(progress))
    }

    @Test
    fun statusSupportingTextError() {
        val progress = CategoryProgress(
            state = CategoryAutomationState.ERROR,
            error = "Network timeout",
            paused = false,
        )
        assertEquals("Network timeout", AiCategoriesLogic.statusSupportingText(progress))
    }

    @Test
    fun reviewLineVisibility() {
        assertNull(AiCategoriesLogic.reviewLine(CategoryProgress(review = 0)))
        assertEquals("3 to review", AiCategoriesLogic.reviewLine(CategoryProgress(review = 3)))
    }

    @Test
    fun failedLineVisibility() {
        assertNull(AiCategoriesLogic.failedLine(CategoryProgress(failed = 0)))
        assertEquals("2 couldn't be categorized", AiCategoriesLogic.failedLine(CategoryProgress(failed = 2)))
    }

    @Test
    fun statusSupportingTextStopped() {
        val progress = CategoryProgress(
            state = CategoryAutomationState.WORKING,
            stopped = true,
        )
        assertEquals("Stopped", AiCategoriesLogic.statusSupportingText(progress))
    }

    @Test
    fun headerStatusTextVariants() {
        assertEquals("Stopped", AiCategoriesLogic.headerStatusText(CategoryProgress(stopped = true)))
        assertEquals("Stopped", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.WORKING, stopped = true)))
        assertEquals("API limit reached", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.ERROR, error = "API limit reached")))
        assertEquals("Categorizing stopped", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.ERROR, error = null)))
        assertEquals("Categorizing 4 of 12", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.WORKING, labeled = 4, total = 12)))
        assertEquals("All 12 payments categorized", AiCategoriesLogic.headerStatusText(CategoryProgress(state = CategoryAutomationState.READY, labeled = 12, total = 12)))
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
    fun rowStateText() {
        assertEquals("Set by you", AiCategoriesLogic.rowSourceLabel(CategorySource.MANUAL))
        assertEquals("Suggested by AI", AiCategoriesLogic.rowSourceLabel(CategorySource.AI))
        assertEquals("Same merchant", AiCategoriesLogic.rowSourceLabel(CategorySource.MERCHANT))
        assertEquals("Needs review", AiCategoriesLogic.rowSourceLabel(CategorySource.REVIEW))
        assertEquals("Couldn't categorize", AiCategoriesLogic.rowSourceLabel(CategorySource.FAILED))
        assertNull(AiCategoriesLogic.rowSourceLabel(CategorySource.PENDING))
        assertNull(AiCategoriesLogic.rowSourceLabel(CategorySource.NONE))

        assertEquals("Categorizing", AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.CATEGORIZING, CategorySource.AI))
        assertEquals("Queued", AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.QUEUED, CategorySource.NONE))
        assertEquals("Suggested by AI", AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.IDLE, CategorySource.AI))
        assertEquals("Set by you", AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.IDLE, CategorySource.MANUAL))
        assertEquals("Same merchant", AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.IDLE, CategorySource.MERCHANT))
        assertEquals("Needs review", AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.IDLE, CategorySource.REVIEW))
        assertEquals("Couldn't categorize", AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.IDLE, CategorySource.FAILED))
        assertNull(AiCategoriesLogic.paymentRowStateText(PaymentCategoryState.IDLE, CategorySource.NONE))
    }
}
