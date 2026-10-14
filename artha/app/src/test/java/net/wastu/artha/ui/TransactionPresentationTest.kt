package net.wastu.artha.ui

import java.time.LocalDate
import java.time.Instant
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.screens.TransactionFilters
import net.wastu.artha.ui.screens.TransactionPeriod
import net.wastu.artha.ui.screens.transactionSearchScope
import org.junit.Assert.*
import org.junit.Test

class TransactionPresentationTest {
    @org.junit.Test fun reversedPaymentShowsZeroButOriginalChargeRemainsAvailable() {
        val tx = net.wastu.artha.core.model.UnifiedTransaction(net.wastu.artha.core.model.Provider.JAGO, "fixture",
            net.wastu.artha.core.model.Direction.DEBIT, net.wastu.artha.core.model.Money(125000),
            java.time.Instant.EPOCH, "Fictional reversed payment", "{}", isReversal = true)
        assertEquals("Rp0", paymentRowAmount(tx))
        assertEquals(tx.money, originalPaymentMoney(tx))
        val partiallyRefunded = tx.copy(money = net.wastu.artha.core.model.Money(25000), isReversal = false, rawPayload = """{"grossPaidAmountMinor":125000}""")
        assertEquals(tx.money, originalPaymentMoney(partiallyRefunded))
    }

    @Test fun reversingCreditsKeepTheirRecordedIncomingAmountInListsAndWidgets() {
        val ordinary = transaction("fixture")
        val credit = ordinary.copy(direction = Direction.CREDIT, isReversal = true)
        assertEquals("+${formatMoney(credit.money)}", paymentRowAmount(credit))
        assertEquals("Incoming reversal", net.wastu.artha.ui.screens.transactionAccountingLabel(credit))
        assertFalse(net.wastu.artha.data.analysis.BudgetAnalyzer.isSpending(credit))
        assertEquals(ordinary.money, credit.money)
        assertEquals("Rp0", paymentRowAmount(ordinary.copy(isReversal = true)))
        assertEquals("−${formatMoney(ordinary.money)}", paymentRowAmount(ordinary))
    }

    @Test fun browsingAndSearchUseTheSameConfirmationSourceAndDirectionScope() {
        val records = listOf(
            transaction("payment"), transaction("order", confirmed = false),
            transaction("incoming", direction = Direction.CREDIT), transaction("other-source", provider = Provider.GRAB),
        )
        assertEquals(listOf("payment", "incoming", "other-source"), records.filter(TransactionFilters()::matches).map { it.providerTransactionId })
        val selected = TransactionFilters(provider = Provider.JAGO, direction = Direction.DEBIT)
        assertEquals(listOf("payment"), records.filter(selected::matches).map { it.providerTransactionId })
        assertFalse(records[1].isConfirmed)
    }

    @Test fun allSavedSearchExpandsBeyondCurrentDateAndSourceFiltersWithoutChangingRecords() {
        val saved = listOf(transaction("current"), transaction("unmatched", confirmed = false),
            transaction("outside-current-view", provider = Provider.GRAB))
        val currentDateView = saved.take(2)
        val filters = TransactionFilters(provider = Provider.JAGO)
        assertEquals(listOf("current"), transactionSearchScope(currentDateView, saved, filters, false).map { it.providerTransactionId })
        assertEquals(listOf("current", "outside-current-view"), transactionSearchScope(currentDateView, saved, filters, true).map { it.providerTransactionId })
        assertFalse(transactionSearchScope(currentDateView, saved, filters, false).any { !it.isConfirmed })
        assertFalse(transactionSearchScope(currentDateView, saved, filters, true).any { !it.isConfirmed })
        assertEquals(3, saved.size)
        assertFalse(saved[1].isConfirmed)
    }

    @Test fun previousMonthUsesItsActualLastDayAcrossLeapYearAndYearBoundary() {
        assertRange(TransactionPeriod.LAST_MONTH.range(LocalDate.of(2024, 3, 15)), LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29))
        assertRange(TransactionPeriod.LAST_MONTH.range(LocalDate.of(2026, 1, 3)), LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31))
    }

    @Test fun rollingMonthIncludesThirtyAccountingDaysAndCurrentMonthStopsAtToday() {
        val today = LocalDate.of(2026, 1, 12)
        assertRange(TransactionPeriod.LAST_30_DAYS.range(today), LocalDate.of(2025, 12, 14), today)
        assertRange(TransactionPeriod.THIS_MONTH.range(today), LocalDate.of(2026, 1, 1), today)
        val end = TransactionPeriod.THIS_MONTH.range(today)!!.second!!
        assertTrue(today.atTime(23, 59).atZone(AccountingTime.zone).toInstant().toEpochMilli() < AccountingTime.dayAfter(end))
        assertEquals(today.plusDays(1).atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli(), AccountingTime.dayAfter(end))
        assertNull(TransactionPeriod.ALL.range(today))
    }

    private fun assertRange(range: Pair<Long?, Long?>?, first: LocalDate, last: LocalDate) {
        fun day(value: Long?) = Instant.ofEpochMilli(value!!).atZone(AccountingTime.zone).toLocalDate()
        assertEquals(first, day(range!!.first))
        assertEquals(last, day(range.second))
    }

    private fun transaction(id: String, provider: Provider = Provider.JAGO, direction: Direction = Direction.DEBIT, confirmed: Boolean = true) =
        UnifiedTransaction(provider, id, direction, Money(10000), Instant.parse("2026-01-12T02:00:00Z"), "Fixture", "{}", isConfirmed = confirmed)
}
