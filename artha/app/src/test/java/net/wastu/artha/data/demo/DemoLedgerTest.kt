package net.wastu.artha.data.demo

import java.time.Instant
import java.time.ZoneId
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoLedgerTest {
    private val now = Instant.parse("2026-10-29T05:00:00Z")
    private val zone = ZoneId.of("Asia/Jakarta")

    @Test fun isDeterministicAndHasNoFutureRows() {
        val a = DemoLedger.generate(now, zone)
        val b = DemoLedger.generate(now, zone)
        assertEquals(a.transactions.map { it.key to it.money }, b.transactions.map { it.key to it.money })
        assertTrue(a.transactions.none { it.occurredAt.isAfter(now) })
        assertEquals(a.transactions.size, a.transactions.map { it.key }.toSet().size)
    }

    @Test fun spansAYearWithEveryMonthPopulated() {
        val rows = DemoLedger.generate(now, zone).transactions
        assertTrue("only ${rows.size} rows", rows.size > 900)
        val months = rows.map { java.time.YearMonth.from(it.occurredAt.atZone(zone)) }.toSet()
        assertTrue(months.size >= 12)
    }

    @Test fun everySourceHasSubstantialActivity() {
        // Only rows the reconciler confirms are shown, so measure after reconciling.
        val perProvider = net.wastu.artha.data.reconciliation.LedgerReconciler
            .reconcileLedger(DemoLedger.generate(now, zone).transactions).filter { it.isConfirmed }.groupingBy { it.provider }.eachCount()
        val total = perProvider.values.sum().toDouble()
        net.wastu.artha.core.model.Provider.entries.forEach {
            val share = (perProvider[it] ?: 0) / total
            assertTrue("$it share $share of $perProvider", share in 0.15..0.40)
        }
    }

    @Test fun coversExpensesIncomeAndTransfers() {
        val types = DemoLedger.generate(now, zone).transactions.map { it.type }.toSet()
        assertEquals(TransactionType.entries.toSet(), types)
    }

    @Test fun everyOlderExpenseAndIncomeRowIsLabelled() {
        val data = DemoLedger.generate(now, zone)
        val labelled = data.labels.map { it.key }.toSet()
        val unlabelled = data.transactions.filter { it.type != TransactionType.TRANSFER && it.key !in labelled && it.occurredAt.isBefore(now.minusSeconds(30 * 3600)) }
        assertTrue(unlabelled.toString(), unlabelled.isEmpty())
        assertTrue(data.transactions.any { it.direction == Direction.CREDIT && it.category == "Salary" })
    }
}
