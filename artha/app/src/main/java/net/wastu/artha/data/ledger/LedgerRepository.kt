package net.wastu.artha.data.ledger

import net.wastu.artha.core.model.UnifiedTransaction
import kotlinx.coroutines.flow.Flow

data class AppendResult(val inserted: Int, val duplicates: Int)

/** Append-only: existing Unified Transactions are never replaced or deleted. */
interface LedgerRepository {
    fun observeTransactions(): Flow<List<UnifiedTransaction>>
    suspend fun append(transactions: List<UnifiedTransaction>): AppendResult
}
