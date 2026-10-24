package net.wastu.artha.data.analysis

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySourceResolverTest {
    private val configuredAi = AiConfig(baseUrl = "https://example.com/v1", apiKey = "key", model = "gpt-4o")

    private fun spending(
        id: String = "tx1",
        manual: Boolean = false,
        aiReasoning: String? = null,
    ) = UnifiedTransaction(
        provider = Provider.JAGO,
        providerTransactionId = id,
        direction = Direction.DEBIT,
        money = Money(50_000),
        occurredAt = Instant.EPOCH,
        description = "Coffee shop",
        rawPayload = "{}",
        classificationIsManual = manual,
        aiReasoning = aiReasoning,
    )

    private fun income(id: String = "tx2") = UnifiedTransaction(
        provider = Provider.JAGO,
        providerTransactionId = id,
        direction = Direction.CREDIT,
        money = Money(100_000),
        occurredAt = Instant.EPOCH,
        description = "Salary",
        rawPayload = "{}",
    )

    @Test
    fun manualSourceWhenFlagSetOrKeyInSnapshot() {
        val txDirectManual = spending(manual = true)
        val snapshot = AnalysisSnapshot(analysisEnabled = true, aiConfig = configuredAi)
        assertEquals(CategorySource.MANUAL, categorySource(txDirectManual, snapshot))

        val txSnapshotManual = spending("manualKey")
        val snapshotWithKey = snapshot.copy(manualCategories = setOf(txSnapshotManual.key))
        assertEquals(CategorySource.MANUAL, categorySource(txSnapshotManual, snapshotWithKey))
    }

    @Test
    fun aiSourceWhenReasonPrefixed() {
        val tx = spending("tx_ai")
        val snapshot = AnalysisSnapshot(
            analysisEnabled = true,
            aiConfig = configuredAi,
            reasons = mapOf(tx.key to "AI suggestion: Morning espresso run"),
        )
        assertEquals(CategorySource.AI, categorySource(tx, snapshot))
        assertEquals("Morning espresso run", aiCategoryReason(snapshot, tx.key))

        // Precedence: manual overrides AI reason
        val manualTx = spending("tx_ai", manual = true)
        assertEquals(CategorySource.MANUAL, categorySource(manualTx, snapshot))

        val manualSnapshot = snapshot.copy(manualCategories = setOf(tx.key))
        assertEquals(CategorySource.MANUAL, categorySource(tx, manualSnapshot))
        assertNull(aiCategoryReason(manualSnapshot, tx.key))
    }

    @Test
    fun pendingSourceWhenSpendingAnalysisEnabledAndConfigured() {
        val tx = spending("tx_pending")
        val snapshot = AnalysisSnapshot(analysisEnabled = true, aiConfig = configuredAi)
        assertEquals(CategorySource.PENDING, categorySource(tx, snapshot))
    }

    @Test
    fun noneSourceWhenNotPendingNorAiNorManual() {
        val tx = spending("tx_none")

        // Analysis disabled
        val disabledSnapshot = AnalysisSnapshot(analysisEnabled = false, aiConfig = configuredAi)
        assertEquals(CategorySource.NONE, categorySource(tx, disabledSnapshot))

        // AI unconfigured (missing apiKey)
        val unconfiguredSnapshot = AnalysisSnapshot(
            analysisEnabled = true,
            aiConfig = configuredAi.copy(apiKey = ""),
        )
        assertEquals(CategorySource.NONE, categorySource(tx, unconfiguredSnapshot))

        // Non-spending (income) even if analysis enabled and AI configured
        val incomeTx = income()
        assertEquals(CategorySource.NONE, categorySource(incomeTx, disabledSnapshot.copy(analysisEnabled = true)))

        // Non-AI reason without configuration
        val snapshotWithOtherReason = AnalysisSnapshot(
            analysisEnabled = false,
            reasons = mapOf(tx.key to "Rule matched"),
        )
        assertEquals(CategorySource.NONE, categorySource(tx, snapshotWithOtherReason))
        assertNull(aiCategoryReason(snapshotWithOtherReason, tx.key))
    }
}
