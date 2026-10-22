package net.wastu.artha.ui

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.ui.screens.formatSavedDay
import net.wastu.artha.ui.screens.sourceRowHint
import net.wastu.artha.ui.screens.sourceStateDescription
import net.wastu.artha.ui.screens.sourceStateLine
import net.wastu.artha.ui.screens.sourceStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesUiStateTest {


    private fun transaction(provider: Provider, daysAgo: Long, id: String): UnifiedTransaction =
        UnifiedTransaction(
            provider = provider, providerTransactionId = "test:$id", direction = Direction.DEBIT,
            money = Money(10_00L), occurredAt = Instant.ofEpochMilli(MS_NOW - daysAgo * DAY_MILLIS),
            description = "Test payment", rawPayload = """{"origin":"test"}""",
        )

    @Test fun statusLabelsCoverEveryState() {
        assertEquals("Syncing", sourceStatusLabel(AccountStatus.CONNECTED, importEnabled = true, importing = true))
        assertEquals("Paused", sourceStatusLabel(AccountStatus.CONNECTED, importEnabled = false, importing = false))
        assertEquals("Connected", sourceStatusLabel(AccountStatus.CONNECTED, importEnabled = true, importing = false))
        assertEquals("Partial", sourceStatusLabel(AccountStatus.PARTIAL, importEnabled = true, importing = false))
        assertEquals("Sign in", sourceStatusLabel(AccountStatus.NEEDS_LOGIN, importEnabled = true, importing = false))
        assertEquals("No app", sourceStatusLabel(AccountStatus.APP_NOT_INSTALLED, importEnabled = true, importing = false))
        assertEquals("Limited", sourceStatusLabel(AccountStatus.RATE_LIMITED, importEnabled = true, importing = false))
        assertEquals("Error", sourceStatusLabel(AccountStatus.ERROR, importEnabled = true, importing = false))
        assertEquals("New", sourceStatusLabel(AccountStatus.UNKNOWN, importEnabled = true, importing = false))
    }

    @Test fun problemStatesCarryRecoverySteps() {
        assertTrue(sourceStateDescription(AccountStatus.NEEDS_LOGIN, hasSavedRows = true).lowercase().contains("sign in"))
        assertTrue(sourceStateDescription(AccountStatus.ERROR, hasSavedRows = false).contains("failed"))
        assertTrue(sourceStateDescription(AccountStatus.RATE_LIMITED, hasSavedRows = true).contains("limiting"))
        assertTrue(sourceStateDescription(AccountStatus.CONNECTED, hasSavedRows = true).contains("healthy"))
        assertTrue(sourceStateDescription(AccountStatus.UNKNOWN, hasSavedRows = false).contains("No history"))
    }

    @Test fun rowLinePrefersDataFreshnessOverCounts() {
        val latest = Instant.ofEpochMilli(MS_NOW - 5 * DAY_MILLIS)
        val coverage = SourceCoverage(
            earliest = Instant.ofEpochMilli(MS_NOW - 200 * DAY_MILLIS),
            latest = latest,
        )
        val line = sourceStateLine(
            diagnostic = ProviderDiagnostic(lastSyncEpochMillis = MS_NOW),
            coverage = coverage, count = 41, importEnabled = true, importing = false,
        )
        assertEquals("Payments to ${formatSavedDay(latest)}, synced just now", line)
    }

    @Test fun rowLineWithoutAnyDataFallsBackToNotImported() {
        assertEquals("Not imported yet", sourceStateLine(null, null, 41, importEnabled = true, importing = false))
    }

    @Test fun rowLineKeepsPausedAndSyncingStatesFirst() {
        assertEquals(
            "Import paused",
            sourceStateLine(null, null, 0, importEnabled = false, importing = false).substringBefore(", "),
        )
        assertTrue(sourceStateLine(null, null, 0, importEnabled = true, importing = true).startsWith("Checking"))
    }


    @Test fun sourceCoverageSpansResolvedRowsByProvider() {
        val rows = listOf(
            transaction(Provider.GOJEK, daysAgo = 3, id = "g1"),
            transaction(Provider.GOJEK, daysAgo = 90, id = "g2"),
            transaction(Provider.JAGO, daysAgo = 1, id = "j1"),
        )
        val coverage = sourceCoverage(rows)
        assertEquals(2, coverage.size)
        assertEquals(MS_NOW - 90 * DAY_MILLIS, coverage.getValue(Provider.GOJEK).earliest!!.toEpochMilli())
        assertEquals(MS_NOW - 3 * DAY_MILLIS, coverage.getValue(Provider.GOJEK).latest!!.toEpochMilli())
        assertEquals(MS_NOW - 1 * DAY_MILLIS, coverage.getValue(Provider.JAGO).latest!!.toEpochMilli())
    }

    @Test fun hintsNeverFireOnHealthySources() {
        assertNull(sourceRowHint(ProviderDiagnostic(accountStatus = AccountStatus.CONNECTED)))
        assertNull(sourceRowHint(null))
        assertEquals("Open the source app, sign in, then import again",
            sourceRowHint(ProviderDiagnostic(accountStatus = AccountStatus.NEEDS_LOGIN)))
        assertEquals("Unable to read Gojek data",
            sourceRowHint(ProviderDiagnostic(accountStatus = AccountStatus.ERROR,
                lastError = "Unable to read Gojek data. Valid payments are retained")))
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val MS_NOW = 1_800_000_000_000L
    }
}
