package net.wastu.artha.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisStatusTest {
    private fun status(
        enabled: Boolean = true, configured: Boolean = true, hasSpending: Boolean = true, hasSummary: Boolean = false,
        upToDate: Boolean = false, running: Boolean = false, waiting: AnalysisWait? = null, failure: Pair<String, Long>? = null,
    ) = monthAnalysisStatus(enabled, configured, hasSpending, hasSummary, 1L, upToDate, running, null, waiting, failure)

    @Test fun precedence() {
        assertEquals(MonthAnalysisStatus.Off, status(enabled = false, running = true))
        assertEquals(MonthAnalysisStatus.NotConfigured, status(configured = false))
        assertEquals(MonthAnalysisStatus.Running(null), status(running = true, failure = "x" to 2L))
        assertEquals(MonthAnalysisStatus.NoSpending, status(hasSpending = false))
        assertEquals(MonthAnalysisStatus.Failed("x", 2L, true), status(hasSummary = true, failure = "x" to 2L))
        assertEquals(MonthAnalysisStatus.Waiting(AnalysisWait.IMPORT), status(waiting = AnalysisWait.IMPORT))
        assertEquals(MonthAnalysisStatus.UpToDate(1L), status(hasSummary = true, upToDate = true, waiting = AnalysisWait.IMPORT))
        assertEquals(MonthAnalysisStatus.NeverRun, status())
        assertEquals(MonthAnalysisStatus.Outdated(1L), status(hasSummary = true))
    }

    @Test fun labels() {
        assertEquals("Up to date, 5 min ago", analysisStatusLabel(MonthAnalysisStatus.UpToDate(0L), 5 * 60_000L))
        assertEquals("Re-analyze", analysisActionLabel(MonthAnalysisStatus.Outdated(null)))
        assertEquals(null, analysisActionLabel(MonthAnalysisStatus.Running(null)))
        assertEquals("Categorizing 32 of 140", categoryProgressLabel(CategoryProgress(CategoryAutomationState.WORKING, 32, 140)))
    }
}
