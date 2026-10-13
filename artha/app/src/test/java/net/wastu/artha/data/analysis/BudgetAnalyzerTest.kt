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

    @Test
    fun fingerprintCalculationIsDeterministicAndDetectsChanges() {
        val tx1 = UnifiedTransaction(
            provider = Provider.GOJEK,
            providerTransactionId = "gojek:1",
            direction = Direction.DEBIT,
            money = Money(50_000_00L),
            occurredAt = Instant.parse("2026-10-10T12:00:00Z"),
            description = "GoFood",
            rawPayload = "{}",
            category = "Dining Out",
        )
        val tx2 = UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:1",
            direction = Direction.DEBIT,
            money = Money(25_000_00L),
            occurredAt = Instant.parse("2026-10-12T12:00:00Z"),
            description = "ShopeeFood",
            rawPayload = "{}",
        )

        val fpInitial = BudgetAnalyzer.computeMonthFingerprint(
            monthTransactions = listOf(tx1),
            monthlyNote = "Team lunch",
            knowledge = "Knowledge base v1",
            customCategories = listOf("Dining Out", "Groceries"),
        )

        val fpIdentical = BudgetAnalyzer.computeMonthFingerprint(
            monthTransactions = listOf(tx1),
            monthlyNote = "Team lunch",
            knowledge = "Knowledge base v1",
            customCategories = listOf("Dining Out", "Groceries"),
        )
        assertEquals("Identical inputs must yield identical fingerprints", fpInitial, fpIdentical)

        // 1. Changing transactions changes fingerprint
        val fpNewTx = BudgetAnalyzer.computeMonthFingerprint(
            monthTransactions = listOf(tx1, tx2),
            monthlyNote = "Team lunch",
            knowledge = "Knowledge base v1",
            customCategories = listOf("Dining Out", "Groceries"),
        )
        org.junit.Assert.assertNotEquals(fpInitial, fpNewTx)

        // 2. Modifying transaction user note changes fingerprint
        val fpModifiedNote = BudgetAnalyzer.computeMonthFingerprint(
            monthTransactions = listOf(tx1.copy(userNote = "Client lunch")),
            monthlyNote = "Team lunch",
            knowledge = "Knowledge base v1",
            customCategories = listOf("Dining Out", "Groceries"),
        )
        org.junit.Assert.assertNotEquals(fpInitial, fpModifiedNote)

        // 3. Modifying monthly note changes fingerprint
        val fpMonthlyNote = BudgetAnalyzer.computeMonthFingerprint(
            monthTransactions = listOf(tx1),
            monthlyNote = "Updated monthly note",
            knowledge = "Knowledge base v1",
            customCategories = listOf("Dining Out", "Groceries"),
        )
        org.junit.Assert.assertNotEquals(fpInitial, fpMonthlyNote)

        // 4. Modifying knowledge changes fingerprint
        val fpKnowledge = BudgetAnalyzer.computeMonthFingerprint(
            monthTransactions = listOf(tx1),
            monthlyNote = "Team lunch",
            knowledge = "Knowledge base v2 with new rules",
            customCategories = listOf("Dining Out", "Groceries"),
        )
        org.junit.Assert.assertNotEquals(fpInitial, fpKnowledge)

        // 5. Modifying custom categories changes fingerprint
        val fpCats = BudgetAnalyzer.computeMonthFingerprint(
            monthTransactions = listOf(tx1),
            monthlyNote = "Team lunch",
            knowledge = "Knowledge base v1",
            customCategories = listOf("Dining Out", "Groceries", "Entertainment"),
        )
        org.junit.Assert.assertNotEquals(fpInitial, fpCats)
    }
}
