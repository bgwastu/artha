package net.wastu.artha.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisStatusTest {
    @Test fun categoryLabels() {
        assertEquals("Categorizing 32 of 140", categoryProgressLabel(CategoryProgress(CategoryAutomationState.WORKING, 32, 140)))
        assertEquals("42 of 51 categorized", categoryProgressLabel(CategoryProgress(CategoryAutomationState.READY, 42, 51)))
        assertEquals("All 51 categorized", categoryProgressLabel(CategoryProgress(CategoryAutomationState.READY, 51, 51)))
        assertEquals("Stopped", categoryProgressLabel(CategoryProgress(CategoryAutomationState.READY, 1, 2, stopped = true)))
    }
}
