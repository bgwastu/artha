package net.wastu.artha.data.analysis

import java.time.Instant
import net.wastu.artha.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TransactionMetadataTest {
    private fun tx(provider: Provider, id: String) = UnifiedTransaction(provider, id, Direction.DEBIT, Money(100), Instant.EPOCH, "Payment", "{}")

    @Test fun exactKeysNeverApplySuffixOrOtherProviderEdits() {
        val a = tx(Provider.JAGO, "12")
        val b = tx(Provider.JAGO, "112")
        val c = tx(Provider.SHOPEE, "12")
        val snapshot = AnalysisSnapshot(userNotes = mapOf(a.key to "Only this one"))
        assertEquals("Only this one", TransactionMetadata.enrich(a, snapshot).userNote)
        assertNull(TransactionMetadata.enrich(b, snapshot).userNote)
        assertNull(TransactionMetadata.enrich(c, snapshot).userNote)
    }

    @Test fun uniqueLegacyNotesMigrateAndAmbiguousEntriesAreRetainedUnassigned() {
        val a = tx(Provider.JAGO, "jago:12")
        val b = tx(Provider.SHOPEE, "shopee:12")
        val c = tx(Provider.JAGO, "jago:112")
        val snapshot = AnalysisSnapshot(userNotes = mapOf("12" to "Ambiguous", "112" to "Unique"), categories = mapOf("112" to "Dining Out"))
        val migrated = TransactionMetadata.migrate(snapshot, listOf(a, b, c))
        assertEquals("Ambiguous", migrated.userNotes["12"])
        assertNull(TransactionMetadata.enrich(a, migrated).userNote)
        assertNull(TransactionMetadata.enrich(b, migrated).userNote)
        assertEquals("Unique", migrated.userNotes[c.key])
        assertTrue(c.key in migrated.manualCategories)
        assertEquals(migrated, TransactionMetadata.migrate(migrated, listOf(a, b, c)))
    }

    @Test fun automaticClassificationPreservesManualChoice() {
        val tx = tx(Provider.JAGO, "12")
        val snapshot = AnalysisSnapshot(categories = mapOf(tx.key to "Groceries"), manualCategories = setOf(tx.key))
        assertEquals(snapshot, TransactionMetadata.applyAutomatic(snapshot, mapOf(tx.key to ("Dining Out" to "AI")), 100))
    }

    @Test fun editableCategoryCannotExcludeAnActualPaymentFromSpending() {
        assertTrue(BudgetAnalyzer.isSpending(tx(Provider.JAGO, "12").copy(category = CategoryConstants.INVESTMENTS_TRANSFERS)))
    }

    @Test fun resetTombstonesBeatOldCloudClassificationsAndTimestampTiesConverge() {
        val deleted = AnalysisSnapshot(updatedAt = mapOf("cat:12" to 20))
        val old = AnalysisSnapshot(categories = mapOf("12" to "Shopping"), updatedAt = mapOf("cat:12" to 10))
        assertTrue(AnalysisSyncMerger.mergeAnalysis(deleted, old).categories.isEmpty())
        val other = old.copy(categories = mapOf("12" to "Dining Out"))
        assertEquals(AnalysisSyncMerger.mergeAnalysis(old, other), AnalysisSyncMerger.mergeAnalysis(other, old))
    }
}
