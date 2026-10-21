package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionCoordinatorTest {
    private var opened = false
    private var extracted = false
    private var appended = emptyList<UnifiedTransaction>()
    private val ledger = object : LedgerRepository {
        override fun observeTransactions() = flowOf(appended)
        override suspend fun loadTransactions() = appended
        override suspend fun append(transactions: List<UnifiedTransaction>): AppendResult {
            appended = transactions
            return AppendResult(transactions.size, 0)
        }
    }

    @Test
    fun deniedRootStopsBeforeExtractionAndLedgerAccess() = runTest {
        val coordinator = coordinator(granted = false)
        try {
            coordinator.ingest(Provider.JAGO)
            error("Expected the Root Gate to reject ingestion")
        } catch (_: IllegalStateException) {
            assertFalse(opened)
            assertFalse(extracted)
        }
    }

    @Test
    fun verifiedAdapterAppendsItsNormalizedTransactions() = runTest {
        val result = coordinator(granted = true).ingest(Provider.JAGO)
        assertTrue(extracted)
        assertTrue(opened)
        assertEquals(AppendResult(1, 0), result)
        assertEquals("original", appended.single().providerTransactionId)
    }

    @Test
    fun adapterCannotAppendAnotherProvidersTransactions() = runTest {
        try {
            coordinator(granted = true, returnedProvider = Provider.GRAB).ingest(Provider.JAGO)
            error("Expected Provider mismatch to be rejected")
        } catch (_: IllegalArgumentException) {
            assertTrue(extracted)
            assertFalse(opened)
        }
    }

    @Test
    fun unsupportedProviderDoesNotPretendToIngest() = runTest {
        val coordinator = IngestionCoordinator(RootAccess { true }, { opened = true; ledger })
        try {
            coordinator.ingest(Provider.GRAB)
            error("Expected an unimplemented adapter to be rejected")
        } catch (_: IllegalArgumentException) {
            assertFalse(opened)
        }
    }

    private fun coordinator(granted: Boolean, returnedProvider: Provider = Provider.JAGO): IngestionCoordinator {
        val adapter = object : ProviderAdapter {
            override val provider = Provider.JAGO
            override suspend fun ingest(): List<UnifiedTransaction> {
                extracted = true
                return listOf(UnifiedTransaction(returnedProvider, "original", Direction.DEBIT, Money(100), Instant.EPOCH, "Example", "{}"))
            }
        }
        return IngestionCoordinator(RootAccess { granted }, { opened = true; ledger }, listOf(adapter))
    }
}
