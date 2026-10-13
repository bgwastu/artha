package net.wastu.artha.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM unified_transactions ORDER BY occurredAtEpochMillis DESC, provider, providerTransactionId")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun append(transactions: List<TransactionEntity>): List<Long>
}
