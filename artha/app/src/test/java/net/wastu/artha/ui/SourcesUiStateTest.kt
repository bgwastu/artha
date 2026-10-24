package net.wastu.artha.ui

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.ui.screens.formatRelativeTime
import net.wastu.artha.ui.screens.formatSavedDay
import net.wastu.artha.ui.screens.isSourceProblem
import net.wastu.artha.ui.screens.sourceHeadline
import net.wastu.artha.ui.screens.sourceRowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesUiStateTest {

    private fun transaction(provider: Provider, daysAgo: Long, id: String): UnifiedTransaction =
        UnifiedTransaction(
            provider = provider,
            providerTransactionId = "test:$id",
            direction = Direction.DEBIT,
            money = Money(10_00L),
            occurredAt = Instant.ofEpochMilli(MS_NOW - daysAgo * DAY_MILLIS),
            description = "Test payment",
            rawPayload = """{"origin":"test"}""",
        )

    @Test
    fun statusLabelsCoverEveryState() {
        assertEquals("Syncing", sourceRowStatus(Provider.GOJEK, null, importEnabled = true, importing = true, now = MS_NOW))
        assertEquals("Paused", sourceRowStatus(Provider.GOJEK, null, importEnabled = false, importing = false, now = MS_NOW))
        assertEquals(
            "Sign in to Bank Jago",
            sourceRowStatus(Provider.JAGO, ProviderDiagnostic(accountStatus = AccountStatus.NEEDS_LOGIN), importEnabled = true, importing = false, now = MS_NOW),
        )
        assertEquals(
            "Shopee app not installed",
            sourceRowStatus(Provider.SHOPEE, ProviderDiagnostic(accountStatus = AccountStatus.APP_NOT_INSTALLED), importEnabled = true, importing = false, now = MS_NOW),
        )
        assertEquals(
            "Limited by Grab. Try later",
            sourceRowStatus(Provider.GRAB, ProviderDiagnostic(accountStatus = AccountStatus.RATE_LIMITED), importEnabled = true, importing = false, now = MS_NOW),
        )
        assertEquals(
            "Last sync failed",
            sourceRowStatus(Provider.GOJEK, ProviderDiagnostic(accountStatus = AccountStatus.ERROR), importEnabled = true, importing = false, now = MS_NOW),
        )
    }

    @Test
    fun problemStatesCarryRecoverySteps() {
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.NEEDS_LOGIN), importEnabled = true, importing = false))
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.ERROR), importEnabled = true, importing = false))
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.RATE_LIMITED), importEnabled = true, importing = false))
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.APP_NOT_INSTALLED), importEnabled = true, importing = false))
        assertFalse(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.CONNECTED), importEnabled = true, importing = false))
        assertFalse(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.UNKNOWN), importEnabled = true, importing = false))
    }

    @Test
    fun rowLinePrefersDataFreshnessOverCounts() {
        val latest = Instant.ofEpochMilli(MS_NOW - 5 * DAY_MILLIS)
        assertEquals("5 days ago", formatRelativeTime(latest.toEpochMilli(), MS_NOW))
        assertEquals("10 Jan 2027", formatSavedDay(latest))
    }

    @Test
    fun rowLineWithoutAnyDataFallsBackToNotImported() {
        assertEquals("Not synced yet", sourceRowStatus(Provider.GOJEK, null, importEnabled = true, importing = false, now = MS_NOW))
    }

    @Test
    fun rowLineKeepsPausedAndSyncingStatesFirst() {
        assertEquals(
            "Paused",
            sourceRowStatus(Provider.GOJEK, null, importEnabled = false, importing = false, now = MS_NOW),
        )
        assertEquals(
            "Syncing",
            sourceRowStatus(Provider.GOJEK, null, importEnabled = true, importing = true, now = MS_NOW),
        )
    }

    @Test
    fun sourceCoverageSpansResolvedRowsByProvider() {
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

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val MS_NOW = 1_800_000_000_000L
    }
}
