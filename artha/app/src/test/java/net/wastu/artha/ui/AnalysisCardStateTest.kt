package net.wastu.artha.ui

import net.wastu.artha.ui.screens.AiCardLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisCardStateTest {
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
}
