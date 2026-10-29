package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSyncMergerTest {

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

        val merged = AnalysisSyncMerger.mergeAnalysis(local, remote)
        assertEquals("Dining Out", merged.categories["tx-1"])
        assertEquals(200L, merged.updatedAt["cat:tx-1"])
    }

    @Test
    fun categoryTombstonePreventsReappearanceOfDeletedCategories() {
        val deviceA = AnalysisSnapshot(
            customCategories = listOf("Groceries", "Dining Out"),
            deletedCategories = mapOf("Crypto" to 250L),
            updatedAt = mapOf("customCat:Crypto" to 100L),
        )
        val deviceB = AnalysisSnapshot(
            customCategories = listOf("Groceries", "Crypto"),
            updatedAt = mapOf("customCat:Crypto" to 100L),
        )

        val merged = AnalysisSyncMerger.mergeAnalysis(deviceA, deviceB)
        assertFalse("Deleted category should not be resurrected", merged.customCategories.contains("Crypto"))
        assertTrue(merged.customCategories.contains("Groceries"))
    }

    @Test
    fun freshInstallAbsorbsPopulatedRemoteSnapshot() {
        val freshInstall = AnalysisSnapshot()
        val remote = AnalysisSnapshot(
            categories = mapOf("tx-1" to "Dining Out"),
            customCategories = listOf("Tech", "Fitness"),
            updatedAt = mapOf("cat:tx-1" to 500L, "customCat:Tech" to 500L),
        )

        val merged = AnalysisSyncMerger.mergeAnalysis(freshInstall, remote)
        assertEquals("Dining Out", merged.categories["tx-1"])
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

        val toAppend = AnalysisSyncMerger.findMissingTransactionsToAppend(
            localTransactions = listOf(existingTx),
            remoteTransactions = listOf(existingTx, newTx),
        )
        assertEquals(1, toAppend.size)
        assertEquals("shopee:2", toAppend[0].providerTransactionId)
    }
}
