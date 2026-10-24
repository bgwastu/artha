package net.wastu.artha.data.analysis

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test
    fun hasItemDetailsDetection() {
        val withItems = spending(id = "items").copy(rawPayload = """{"items":[{"name":"Coffee","qty":1}]}""")
        val withOrderItems = spending(id = "orderItems").copy(rawPayload = """{"orderItems":[{"name":"Item"}]}""")
        val withProducts = spending(id = "products").copy(rawPayload = """{"products":[{"name":"Item"}]}""")
        val withMenu = spending(id = "menu").copy(rawPayload = """{"menu":[{"name":"Item"}]}""")
        val emptyItems = spending(id = "empty").copy(rawPayload = """{"items":[]}""")
        val noItems = spending(id = "none").copy(rawPayload = """{"description":"Coffee"}""")
        val nestedGrouped = spending(id = "nested").copy(rawPayload = """{"groupedTransactions":[{"rawPayload":"{\"items\":[{\"name\":\"Nested Item\"}]}"}]}""")

        assertTrue(hasItemDetails(withItems))
        assertTrue(hasItemDetails(withOrderItems))
        assertTrue(hasItemDetails(withProducts))
        assertTrue(hasItemDetails(withMenu))
        assertTrue(hasItemDetails(nestedGrouped))
        assertFalse(hasItemDetails(emptyItems))
        assertFalse(hasItemDetails(noItems))
    }

    @Test
    fun needsReviewLogic() {
        val tx = spending("tx_review")
        val txWithItems = spending("tx_with_items").copy(rawPayload = """{"items":[{"name":"Food"}]}""")
        val manualTx = spending("tx_manual", manual = true)

        // Manual never needs review
        assertFalse(needsReview(manualTx, AnalysisSnapshot(aiConfidence = mapOf(manualTx.key to CategoryConfidence.LOW))))
        val manualSnapshot = AnalysisSnapshot(manualCategories = setOf(tx.key), aiConfidence = mapOf(tx.key to CategoryConfidence.LOW))
        assertFalse(needsReview(tx, manualSnapshot))

        // No confidence recorded -> false
        assertFalse(needsReview(tx, AnalysisSnapshot()))

        // LOW confidence -> always true
        assertTrue(needsReview(tx, AnalysisSnapshot(aiConfidence = mapOf(tx.key to CategoryConfidence.LOW))))
        assertTrue(needsReview(txWithItems, AnalysisSnapshot(aiConfidence = mapOf(txWithItems.key to CategoryConfidence.LOW))))

        // HIGH confidence -> always false
        assertFalse(needsReview(tx, AnalysisSnapshot(aiConfidence = mapOf(tx.key to CategoryConfidence.HIGH))))
        assertFalse(needsReview(txWithItems, AnalysisSnapshot(aiConfidence = mapOf(txWithItems.key to CategoryConfidence.HIGH))))

        // MEDIUM confidence without item details -> true
        assertTrue(needsReview(tx, AnalysisSnapshot(aiConfidence = mapOf(tx.key to CategoryConfidence.MEDIUM))))

        // MEDIUM confidence with item details -> false
        assertFalse(needsReview(txWithItems, AnalysisSnapshot(aiConfidence = mapOf(txWithItems.key to CategoryConfidence.MEDIUM))))
    }

    @Test
    fun categorySourcePrecedence() {
        val tx = spending("tx_prec")
        val baseSnapshot = AnalysisSnapshot(
            analysisEnabled = true,
            aiConfig = configuredAi,
            reasons = mapOf(tx.key to "AI suggestion: Groceries"),
            aiConfidence = mapOf(tx.key to CategoryConfidence.HIGH),
        )

        // AI source
        assertEquals(CategorySource.AI, categorySource(tx, baseSnapshot))

        // Merchant source when reason is Same merchant
        val merchantSnapshot = baseSnapshot.copy(reasons = mapOf(tx.key to "Same merchant: Same as earlier Indomaret payments"))
        assertEquals(CategorySource.MERCHANT, categorySource(tx, merchantSnapshot))

        // Review overrides AI and Merchant
        val reviewSnapshot = merchantSnapshot.copy(aiConfidence = mapOf(tx.key to CategoryConfidence.LOW))
        assertEquals(CategorySource.REVIEW, categorySource(tx, reviewSnapshot))

        // Failed overrides Review
        val failedSnapshot = reviewSnapshot.copy(categoryFailures = mapOf(tx.key to "Timeout error"))
        assertEquals(CategorySource.FAILED, categorySource(tx, failedSnapshot))

        // Manual overrides Failed
        val manualSnapshot = failedSnapshot.copy(manualCategories = setOf(tx.key))
        assertEquals(CategorySource.MANUAL, categorySource(tx, manualSnapshot))
    }

    @Test
    fun categoryReasonTextStripsPrefix() {
        val txAi = spending("tx_ai")
        val txMerchant = spending("tx_merchant")
        val txOther = spending("tx_other")
        val snapshot = AnalysisSnapshot(
            reasons = mapOf(
                txAi.key to "AI suggestion: Fresh groceries from Indomaret",
                txMerchant.key to "Same merchant: Same as earlier PLN payments",
                txOther.key to "Some random reason",
            ),
        )

        assertEquals("Fresh groceries from Indomaret", categoryReasonText(snapshot, txAi.key))
        assertEquals("Same as earlier PLN payments", categoryReasonText(snapshot, txMerchant.key))
        assertNull(categoryReasonText(snapshot, txOther.key))

        // Manual category -> null
        val manualSnapshot = snapshot.copy(manualCategories = setOf(txAi.key))
        assertNull(categoryReasonText(manualSnapshot, txAi.key))
    }
}
