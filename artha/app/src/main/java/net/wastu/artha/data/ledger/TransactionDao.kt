package net.wastu.artha.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM unified_transactions ORDER BY occurredAtEpochMillis DESC, provider, providerTransactionId")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM unified_transactions ORDER BY occurredAtEpochMillis DESC, provider, providerTransactionId")
    suspend fun loadAll(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun append(transactions: List<TransactionEntity>): List<Long>

    @Query("SELECT * FROM unified_transactions WHERE provider != 'JAGO'")
    suspend fun sourceRecords(): List<TransactionEntity>

    @Transaction
    suspend fun appendProviderImport(transactions: List<TransactionEntity>): AppendResult {
        val observations = if (transactions.any { it.provider != "JAGO" }) ProviderObservations.capture(
            sourceRecords().map(TransactionEntity::toDomain), transactions.map(TransactionEntity::toDomain),
            transactions.maxOfOrNull { it.ingestedAtEpochMillis } ?: 0L) else emptyList()
        val original = append(transactions)
        val inserted = original.count { it != -1L }
        val updates = append(observations.map { TransactionEntity.fromDomain(it, java.time.Instant.ofEpochMilli(transactions.maxOf { row -> row.ingestedAtEpochMillis })) })
            .count { it != -1L }
        return AppendResult(inserted, original.size - inserted, updates)
    }
}
