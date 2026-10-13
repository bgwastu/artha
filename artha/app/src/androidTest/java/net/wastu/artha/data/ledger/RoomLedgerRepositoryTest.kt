package net.wastu.artha.data.ledger

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLedgerRepositoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private val transaction = UnifiedTransaction(
        Provider.JAGO, "provider-id-1", Direction.DEBIT, Money(25_000_000),
        Instant.parse("2026-01-01T00:00:00Z"), "Test payment", "{\"original\":true}",
        Pocket("main", "Main Pocket"),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java)
            .addCallback(LedgerDatabase.AppendOnlyCallback).build()
        ledger = RoomLedgerRepository(database.transactions())
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun duplicateIngestionPreservesTheOriginalTransactionAndPayload() = runBlocking {
        assertEquals(AppendResult(1, 0), ledger.append(listOf(transaction)))
        assertEquals(AppendResult(0, 1), ledger.append(listOf(transaction.copy(description = "Changed", rawPayload = "{}"))))
        assertEquals(listOf(transaction), ledger.observeTransactions().first())
    }

    @Test
    fun providerIdsAreNamespacedAndLedgerIsChronological() = runBlocking {
        val newer = transaction.copy(provider = Provider.SHOPEE, occurredAt = transaction.occurredAt.plusSeconds(10))
        assertEquals(AppendResult(2, 0), ledger.append(listOf(transaction, newer)))
        assertEquals(listOf(newer, transaction), ledger.observeTransactions().first())
    }

    @Test
    fun invalidPayloadRejectsTheEntireBatch() = runBlocking {
        try {
            ledger.append(listOf(transaction, transaction.copy(providerTransactionId = "invalid", rawPayload = "not JSON")))
            fail("Expected invalid JSON to be rejected")
        } catch (_: JSONException) {
            assertEquals(emptyList<UnifiedTransaction>(), ledger.observeTransactions().first())
        }
    }

    @Test
    fun sqliteRejectsUpdatesAndDeletes() = runBlocking {
        ledger.append(listOf(transaction))
        listOf("UPDATE unified_transactions SET description = 'Changed'", "DELETE FROM unified_transactions").forEach { sql ->
            try {
                database.openHelper.writableDatabase.execSQL(sql)
                fail("Ledger mutation should be rejected")
            } catch (_: SQLiteException) {
                assertEquals(listOf(transaction), ledger.observeTransactions().first())
            }
        }
    }

    @Test
    fun versionOneHistorySurvivesTheIndexedMigration() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ledger-migration-test.db"
        context.deleteDatabase(name)
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                old.execSQL("CREATE TABLE unified_transactions (provider TEXT NOT NULL, providerTransactionId TEXT NOT NULL, direction TEXT NOT NULL, amountMinor INTEGER NOT NULL, currencyCode TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, description TEXT NOT NULL, rawPayload TEXT NOT NULL, pocketId TEXT, pocketName TEXT, ingestedAtEpochMillis INTEGER NOT NULL, PRIMARY KEY(provider, providerTransactionId))")
                old.execSQL("INSERT INTO unified_transactions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>("JAGO", "provider-id-1", "DEBIT", 25000000, "IDR", transaction.occurredAt.toEpochMilli(), "Test payment", "{\"original\":true}", "main", "Main Pocket", 1))
                old.version = 1
            }
            val migrated = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
                .addMigrations(LedgerDatabase.Migration1To2).addCallback(LedgerDatabase.AppendOnlyCallback).build()
            try {
                assertEquals(listOf(transaction), RoomLedgerRepository(migrated.transactions()).observeTransactions().first())
                try {
                    migrated.openHelper.writableDatabase.execSQL("DELETE FROM unified_transactions")
                    fail("Migration must retain append-only protection")
                } catch (_: SQLiteException) { }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun transactionsSurviveClosingAndReopeningTheLedger() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ledger-persistence-test.db"
        context.deleteDatabase(name)
        try {
            val persistent = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addCallback(LedgerDatabase.AppendOnlyCallback).build()
            try {
                RoomLedgerRepository(persistent.transactions()).append(listOf(transaction))
            } finally {
                persistent.close()
            }
            val reopened = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addCallback(LedgerDatabase.AppendOnlyCallback).build()
            try {
                assertEquals(listOf(transaction), RoomLedgerRepository(reopened.transactions()).observeTransactions().first())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
