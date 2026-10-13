package net.wastu.artha.data.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TransactionEntity::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    companion object {
        fun create(context: Context): LedgerDatabase = Room.databaseBuilder(
            context.applicationContext,
            LedgerDatabase::class.java,
            "artha-ledger.db",
        ).addCallback(AppendOnlyCallback).build()

        val AppendOnlyCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TRIGGER prevent_transaction_update BEFORE UPDATE ON unified_transactions
                    BEGIN SELECT RAISE(ABORT, 'Ledger is append-only'); END
                """.trimIndent())
                db.execSQL("""
                    CREATE TRIGGER prevent_transaction_delete BEFORE DELETE ON unified_transactions
                    BEGIN SELECT RAISE(ABORT, 'Ledger is append-only'); END
                """.trimIndent())
            }
        }
    }
}
