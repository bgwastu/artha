package net.wastu.artha.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM unified_transactions ORDER BY occurredAtEpochMillis DESC, provider, providerTransactionId")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM unified_transactions ORDER BY occurredAtEpochMillis DESC, provider, providerTransactionId")
    suspend fun loadAll(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun append(transactions: List<TransactionEntity>): List<Long>

    @Query("SELECT * FROM unified_transactions WHERE provider IN (:providers)")
    suspend fun sourceRecords(providers: List<String>): List<TransactionEntity>

    @Transaction
    suspend fun appendProviderImport(transactions: List<TransactionEntity>): AppendResult {
        val providers = transactions.map { it.provider }.distinct().filterNot { it == "JAGO" }
        val ingestedAt = transactions.maxOfOrNull { it.ingestedAtEpochMillis } ?: 0L
        val observations = if (providers.isEmpty()) emptyList() else ProviderObservations.capture(
            sourceRecords(providers).map(TransactionEntity::toDomain),
            transactions.map(TransactionEntity::toDomain),
            ingestedAt,
        )
        val original = append(transactions)
        val inserted = original.count { it != -1L }
        val updates = append(observations.map { TransactionEntity.fromDomain(it, Instant.ofEpochMilli(ingestedAt)) })
            .count { it != -1L }
        return AppendResult(inserted, original.size - inserted, updates)
    }
}
