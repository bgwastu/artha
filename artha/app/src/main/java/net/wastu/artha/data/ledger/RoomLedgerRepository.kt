package net.wastu.artha.data.ledger

import net.wastu.artha.core.model.UnifiedTransaction
import java.time.Clock
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class RoomLedgerRepository(
    private val dao: TransactionDao,
    private val clock: Clock = Clock.systemUTC(),
) : LedgerRepository {
    override fun observeTransactions() = dao.observeTransactions().map { transactions ->
        transactions.map(TransactionEntity::toDomain)
    }

    override suspend fun loadTransactions(): List<UnifiedTransaction> =
        dao.loadAll().map(TransactionEntity::toDomain)

    override suspend fun append(transactions: List<UnifiedTransaction>): AppendResult {
        // Parse before inserting the batch so malformed payloads cannot be partially appended.
        transactions.forEach { JSONObject(it.rawPayload) }
        val ingestedAt = clock.instant()
        val results = dao.append(transactions.map { TransactionEntity.fromDomain(it, ingestedAt) })
        val inserted = results.count { it != -1L }
        return AppendResult(inserted = inserted, duplicates = results.size - inserted)
    }

    override suspend fun appendProviderImport(transactions: List<UnifiedTransaction>): AppendResult {
        transactions.forEach { JSONObject(it.rawPayload) }
        return dao.appendProviderImport(transactions.map { TransactionEntity.fromDomain(it, clock.instant()) })
    }
}
