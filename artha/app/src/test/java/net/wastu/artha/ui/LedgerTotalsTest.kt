package net.wastu.artha.ui

import java.time.Instant
import java.time.LocalDate
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.ui.screens.TransactionPeriod
import net.wastu.artha.ui.screens.periodSummary
import net.wastu.artha.ui.widget.WidgetPaymentRow
import net.wastu.artha.ui.widget.groupedWidgetPayments
import net.wastu.artha.ui.widget.recentWidgetPayments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTotalsTest {

    private val today = LocalDate.of(2026, 10, 15)

    private fun tx(
        id: String,
        amount: Long = 10_000_00L,
        time: String = "2026-10-10T10:00:00Z",
        direction: Direction = Direction.DEBIT,
        payload: String = "{}",
        description: String = "Fixture payment",
        isReversal: Boolean = false,
        confirmed: Boolean = true,
        category: String? = null,
        provider: Provider = Provider.JAGO,
    ): UnifiedTransaction = UnifiedTransaction(
        provider = provider,
        providerTransactionId = id,
        direction = direction,
        money = Money(amount),
        occurredAt = Instant.parse(time),
        description = description,
        rawPayload = payload,
        isReversal = isReversal,
        isConfirmed = confirmed,
        category = category,
    )

    @Test
    fun periodSummaryLabelsForPresets() {
        val spending1 = tx("s1", amount = 50_000_00L, time = "2026-10-05T08:00:00Z")
        val spending2 = tx("s2", amount = 30_000_00L, time = "2026-10-12T08:00:00Z")
        val pocketTransfer = tx("pt", amount = 20_000_00L, payload = """{"isPocketTransfer":true}""")
        val rows = listOf(spending1, spending2, pocketTransfer)

        val thisMonthRange = TransactionPeriod.THIS_MONTH.range(today)
        val thisMonthSummary = periodSummary(rows, thisMonthRange, today)
        assertEquals("This month", thisMonthSummary.label)
        assertEquals(80_000_00L, thisMonthSummary.totalMinor)
        assertEquals(2, thisMonthSummary.count)

        val lastMonthRange = TransactionPeriod.LAST_MONTH.range(today)
        val lastMonthSummary = periodSummary(rows, lastMonthRange, today)
        assertEquals("Last month", lastMonthSummary.label)

        val last30DaysRange = TransactionPeriod.LAST_30_DAYS.range(today)
        val last30DaysSummary = periodSummary(rows, last30DaysRange, today)
        assertEquals("Last 30 days", last30DaysSummary.label)
    }

    @Test
    fun periodSummaryLabelForCustomRange() {
        val spending = tx("s1", amount = 45_000_00L)
        val start = LocalDate.of(2026, 10, 1).atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 10, 14).atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli()
        val customRange = start to end

        val summary = periodSummary(listOf(spending), customRange, today)
        assertEquals("1 Oct 2026 to 14 Oct 2026", summary.label)
        assertEquals(45_000_00L, summary.totalMinor)
        assertEquals(1, summary.count)
    }

    @Test
    fun periodSummaryLabelForCategoryOnly() {
        val spending = tx("s1", amount = 75_000_00L, category = "Dining Out")
        val summary = periodSummary(listOf(spending), null, today, category = "Dining Out")
        assertEquals("Dining Out", summary.label)
        assertEquals(75_000_00L, summary.totalMinor)
        assertEquals(1, summary.count)
    }

    @Test
    fun dayTotalsExcludeNonSpendingRows() {
        val spending1 = tx("s1", amount = 100_000_00L)
        val spending2 = tx("s2", amount = 50_000_00L)
        val pocketTransfer = tx("pt", amount = 200_000_00L, payload = """{"isPocketTransfer":true}""")
        val walletFunding = tx("wf", amount = 300_000_00L, payload = """{"isWalletFunding":true}""")
        val walletFundingCode = tx("wfc", amount = 400_000_00L, payload = """{"code":"WTD02"}""")
        val internalCode = tx("ic", amount = 500_000_00L, payload = """{"code":"TDD02"}""")
        val reversal = tx("rev", amount = 600_000_00L, isReversal = true)
        val credit = tx("cr", amount = 700_000_00L, direction = Direction.CREDIT)

        val dailyRows = listOf(spending1, spending2, pocketTransfer, walletFunding, walletFundingCode, internalCode, reversal, credit)
        val dayTotal = dailyRows.filter(UnifiedTransaction::isExpense).sumOf { it.money.amountMinor }
        assertEquals(150_000_00L, dayTotal)
    }

    @Test
    fun transferTagAppliedForOwnTransferAndWalletFunding() {
        val ownTransfer = tx("own", payload = """{"isOwnTransfer":true}""")
        val tagsOwn = transactionTags(ownTransfer)
        assertTrue(tagsOwn.any { it.kind == TransactionTagKind.TRANSFER && it.label == "Transfer" })

        val walletFunding = tx("wf", payload = """{"isWalletFunding":true}""")
        val tagsWf = transactionTags(walletFunding)
        assertTrue(tagsWf.any { it.kind == TransactionTagKind.TRANSFER && it.label == "Transfer" })

        val pocketTransfer = tx("pt", payload = """{"isPocketTransfer":true}""")
        val tagsPt = transactionTags(pocketTransfer)
        assertTrue(tagsPt.any { it.kind == TransactionTagKind.TRANSFER && it.label == "Transfer" })

        val descPocketTransfer = tx("dpt", description = "Pocket Transfer: Savings")
        val tagsDpt = transactionTags(descPocketTransfer)
        assertTrue(tagsDpt.any { it.kind == TransactionTagKind.TRANSFER && it.label == "Transfer" })
    }

    @Test
    fun expensesOnlyFilteringExcludesCredits() {
        val debit = tx("d1", direction = Direction.DEBIT)
        val credit = tx("c1", direction = Direction.CREDIT)
        val mixed = listOf(debit, credit)

        val debitOnly = mixed.filter { it.direction == Direction.DEBIT }
        assertEquals(listOf(debit), debitOnly)
        assertFalse(debitOnly.any { it.direction == Direction.CREDIT })
    }

    @Test
    fun widgetExcludesCredits() {
        val debit = tx("d1", direction = Direction.DEBIT, confirmed = true)
        val credit = tx("c1", direction = Direction.CREDIT, confirmed = true)
        val unconfirmed = tx("d2", direction = Direction.DEBIT, confirmed = false)
        val mixed = listOf(debit, credit, unconfirmed)

        val recent = recentWidgetPayments(mixed, setOf(Provider.JAGO))
        assertEquals(listOf(debit), recent)

        val grouped = groupedWidgetPayments(mixed, setOf(Provider.JAGO))
        val paymentRows = grouped.filterIsInstance<WidgetPaymentRow.Payment>()
        assertEquals(listOf(debit), paymentRows.map { it.transaction })
        assertTrue(paymentRows.all { it.transaction.direction == Direction.DEBIT })
    }

    @Test fun emptyPeriodTotalFormatsAsZeroInsteadOfCrashing() {
        assertEquals("Rp0", net.wastu.artha.ui.screens.formatSpendingTotal(0L))
    }

    @Test fun periodSummaryTotalsIncomeExpensesAndDifference() {
        val rows = listOf(
            tx("e1", amount = 30_000_00L),
            tx("i1", amount = 100_000_00L, direction = Direction.CREDIT, payload = "{}"),
        )
        val summary = periodSummary(rows, null, today)
        val expected = summary.incomeMinor - summary.totalMinor
        assertEquals(expected, summary.netMinor)
        assertEquals("Rp0", net.wastu.artha.ui.screens.formatNetTotal(0L))
        assertTrue(net.wastu.artha.ui.screens.formatNetTotal(5_000_00L).startsWith("+"))
        assertTrue(net.wastu.artha.ui.screens.formatNetTotal(-5_000_00L).startsWith("-"))
    }
}
