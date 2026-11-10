package net.wastu.artha.data.ingestion

import java.time.Instant
import kotlinx.coroutines.test.runTest
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.sync.JagoScreenSync.Result
import net.wastu.artha.data.sync.JagoUnlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JagoProviderAdapterTest {
    private val payment = UnifiedTransaction(Provider.JAGO, "fixture", Direction.DEBIT, Money(10000), Instant.EPOCH, "Fictional payment", "{}")

    private class FakeCache(var modifiedAt: Long? = 1_000L, val rows: List<UnifiedTransaction>) : JagoCache {
        var reads = 0
        override fun invalidate() = Unit
        override suspend fun transactions(): List<UnifiedTransaction> { reads++; return rows }
        override suspend fun cacheModifiedAtMillis() = modifiedAt
    }

    private class FakeUnlock(val result: Result) : JagoUnlock {
        val triggers = mutableListOf<String>()
        override suspend fun run(trigger: String): Result { triggers += trigger; return result }
    }

    @Test fun automaticImportsOnlyReadTheCacheAndNeverUnlockJago() = runTest {
        val unlock = FakeUnlock(Result.SYNCED)
        val cache = FakeCache(rows = listOf(payment))
        val adapter = JagoProviderAdapter(cache, unlock)
        assertEquals(listOf(payment), adapter.ingest())
        assertTrue("an automatic import must not launch Bank Jago", unlock.triggers.isEmpty())
    }

    @Test fun aPersonsSyncUnlocksOnceReportsTheResultAndThenReadsTheCache() = runTest {
        val unlock = FakeUnlock(Result.SYNCED)
        val seen = mutableListOf<Result>()
        val adapter = JagoProviderAdapter(FakeCache(rows = listOf(payment)), unlock, seen::add)
        assertEquals(listOf(payment), adapter.ingestInteractive())
        assertEquals(1, unlock.triggers.size)
        assertEquals(listOf(Result.SYNCED), seen)
    }

    @Test fun aFailedUnlockOrJagoLeavingOnItsOwnAsksTheUserToVerifyAndDoesNotRetry() = runTest {
        for (result in listOf(Result.UNLOCK_FAILED, Result.JAGO_EXITED)) {
            val unlock = FakeUnlock(result)
            val cache = FakeCache(rows = listOf(payment))
            val adapter = JagoProviderAdapter(cache, unlock)
            try {
                adapter.ingestInteractive()
                fail("$result should ask the user to verify Bank Jago")
            } catch (e: ProviderReadException) {
                assertEquals(AccountStatus.NEEDS_LOGIN, e.status)
                assertTrue(e.message, "Open Bank Jago" in e.message.orEmpty())
            }
            assertEquals("one attempt, no retry", 1, unlock.triggers.size)
            assertEquals(0, cache.reads)
        }
    }

    @Test fun aWrongPinOrSkippedUnlockStillReadsWhatJagoSaved() = runTest {
        for (result in listOf(Result.WRONG_PIN, Result.SKIPPED_NO_PIN, Result.SKIPPED_SCREEN_OFF, Result.ABORTED, Result.QUIET)) {
            val adapter = JagoProviderAdapter(FakeCache(rows = listOf(payment)), FakeUnlock(result))
            assertEquals(result.name, listOf(payment), adapter.ingestInteractive())
        }
    }

    @Test fun theVerifyRequestStandsUntilBankJagoSavesSomethingNewer() = runTest {
        val cache = FakeCache(modifiedAt = 1_000L, rows = listOf(payment))
        val adapter = JagoProviderAdapter(cache, verificationRequestedAt = { 5_000L })
        try {
            adapter.ingest()
            fail("an unchanged cache must not clear the request to verify Bank Jago")
        } catch (e: ProviderReadException) {
            assertEquals(AccountStatus.NEEDS_LOGIN, e.status)
        }
        assertEquals(0, cache.reads)
        cache.modifiedAt = 6_000L
        assertEquals(listOf(payment), adapter.ingest())
    }

    @Test fun withNothingPendingTheCacheIsReadAsBefore() = runTest {
        val adapter = JagoProviderAdapter(FakeCache(modifiedAt = null, rows = listOf(payment)))
        assertEquals(listOf(payment), adapter.ingest())
    }
}
