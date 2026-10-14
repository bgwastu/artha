package net.wastu.artha

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.wastu.artha.core.model.Direction
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.ui.LedgerState
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class DemoModeTest {
    @Test fun fictionalDatasetHasRequestedScaleIncomeAndReversal() {
        val rows = samplePayments()
        assertTrue(rows.all { it.providerTransactionId.startsWith("demo-") && org.json.JSONObject(it.rawPayload).getBoolean("demo") })
        val analysis = BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 10), rows)
        assertTrue(analysis.totalPersonalSpendingMinor in 400_000_000L..500_000_000L)
        assertTrue(rows.any { it.direction == Direction.CREDIT })
        assertTrue(rows.any { it.isReversal && !BudgetAnalyzer.isSpending(it) })
    }

    @Test fun multiYearLedgerIsLongStableAndKeepsTransfersOutOfSpending() {
        val rows = samplePayments()
        assertTrue(rows.size > 2700)
        assertEquals(rows, samplePayments())
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(rows, rows.sortedByDescending { it.occurredAt })
        val months = rows.map { YearMonth.from(it.occurredAt.atZone(net.wastu.artha.core.model.AccountingTime.zone)) }.toSet()
        assertEquals(46, months.size)
        assertEquals(YearMonth.of(2023, 1), months.minOrNull())
        assertEquals(YearMonth.of(2026, 10), months.maxOrNull())
        assertTrue(rows.any { it.occurredAt.atZone(net.wastu.artha.core.model.AccountingTime.zone).toLocalDate() == java.time.LocalDate.of(2024, 2, 29) })
        assertTrue(rows.filter { it.description.startsWith("Pocket Transfer:") || it.description == "GoPay top-up" }
            .all { !BudgetAnalyzer.isSpending(it) })
        assertEquals(433500000L, BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 10), rows).totalPersonalSpendingMinor)
    }

    @Test fun demoFlagIsSeparateAndFailedExitDoesNotRevealPersonalMode() {
        var enabled = false
        var staged = false
        var commitSucceeds = true
        val editor = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { assertEquals("enabled", args!![0]); staged = args[1] as Boolean; proxy }
                "commit" -> { if (commitSucceeds) enabled = staged; commitSucceeds }
                else -> error("Unexpected demo preference edit: ${method.name}")
            }
        } as android.content.SharedPreferences.Editor
        val preferences = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) { "getBoolean" -> enabled; "edit" -> editor; else -> error("Unexpected personal preference read") }
        } as android.content.SharedPreferences
        val context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                assertEquals("artha_demo_mode", name)
                return preferences
            }
        }
        assertFalse(DemoMode.isEnabled(context))
        assertTrue(DemoMode.setEnabled(context, true))
        assertTrue(DemoMode.isEnabled(context))
        commitSucceeds = false
        assertFalse(DemoMode.setEnabled(context, false))
        assertTrue(DemoMode.isEnabled(context))
        commitSucceeds = true
        assertTrue(DemoMode.setEnabled(context, false))
        assertFalse(DemoMode.isEnabled(context))
    }

    @Test fun previewLoadsWithoutStorageAndHasSavedCommentaryButNoLiveServices() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = demoModel(StandardTestDispatcher(testScheduler))
            store.put("demo", model)
            model.retryLedger()
            advanceUntilIdle()
            assertTrue(model.ledgerState.value is LedgerState.Ready)
            assertEquals(samplePayments(), model.allTransactions.value)
            assertFalse(model.analysisSnapshot.value.monthlySummaries["2026-10:3m"].isNullOrBlank())
            assertNull(model.analysisStore)
            assertNull(model.driveSyncManager)
            assertEquals("", model.analysisSnapshot.value.aiConfig.apiKey)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
