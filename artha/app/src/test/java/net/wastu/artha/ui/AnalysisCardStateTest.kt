package net.wastu.artha.ui

import net.wastu.artha.data.analysis.AnalysisStage
import net.wastu.artha.data.analysis.AnalysisWait
import net.wastu.artha.data.analysis.MonthAnalysisStatus
import net.wastu.artha.ui.screens.AiCardLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCardStateTest {
    @Test
    fun defaultExpansionKeyedOnSummary() {
        assertTrue(AiCardLogic.shouldExpandByDefault(hasSummary = true))
        assertFalse(AiCardLogic.shouldExpandByDefault(hasSummary = false))
    }

    @Test
    fun actionRowVisibilityWithoutSummary() {
        // When there is no summary, action row is reachable even when collapsed
        assertTrue(AiCardLogic.shouldShowActionRow(hasSummary = false, expanded = false, readOnly = false))
        assertTrue(AiCardLogic.shouldShowActionRow(hasSummary = false, expanded = true, readOnly = false))
    }

    @Test
    fun actionRowVisibilityWithSummary() {
        // When summary exists, action row is shown only when expanded
        assertTrue(AiCardLogic.shouldShowActionRow(hasSummary = true, expanded = true, readOnly = false))
        assertFalse(AiCardLogic.shouldShowActionRow(hasSummary = true, expanded = false, readOnly = false))
    }

    @Test
    fun actionRowHiddenWhenReadOnly() {
        assertFalse(AiCardLogic.shouldShowActionRow(hasSummary = false, expanded = false, readOnly = true))
        assertFalse(AiCardLogic.shouldShowActionRow(hasSummary = false, expanded = true, readOnly = true))
        assertFalse(AiCardLogic.shouldShowActionRow(hasSummary = true, expanded = true, readOnly = true))
        assertFalse(AiCardLogic.shouldShowActionRow(hasSummary = true, expanded = false, readOnly = true))
    }

    @Test
    fun categoryProgressFractionCalculations() {
        assertEquals(0f, AiCardLogic.categoryProgressFraction(0, 0), 0.001f)
        assertEquals(0f, AiCardLogic.categoryProgressFraction(0, 100), 0.001f)
        assertEquals(0.5f, AiCardLogic.categoryProgressFraction(50, 100), 0.001f)
        assertEquals(1f, AiCardLogic.categoryProgressFraction(100, 100), 0.001f)
        // Coerced bounds
        assertEquals(1f, AiCardLogic.categoryProgressFraction(150, 100), 0.001f)
        assertEquals(0f, AiCardLogic.categoryProgressFraction(-5, 100), 0.001f)
    }

    @Test
    fun primaryActionRouting() {
        assertTrue(AiCardLogic.isConfigureAction(MonthAnalysisStatus.NotConfigured))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.NeverRun))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.UpToDate(1000L)))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.Outdated(1000L)))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.Failed("error", 1000L, false)))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.Running(AnalysisStage.REVIEWING)))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.Waiting(AnalysisWait.CATEGORIES)))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.NoSpending))
        assertFalse(AiCardLogic.isConfigureAction(MonthAnalysisStatus.Off))
    }
}
