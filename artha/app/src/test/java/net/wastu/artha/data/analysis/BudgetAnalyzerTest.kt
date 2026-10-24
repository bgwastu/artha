package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.Instant
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetAnalyzerTest {

    @Test
    fun pocketTransfersAndTermDepositsAreExcludedFromSpending() {
        val termDeposit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:td-1",
            direction = Direction.DEBIT,
            money = Money(58_000_000_00L),
            occurredAt = Instant.parse("2026-09-20T06:31:00Z"),
            description = "Term Deposit",
            rawPayload = """{"code":"TDD02"}""",
        )
        val pocketTransfer = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:pt-1",
            direction = Direction.DEBIT,
            money = Money(5_000_000_00L),
            occurredAt = Instant.parse("2026-09-20T06:31:00Z"),
            description = "Pocket Transfer: Business Pocket to Main Pocket",
            rawPayload = """{"isPocketTransfer":true}""",
        )

        assertFalse(BudgetAnalyzer.isSpending(termDeposit))
        assertFalse(BudgetAnalyzer.isSpending(pocketTransfer))
    }

    @Test
    fun reversalsAreExcludedFromSpending() {
        val reversed = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:rev-1",
            direction = Direction.DEBIT,
            money = Money(10_000_00L),
            occurredAt = Instant.parse("2026-10-09T05:54:31Z"),
            description = "LinkedIn Premium (Reversed)",
            rawPayload = """{"isReversal":true}""",
        )
        assertFalse(BudgetAnalyzer.isSpending(reversed))
    }

    @Test
    fun categoryTotalsAndDeltasAreCalculatedAccurately() {
        val sepDining = UnifiedTransaction(
            provider = Provider.GOJEK,
            providerTransactionId = "gojek:sep-1",
            direction = Direction.DEBIT,
            money = Money(100_000_00L),
            occurredAt = Instant.parse("2026-09-15T12:00:00Z"),
            description = "GoFood",
            rawPayload = "{}",
            category = "Dining Out",
        )
        val octDining = UnifiedTransaction(
            provider = Provider.GOJEK,
            providerTransactionId = "gojek:oct-1",
            direction = Direction.DEBIT,
            money = Money(50_000_00L),
            occurredAt = Instant.parse("2026-10-10T12:00:00Z"),
            description = "GoFood",
            rawPayload = "{}",
            category = "Dining Out",
        )

        val result = BudgetAnalyzer.analyzeMonth(
            month = YearMonth.of(2026, 10),
            allTransactions = listOf(sepDining, octDining),
        )

        assertEquals(50_000_00L, result.totalPersonalSpendingMinor)
        assertEquals(100_000_00L, result.previousMonthTotalMinor)
        assertEquals(-50.0f, result.totalDeltaPercent!!, 0.1f)
        assertEquals(1, result.categories.size)
        assertEquals("Dining Out", result.categories.first().category)
        assertEquals(50_000_00L, result.categories.first().amountMinor)
    }
}
