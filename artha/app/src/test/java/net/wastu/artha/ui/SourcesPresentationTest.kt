package net.wastu.artha.ui

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.ui.screens.countExpenses
import net.wastu.artha.ui.screens.formatRelativeTime
import net.wastu.artha.ui.screens.isSourceProblem
import net.wastu.artha.ui.screens.lastSyncText
import net.wastu.artha.ui.screens.sourceHeadline
import net.wastu.artha.ui.screens.sourceRowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesPresentationTest {

    private val now = 1_800_000_000_000L

    @Test
    fun sourceRowStatusCoversEveryState() {
        // 1. importing -> "Syncing"
        assertEquals(
            "Syncing",
            sourceRowStatus(Provider.GOJEK, null, importEnabled = true, importing = true, now = now)
        )
        // 2. import paused -> "Paused"
        assertEquals(
            "Paused",
            sourceRowStatus(Provider.GOJEK, null, importEnabled = false, importing = false, now = now)
        )
        // 3. NEEDS_LOGIN -> "Sign in to <name>"
        assertEquals(
            "Sign in to Bank Jago",
            sourceRowStatus(
                Provider.JAGO,
                ProviderDiagnostic(accountStatus = AccountStatus.NEEDS_LOGIN),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        // 4. APP_NOT_INSTALLED -> "<name> app not installed"
        assertEquals(
            "Shopee app not installed",
            sourceRowStatus(
                Provider.SHOPEE,
                ProviderDiagnostic(accountStatus = AccountStatus.APP_NOT_INSTALLED),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        // 5. RATE_LIMITED -> "Limited by <name>. Try later"
        assertEquals(
            "Limited by Grab. Try later",
            sourceRowStatus(
                Provider.GRAB,
                ProviderDiagnostic(accountStatus = AccountStatus.RATE_LIMITED),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        // 6. ERROR -> "Last sync failed"
        assertEquals(
            "Last sync failed",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.ERROR, lastError = "Network error"),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        // 7. Otherwise: lastSync within 15 min -> "Up to date"
        assertEquals(
            "Up to date",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(
                    accountStatus = AccountStatus.CONNECTED,
                    lastSyncEpochMillis = now - 5 * 60 * 1000L,
                ),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        assertEquals(
            "Up to date",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(
                    accountStatus = AccountStatus.CONNECTED,
                    lastSyncEpochMillis = now - 14 * 60 * 1000L,
                ),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        // 8. Otherwise: lastSync >= 15 min -> "Synced <relative>"
        assertEquals(
            "Synced 20 min ago",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(
                    accountStatus = AccountStatus.CONNECTED,
                    lastSyncEpochMillis = now - 20 * 60 * 1000L,
                ),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        assertEquals(
            "Synced 2 h ago",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(
                    accountStatus = AccountStatus.CONNECTED,
                    lastSyncEpochMillis = now - 2 * 3600 * 1000L,
                ),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        assertEquals(
            "Synced yesterday",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(
                    accountStatus = AccountStatus.CONNECTED,
                    lastSyncEpochMillis = now - 24 * 3600 * 1000L,
                ),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        assertEquals(
            "Synced 3 days ago",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(
                    accountStatus = AccountStatus.CONNECTED,
                    lastSyncEpochMillis = now - 3 * 24 * 3600 * 1000L,
                ),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        // 9. Otherwise: never synced -> "Not synced yet"
        assertEquals(
            "Not synced yet",
            sourceRowStatus(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.CONNECTED, lastSyncEpochMillis = null),
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
        assertEquals(
            "Not synced yet",
            sourceRowStatus(
                Provider.GOJEK,
                null,
                importEnabled = true,
                importing = false,
                now = now,
            )
        )
    }

    @Test
    fun sourceHeadlineCoversEveryState() {
        assertEquals("Syncing", sourceHeadline(Provider.GOJEK, null, importEnabled = true, importing = true))
        assertEquals("Paused", sourceHeadline(Provider.GOJEK, null, importEnabled = false, importing = false))
        assertEquals(
            "Sign in to Gojek",
            sourceHeadline(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.NEEDS_LOGIN),
                importEnabled = true,
                importing = false,
            )
        )
        assertEquals(
            "Gojek app not installed",
            sourceHeadline(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.APP_NOT_INSTALLED),
                importEnabled = true,
                importing = false,
            )
        )
        assertEquals(
            "Limited by Gojek",
            sourceHeadline(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.RATE_LIMITED),
                importEnabled = true,
                importing = false,
            )
        )
        assertEquals(
            "Last sync failed",
            sourceHeadline(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.ERROR),
                importEnabled = true,
                importing = false,
            )
        )
        assertEquals(
            "Up to date",
            sourceHeadline(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.CONNECTED),
                importEnabled = true,
                importing = false,
            )
        )
        assertEquals(
            "Up to date",
            sourceHeadline(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.PARTIAL),
                importEnabled = true,
                importing = false,
            )
        )
        assertEquals(
            "Up to date",
            sourceHeadline(
                Provider.GOJEK,
                ProviderDiagnostic(accountStatus = AccountStatus.UNKNOWN),
                importEnabled = true,
                importing = false,
            )
        )
        assertEquals(
            "Up to date",
            sourceHeadline(
                Provider.GOJEK,
                null,
                importEnabled = true,
                importing = false,
            )
        )
    }

    @Test
    fun relativeTimeWordingMatchesSpec() {
        assertEquals("just now", formatRelativeTime(now, now))
        assertEquals("just now", formatRelativeTime(now - 30_000L, now))
        assertEquals("just now", formatRelativeTime(now - 59_000L, now))
        assertEquals("1 min ago", formatRelativeTime(now - 60_000L, now))
        assertEquals("15 min ago", formatRelativeTime(now - 15 * 60_000L, now))
        assertEquals("59 min ago", formatRelativeTime(now - 59 * 60_000L, now))
        assertEquals("1 h ago", formatRelativeTime(now - 60 * 60_000L, now))
        assertEquals("2 h ago", formatRelativeTime(now - 2 * 3600_000L, now))
        assertEquals("23 h ago", formatRelativeTime(now - 23 * 3600_000L, now))
        assertEquals("yesterday", formatRelativeTime(now - 24 * 3600_000L, now))
        assertEquals("yesterday", formatRelativeTime(now - 47 * 3600_000L, now))
        assertEquals("2 days ago", formatRelativeTime(now - 48 * 3600_000L, now))
        assertEquals("5 days ago", formatRelativeTime(now - 5 * 24 * 3600_000L, now))
    }

    @Test
    fun lastSyncTextParsing() {
        // 0 added -> "No new payments"
        assertEquals("No new payments", lastSyncText("0 added, 1543 already saved"))
        assertEquals("No new payments", lastSyncText("0 added"))

        // 1 added -> "1 new payment"
        assertEquals("1 new payment", lastSyncText("1 added, 0 already saved"))
        assertEquals("1 new payment", lastSyncText("1 added"))

        // many added -> "N new payments"
        assertEquals("12 new payments", lastSyncText("12 added, 1543 already saved"))
        assertEquals("2 new payments", lastSyncText("2 added. Full food listing blocked"))
        assertEquals("100 new payments", lastSyncText("100 added"))

        // garbage, blank, or unparseable -> null
        assertNull(lastSyncText("garbage"))
        assertNull(lastSyncText("payments saved successfully"))
        assertNull(lastSyncText(""))
        assertNull(lastSyncText("   "))
        assertNull(lastSyncText(null))
    }

    @Test
    fun expenseCountExcludesCredits() {
        val transactions = listOf(
            UnifiedTransaction(
                provider = Provider.GOJEK,
                providerTransactionId = "g1",
                direction = Direction.DEBIT,
                money = Money(10_00L),
                occurredAt = Instant.ofEpochMilli(now),
                description = "Gojek ride",
                rawPayload = "{}",
            ),
            UnifiedTransaction(
                provider = Provider.GOJEK,
                providerTransactionId = "g2",
                direction = Direction.CREDIT,
                money = Money(25_00L),
                occurredAt = Instant.ofEpochMilli(now),
                description = "Gojek refund",
                rawPayload = "{}",
            ),
            UnifiedTransaction(
                provider = Provider.GOJEK,
                providerTransactionId = "g3",
                direction = Direction.DEBIT,
                money = Money(15_00L),
                occurredAt = Instant.ofEpochMilli(now),
                description = "Gofood lunch",
                rawPayload = "{}",
            ),
            UnifiedTransaction(
                provider = Provider.JAGO,
                providerTransactionId = "j1",
                direction = Direction.DEBIT,
                money = Money(50_00L),
                occurredAt = Instant.ofEpochMilli(now),
                description = "Jago transfer",
                rawPayload = "{}",
            ),
        )

        assertEquals(2, countExpenses(transactions, Provider.GOJEK))
        assertEquals(1, countExpenses(transactions, Provider.JAGO))
        assertEquals(0, countExpenses(transactions, Provider.SHOPEE))
        assertEquals(0, countExpenses(transactions, Provider.GRAB))
    }

    @Test
    fun problemStateIdentification() {
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.NEEDS_LOGIN), importEnabled = true, importing = false))
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.APP_NOT_INSTALLED), importEnabled = true, importing = false))
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.RATE_LIMITED), importEnabled = true, importing = false))
        assertTrue(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.ERROR), importEnabled = true, importing = false))

        // When importing, problem state is false
        assertFalse(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.ERROR), importEnabled = true, importing = true))

        // When paused (importEnabled = false), problem state is false
        assertFalse(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.ERROR), importEnabled = false, importing = false))

        // Healthy states are not problems
        assertFalse(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.CONNECTED), importEnabled = true, importing = false))
        assertFalse(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.PARTIAL), importEnabled = true, importing = false))
        assertFalse(isSourceProblem(ProviderDiagnostic(accountStatus = AccountStatus.UNKNOWN), importEnabled = true, importing = false))
        assertFalse(isSourceProblem(null, importEnabled = true, importing = false))
    }
}
