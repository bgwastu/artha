package net.wastu.artha.data.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TransactionEntity::class], version = 2, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    companion object {
        fun create(context: Context): LedgerDatabase = Room.databaseBuilder(
            context.applicationContext,
            LedgerDatabase::class.java,
            "artha-ledger.db",
        ).addMigrations(Migration1To2).addCallback(AppendOnlyCallback).build()

        val Migration1To2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_unified_transactions_occurredAtEpochMillis_provider_providerTransactionId ON unified_transactions (occurredAtEpochMillis DESC, provider ASC, providerTransactionId ASC)")
            }
        }

        val AppendOnlyCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) { protect(db) }
            override fun onOpen(db: SupportSQLiteDatabase) { protect(db) }
            private fun protect(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS prevent_transaction_update BEFORE UPDATE ON unified_transactions
                    BEGIN SELECT RAISE(ABORT, 'Ledger is append-only'); END
                """.trimIndent())
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS prevent_transaction_delete BEFORE DELETE ON unified_transactions
                    BEGIN SELECT RAISE(ABORT, 'Ledger is append-only'); END
                """.trimIndent())
            }
        }
    }
}
