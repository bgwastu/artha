package net.wastu.artha.ui.widget

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    @Test fun firstLoadFailureAndStaleSavedValuesHaveDifferentLabels() {
        val now = Instant.parse("2026-10-13T06:00:00Z").toEpochMilli()
        assertEquals("Loading saved payments", widgetFreshness(null, now))
        assertEquals("Saved data unavailable", widgetFreshness(null, now, failed = true))
        assertEquals("Updated 13 Oct 13:00", widgetFreshness(now, now))
        assertEquals("Update failed\nUpdated 13 Oct 13:00", widgetFreshness(now, now, failed = true))
        assertEquals("Updating\nUpdated 13 Oct 13:00", widgetFreshness(now, now, updating = true))
        assertTrue(widgetFreshness(now - 3_600_000, now).startsWith("May be out of date"))
    }

    @Test fun recentCollectionFiltersBeforeLimitingAndSortsByPaymentTime() {
        fun payment(index: Int, confirmed: Boolean = true, provider: Provider = Provider.JAGO) =
            UnifiedTransaction(provider, "fictional-$index", Direction.DEBIT, Money(10000),
                Instant.EPOCH.plusSeconds(index.toLong()), "Fixture", "{}", isConfirmed = confirmed)
        val saved = (0..44).map { payment(it) } + payment(90, false) + payment(91, provider = Provider.GRAB)
        val result = recentWidgetPayments(saved, setOf(Provider.JAGO))
        assertEquals(40, result.size)
        assertEquals("fictional-44", result.first().providerTransactionId)
        assertEquals("fictional-5", result.last().providerTransactionId)
        assertTrue(result.all { it.isConfirmed && it.provider == Provider.JAGO })
        assertEquals(47, saved.size)
        assertEquals("Latest 40 payments", widgetPaymentScope(45))
        assertEquals("0 payments", widgetPaymentScope(0))
    }

    @Test fun optionalAnalysisDetailsRequireRoomAtTheCurrentTextScale() {
        assertFalse(showAnalysisDetails(180, 110, 1f))
        assertTrue(showAnalysisDetails(320, 400, 1f))
        assertFalse(showAnalysisDetails(320, 400, 1.5f))
        assertFalse(showAnalysisDetails(320, 400, 2f))
        assertTrue(showAnalysisDetails(500, 600, 2f))
    }
}
