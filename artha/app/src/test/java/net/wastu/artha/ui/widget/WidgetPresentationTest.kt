package net.wastu.artha.ui.widget

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    @Test fun genuineEmptySpendingAndAbsentPreviousMonthRenderWithoutInventingAPayment() {
        val month = java.time.YearMonth.of(2026, 10)
        val empty = analysisWidgetAmounts(net.wastu.artha.data.analysis.BudgetAnalyzer.analyzeMonth(month, emptyList()))
        assertEquals("Rp0", empty.total.filterNot(Char::isWhitespace))
        assertNull(empty.previous)
        val payment = UnifiedTransaction(Provider.JAGO, "fictional-current", Direction.DEBIT, Money(123400),
            Instant.parse("2026-10-02T00:00:00Z"), "Fixture", "{}")
        val populated = analysisWidgetAmounts(net.wastu.artha.data.analysis.BudgetAnalyzer.analyzeMonth(month, listOf(payment)))
        assertEquals(net.wastu.artha.ui.formatMoney(payment.money), populated.total)
        assertNull(populated.previous)
        val previousOnly = analysisWidgetAmounts(net.wastu.artha.data.analysis.BudgetAnalyzer.analyzeMonth(month.plusMonths(1), listOf(payment)))
        assertEquals("Rp0", previousOnly.total.filterNot(Char::isWhitespace))
        assertEquals(net.wastu.artha.ui.formatMoney(payment.money), previousOnly.previous)
    }

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
    }

    @Test fun groupedPaymentsUseJakartaDaysAndPreserveStableIdentities() {
        fun payment(id: String, time: String, confirmed: Boolean = true) = UnifiedTransaction(Provider.JAGO, id, Direction.DEBIT,
            Money(10000), Instant.parse(time), "Fixture", "{}", isConfirmed = confirmed)
        val latest = payment("latest", "2026-10-14T01:00:00Z")
        val midnight = payment("midnight", "2026-10-13T18:00:00Z") // 14 October in Jakarta.
        val earlier = payment("earlier", "2026-10-13T10:00:00Z")
        val input = listOf(earlier, midnight, latest, payment("ignored", "2026-10-14T02:00:00Z", false))
        val rows = groupedWidgetPayments(input, setOf(Provider.JAGO))
        assertEquals(listOf(java.time.LocalDate.of(2026, 10, 14), java.time.LocalDate.of(2026, 10, 13)),
            rows.filterIsInstance<WidgetPaymentRow.Day>().map { it.date })
        assertEquals(listOf("latest", "midnight", "earlier"), rows.filterIsInstance<WidgetPaymentRow.Payment>().map { it.transaction.providerTransactionId })
        assertEquals(rows.size, rows.map(::widgetRowId).distinct().size)
        assertEquals(rows.map(::widgetRowId), groupedWidgetPayments(input.reversed(), setOf(Provider.JAGO)).map(::widgetRowId))
        assertTrue(groupedWidgetPayments(emptyList(), setOf(Provider.JAGO)).isEmpty())
    }

    @Test fun dayHeadersGiveRelativeDatesAndIncludeOlderYears() {
        val today = java.time.LocalDate.of(2026, 10, 14)
        assertEquals("Today", widgetDayLabel(today, today))
        assertEquals("Yesterday", widgetDayLabel(today.minusDays(1), today))
        assertEquals("12 Oct", widgetDayLabel(today.minusDays(2), today))
        assertEquals("14 Oct 2025", widgetDayLabel(today.minusYears(1), today))
    }

    @Test fun optionalAnalysisDetailsRequireRoomAtTheCurrentTextScale() {
        assertFalse(showAnalysisDetails(180, 110, 1f))
        assertTrue(showAnalysisDetails(320, 400, 1f))
        assertFalse(showAnalysisDetails(320, 400, 1.5f))
        assertFalse(showAnalysisDetails(320, 400, 2f))
        assertTrue(showAnalysisDetails(500, 600, 2f))
    }
}
