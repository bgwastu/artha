package net.wastu.artha.data.providers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ingestion.*
import net.wastu.artha.data.ledger.*
import org.junit.Assert.*
import org.junit.Test

class ProviderDiagnosticsTest {
    private val payment = UnifiedTransaction(Provider.JAGO, "fixture", Direction.DEBIT,
        Money(10000), Instant.EPOCH, "Fictional payment", "{}")

    @Test fun successfulCacheImportIsNotAnErrorAndCoverageSurvivesReopening() = runTest {
        val fixture = PreferencesFixture()
        val preferences = ProviderPreferences(fixture.context)
        val (coordinator, ledger) = coordinator(preferences) { listOf(payment) }
        coordinator.syncProvider(Provider.JAGO)
        val diagnostic = ProviderPreferences(fixture.context).diagnostics.value.getValue(Provider.JAGO)
        assertEquals(listOf(payment), ledger.rows)
        assertEquals(AccountStatus.CONNECTED, diagnostic.accountStatus)
        assertNull(diagnostic.lastError)
        assertEquals(Provider.JAGO.historyCoverageHint, diagnostic.coverageHint)
        assertEquals("1 added, 0 already saved", diagnostic.lastSyncSummary)
    }

    @Test fun actualPartialImportRetainsValidPaymentsAndItsFailureReason() = runTest {
        val fixture = PreferencesFixture()
        val preferences = ProviderPreferences(fixture.context)
        val (coordinator, ledger) = coordinator(preferences) {
            throw PartialImportException(listOf(payment), "Older page unavailable")
        }
        coordinator.syncProvider(Provider.JAGO)
        val diagnostic = ProviderPreferences(fixture.context).diagnostics.value.getValue(Provider.JAGO)
        assertEquals(listOf(payment), ledger.rows)
        assertEquals(AccountStatus.PARTIAL, diagnostic.accountStatus)
        assertEquals("Older page unavailable", diagnostic.lastError)
        assertEquals(Provider.JAGO.historyCoverageHint, diagnostic.coverageHint)
    }

    @Test fun failedRefreshKeepsPriorSuccessAndDoesNotAppendOrBecomeConnected() = runTest {
        val fixture = PreferencesFixture()
        val preferences = ProviderPreferences(fixture.context)
        preferences.recordSyncSuccess(Provider.JAGO, "1 added", coverageHint = Provider.JAGO.historyCoverageHint)
        val previousTime = preferences.diagnostics.value.getValue(Provider.JAGO).lastSyncEpochMillis
        val (coordinator, ledger) = coordinator(preferences) {
            throw ProviderReadException(AccountStatus.NEEDS_LOGIN, "Sign in required")
        }
        coordinator.syncProvider(Provider.JAGO)
        val diagnostic = ProviderPreferences(fixture.context).diagnostics.value.getValue(Provider.JAGO)
        assertTrue(ledger.rows.isEmpty())
        assertEquals(AccountStatus.NEEDS_LOGIN, diagnostic.accountStatus)
        assertEquals(previousTime, diagnostic.lastSyncEpochMillis)
        assertEquals("1 added", diagnostic.lastSyncSummary)
        assertEquals("Sign in required", diagnostic.lastError)
        assertEquals(Provider.JAGO.historyCoverageHint, diagnostic.coverageHint)
    }

    @Test fun legacyCapabilityOnlyMovesOutOfErrorButActualPartialAndErrorsStay() {
        val fixture = PreferencesFixture()
        fun legacy(provider: Provider, error: String, status: AccountStatus = AccountStatus.PARTIAL) {
            fixture.values["${provider.name}_last_sync_ms"] = 1L
            fixture.values["${provider.name}_status"] = status.name
            fixture.values["${provider.name}_last_error"] = error
            fixture.values["${provider.name}_last_summary"] = "2 added. $error"
        }
        legacy(Provider.JAGO, Provider.JAGO.legacyCoverageHint)
        legacy(Provider.SHOPEE, "${Provider.SHOPEE.legacyCoverageHint}. Full food listing blocked")
        legacy(Provider.GRAB, Provider.GRAB.legacyCoverageHint, AccountStatus.ERROR)
        val diagnostics = ProviderPreferences(fixture.context).diagnostics.value
        assertEquals(AccountStatus.CONNECTED, diagnostics.getValue(Provider.JAGO).accountStatus)
        assertNull(diagnostics.getValue(Provider.JAGO).lastError)
        assertEquals("2 added", diagnostics.getValue(Provider.JAGO).lastSyncSummary)
        assertEquals(Provider.JAGO.historyCoverageHint, diagnostics.getValue(Provider.JAGO).coverageHint)
        assertEquals(AccountStatus.PARTIAL, diagnostics.getValue(Provider.SHOPEE).accountStatus)
        assertEquals("Full food listing blocked", diagnostics.getValue(Provider.SHOPEE).lastError)
        assertEquals("2 added. Full food listing blocked", diagnostics.getValue(Provider.SHOPEE).lastSyncSummary)
        assertEquals(AccountStatus.ERROR, diagnostics.getValue(Provider.GRAB).accountStatus)
        assertEquals(Provider.GRAB.legacyCoverageHint, diagnostics.getValue(Provider.GRAB).lastError)
    }

    private fun coordinator(preferences: ProviderPreferences, read: suspend () -> List<UnifiedTransaction>): Pair<IngestionCoordinator, FixtureLedger> {
        val ledger = FixtureLedger()
        val adapter = object : ProviderAdapter {
            override val provider = Provider.JAGO
            override val coverageHint = Provider.JAGO.historyCoverageHint
            override suspend fun ingest() = read()
        }
        return IngestionCoordinator(RootAccess { true }, { ledger }, listOf(adapter), preferences = preferences) to ledger
    }

    private class FixtureLedger : LedgerRepository {
        var rows = emptyList<UnifiedTransaction>()
        override fun observeTransactions() = flowOf(rows)
        override suspend fun append(transactions: List<UnifiedTransaction>): AppendResult {
            rows = rows + transactions
            return AppendResult(transactions.size, 0)
        }
    }

    private class PreferencesFixture {
        val values = mutableMapOf<String, Any>()
        private val preferences = proxy<SharedPreferences> { name, args -> when (name) {
            "getString", "getLong", "getBoolean" -> values[args[0]] ?: args[1]
            "edit" -> editor()
            else -> error("Unexpected preference call: $name")
        } }
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy { name, args -> when (name) {
                "putString", "putLong", "putBoolean" -> { changes[args[0] as String] = args[1]; editor }
                "remove" -> { changes[args[0] as String] = null; editor }
                "apply" -> { changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; null }
                else -> error("Unexpected editor call: $name")
            } }
            return editor
        }
        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args.orEmpty())
            } as T
    }
}
