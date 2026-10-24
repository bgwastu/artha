package net.wastu.artha.ui

import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.ui.screens.AiCategoriesLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
