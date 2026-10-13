package net.wastu.artha.data.sync

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    @Test
    fun bundleSerializationAndDeserializationRoundTrip() {
        val tx = UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:order-999",
            direction = Direction.DEBIT,
            money = Money(7650000, "IDR"),
            occurredAt = Instant.parse("2026-10-09T08:59:29Z"),
            description = "Fried Chicken Combo",
            rawPayload = """{"items":[{"name":"Chicken Combo","qty":4}]}""",
            cardLast4 = "1234",
        )
        val snapshot = AnalysisSnapshot(
            categories = mapOf("order-999" to "Dining Out"),
            userNotes = mapOf("order-999" to "Dinner with family"),
            monthlyNotes = mapOf("2026-10" to "Jogja vacation"),
            customCategories = listOf("Dining Out", "Travel"),
            categoryIcons = mapOf("Dining Out" to "restaurant"),
            updatedAt = mapOf("cat:order-999" to 1000L),
        )
        val originalBundle = SyncBundle(
            version = 1,
            exportedAtEpochMillis = 5000L,
            analysis = snapshot,
            transactions = listOf(tx),
        )

        val bytes = SyncBundleSerializer.toJsonBytes(originalBundle)
        val parsed = SyncBundleSerializer.fromJsonBytes(bytes)

        assertEquals(1, parsed.version)
        assertEquals(5000L, parsed.exportedAtEpochMillis)
        assertEquals(1, parsed.transactions.size)
        assertEquals("shopee:order-999", parsed.transactions[0].providerTransactionId)
        assertEquals(7650000L, parsed.transactions[0].money.amountMinor)
        assertEquals("Dining Out", parsed.analysis.categories["order-999"])
        assertEquals("Jogja vacation", parsed.analysis.monthlyNotes["2026-10"])
        assertEquals("restaurant", parsed.analysis.categoryIcons["Dining Out"])
    }

    @Test
    fun newerRemoteCategoryOverridesOlderLocalCategory() {
        val local = AnalysisSnapshot(
            categories = mapOf("tx-1" to "Groceries"),
            updatedAt = mapOf("cat:tx-1" to 100L),
        )
        val remote = AnalysisSnapshot(
            categories = mapOf("tx-1" to "Dining Out"),
            updatedAt = mapOf("cat:tx-1" to 200L),
        )

        val merged = SyncMerger.mergeAnalysis(local, remote)
        assertEquals("Dining Out", merged.categories["tx-1"])
        assertEquals(200L, merged.updatedAt["cat:tx-1"])
    }

    @Test
    fun categoryTombstonePreventsReappearanceOfDeletedCategories() {
        // Device A deleted "Crypto" at time 250
        val deviceA = AnalysisSnapshot(
            customCategories = listOf("Groceries", "Dining Out"),
            deletedCategories = mapOf("Crypto" to 250L),
            updatedAt = mapOf("customCat:Crypto" to 100L),
        )
        // Device B still had "Crypto" added at time 100
        val deviceB = AnalysisSnapshot(
            customCategories = listOf("Groceries", "Crypto"),
            updatedAt = mapOf("customCat:Crypto" to 100L),
        )

        val merged = SyncMerger.mergeAnalysis(deviceA, deviceB)
        assertFalse("Deleted category should not be resurrected", merged.customCategories.contains("Crypto"))
        assertTrue(merged.customCategories.contains("Groceries"))
    }

    @Test
    fun freshInstallAbsorbsPopulatedRemoteSnapshot() {
        val freshInstall = AnalysisSnapshot() // empty local state
        val remote = AnalysisSnapshot(
            categories = mapOf("tx-1" to "Dining Out"),
            monthlyNotes = mapOf("2026-10" to "Vacation"),
            customCategories = listOf("Tech", "Fitness"),
            updatedAt = mapOf("cat:tx-1" to 500L, "monthNote:2026-10" to 500L, "customCat:Tech" to 500L),
        )

        val merged = SyncMerger.mergeAnalysis(freshInstall, remote)
        assertEquals("Dining Out", merged.categories["tx-1"])
        assertEquals("Vacation", merged.monthlyNotes["2026-10"])
        assertTrue(merged.customCategories.contains("Tech"))
    }

    @Test
    fun findMissingTransactionsToAppendReturnsOnlyNewTransactions() {
        val existingTx = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:1",
            direction = Direction.DEBIT,
            money = Money(100),
            occurredAt = Instant.EPOCH,
            description = "Existing",
            rawPayload = "{}",
        )
        val newTx = UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:2",
            direction = Direction.DEBIT,
            money = Money(200),
            occurredAt = Instant.EPOCH,
            description = "New",
            rawPayload = "{}",
        )

        val toAppend = SyncMerger.findMissingTransactionsToAppend(
            localTransactions = listOf(existingTx),
            remoteTransactions = listOf(existingTx, newTx),
        )
        assertEquals(1, toAppend.size)
        assertEquals("shopee:2", toAppend[0].providerTransactionId)
    }
}
