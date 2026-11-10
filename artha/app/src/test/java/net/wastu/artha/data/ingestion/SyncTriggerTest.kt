package net.wastu.artha.data.ingestion

import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only a sync a person asked for may take the heavier path; everything else stays read-only. */
class SyncTriggerTest {
    private val payment = UnifiedTransaction(Provider.JAGO, "fixture", Direction.DEBIT, Money(10000), Instant.EPOCH, "Fictional payment", "{}")

    private class Ledger : LedgerRepository {
        var rows = emptyList<UnifiedTransaction>()
        override fun observeTransactions() = flowOf(rows)
        override suspend fun loadTransactions() = rows
        override suspend fun append(transactions: List<UnifiedTransaction>): AppendResult {
            rows = rows + transactions
            return AppendResult(transactions.size, 0)
        }
    }

    private class Recording(private val rows: List<UnifiedTransaction>) : ProviderAdapter {
        override val provider = Provider.JAGO
        val calls = mutableListOf<String>()
        override suspend fun ingest(): List<UnifiedTransaction> { calls += "automatic"; return rows }
        override suspend fun ingestInteractive(): List<UnifiedTransaction> { calls += "interactive"; return rows }
    }

    private fun coordinator(adapter: ProviderAdapter) = IngestionCoordinator(RootAccess { true }, { Ledger() }, listOf(adapter))

    @Test fun aSyncIsAutomaticUnlessThePersonAskedForIt() = runTest {
        val adapter = Recording(listOf(payment))
        val coordinator = coordinator(adapter)
        coordinator.syncProvider(Provider.JAGO)
        coordinator.syncProvider(Provider.JAGO, interactive = false)
        coordinator.ingestAll()
        coordinator.ingestAll(force = false)
        assertEquals(List(4) { "automatic" }, adapter.calls)
        coordinator.syncProvider(Provider.JAGO, interactive = true)
        assertEquals(List(4) { "automatic" } + "interactive", adapter.calls)
    }
}
