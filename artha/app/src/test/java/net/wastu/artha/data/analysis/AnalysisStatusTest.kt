package net.wastu.artha.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisStatusTest {
    @Test fun runProgressReplacesLabeledCountWhileARunIsActive() {
        val run = CategoryProgress(CategoryAutomationState.WORKING, labeled = 51, total = 51, runDone = 12, runTotal = 48)
        assertEquals("Categorizing 12 of 48", categoryProgressLabel(run))
    }

    @Test fun runMessageListsOnlyWhatHappened() {
        assertEquals("48 categorized", categoryRunMessage(CategoryRunResult(48, 0, 0)))
        assertEquals("48 categorized, 3 to review, 2 failed", categoryRunMessage(CategoryRunResult(48, 3, 2)))
    }

    @Test fun categoryLabels() {
        assertEquals("Categorizing 32 of 140", categoryProgressLabel(CategoryProgress(CategoryAutomationState.WORKING, 32, 140)))
        assertEquals("42 of 51 categorized", categoryProgressLabel(CategoryProgress(CategoryAutomationState.READY, 42, 51)))
        assertEquals("All 51 categorized", categoryProgressLabel(CategoryProgress(CategoryAutomationState.READY, 51, 51)))
        assertEquals("Stopped", categoryProgressLabel(CategoryProgress(CategoryAutomationState.READY, 1, 2, stopped = true)))
    }
}
