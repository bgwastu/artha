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
        val snapshot = AnalysisSnapshot(aiConfig = configuredAi)
        assertEquals(CategorySource.MANUAL, categorySource(txDirectManual, snapshot))

        val txSnapshotManual = spending("manualKey")
        val snapshotWithKey = snapshot.copy(manualCategories = setOf(txSnapshotManual.key))
        assertEquals(CategorySource.MANUAL, categorySource(txSnapshotManual, snapshotWithKey))
    }

    @Test
    fun aiSourceWhenReasonPrefixed() {
        val tx = spending("tx_ai")
        val snapshot = AnalysisSnapshot(
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
        val snapshot = AnalysisSnapshot(aiConfig = configuredAi)
        assertEquals(CategorySource.PENDING, categorySource(tx, snapshot))
    }

    @Test
    fun noneSourceWhenNotPendingNorAiNorManual() {
        val tx = spending("tx_none")

        val disabledSnapshot = AnalysisSnapshot(aiConfig = configuredAi)

        // AI unconfigured (missing apiKey)
        val unconfiguredSnapshot = AnalysisSnapshot(
            aiConfig = configuredAi.copy(apiKey = ""),
        )
        assertEquals(CategorySource.NONE, categorySource(tx, unconfiguredSnapshot))

        // Non-spending (income) even if analysis enabled and AI configured
        val incomeTx = income()
        assertEquals(CategorySource.NONE, categorySource(incomeTx, disabledSnapshot))

        // Non-AI reason without configuration
        val snapshotWithOtherReason = AnalysisSnapshot(
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
        assertFalse(needsReview(manualTx, AnalysisSnapshot(aiRecords = aiRecords(confidence = mapOf(manualTx.key to CategoryConfidence.LOW)))))
        val manualSnapshot = AnalysisSnapshot(manualCategories = setOf(tx.key), aiRecords = aiRecords(confidence = mapOf(tx.key to CategoryConfidence.LOW)))
        assertFalse(needsReview(tx, manualSnapshot))

        // No confidence recorded -> false
        assertFalse(needsReview(tx, AnalysisSnapshot()))

        // LOW confidence -> always true
        assertTrue(needsReview(tx, AnalysisSnapshot(aiRecords = aiRecords(confidence = mapOf(tx.key to CategoryConfidence.LOW)))))
        assertTrue(needsReview(txWithItems, AnalysisSnapshot(aiRecords = aiRecords(confidence = mapOf(txWithItems.key to CategoryConfidence.LOW)))))

        // HIGH confidence -> always false
        assertFalse(needsReview(tx, AnalysisSnapshot(aiRecords = aiRecords(confidence = mapOf(tx.key to CategoryConfidence.HIGH)))))
        assertFalse(needsReview(txWithItems, AnalysisSnapshot(aiRecords = aiRecords(confidence = mapOf(txWithItems.key to CategoryConfidence.HIGH)))))

        // MEDIUM (above 90%) is trusted whether or not the payment has item details
        assertFalse(needsReview(tx, AnalysisSnapshot(aiRecords = aiRecords(confidence = mapOf(tx.key to CategoryConfidence.MEDIUM)))))
        assertFalse(needsReview(txWithItems, AnalysisSnapshot(aiRecords = aiRecords(confidence = mapOf(txWithItems.key to CategoryConfidence.MEDIUM)))))
    }

    @Test
    fun categorySourcePrecedence() {
        val tx = spending("tx_prec")
        val baseSnapshot = AnalysisSnapshot(
            aiConfig = configuredAi,
            reasons = mapOf(tx.key to "AI suggestion: Groceries"),
            aiRecords = aiRecords(confidence = mapOf(tx.key to CategoryConfidence.HIGH)),
        )

        // AI source
        assertEquals(CategorySource.AI, categorySource(tx, baseSnapshot))

        // Merchant source when reason is Same merchant
        val merchantSnapshot = baseSnapshot.copy(reasons = mapOf(tx.key to "Same merchant: Same as earlier Indomaret payments"))
        assertEquals(CategorySource.MERCHANT, categorySource(tx, merchantSnapshot))

        // Review overrides AI and Merchant
        val reviewSnapshot = merchantSnapshot.copy(aiRecords = aiRecords(confidence = mapOf(tx.key to CategoryConfidence.LOW)))
        assertEquals(CategorySource.REVIEW, categorySource(tx, reviewSnapshot))

        // Failed overrides Review
        val failedSnapshot = reviewSnapshot.copy(aiRecords = aiRecords(failures = mapOf(tx.key to "Timeout error")))
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

    @Test
    fun categoryStatusOrdersFailureReviewStaleAndUncategorizedAnswers() {
        val tx = spending("tx_status")
        fun status(snapshot: AnalysisSnapshot, fp: String = "fp") = categoryStatus(tx, snapshot) { fp }
        val labeled = AnalysisSnapshot(
            categories = mapOf(tx.key to "Food & Drinks"),
            reasons = mapOf(tx.key to "AI suggestion: Coffee"),
            aiRecords = mapOf(tx.key to AiRecord("fp", 95, null, false)),
        )
        assertEquals(CategoryStatus.NEW, status(AnalysisSnapshot()))
        assertEquals(CategoryStatus.AI, status(labeled))
        assertEquals(CategoryStatus.STALE, status(labeled, fp = "changed"))
        assertEquals(CategoryStatus.REVIEW, status(labeled.copy(aiRecords = mapOf(tx.key to AiRecord("fp", 90, null, false)))))
        assertEquals(CategoryStatus.AI, status(labeled.copy(aiRecords = mapOf(tx.key to AiRecord("fp", 91, null, false)))))
        assertEquals(CategoryStatus.FAILED, status(labeled.copy(aiRecords = mapOf(tx.key to AiRecord("fp", 90, "Timed out", false)))))
        assertEquals(CategoryStatus.MANUAL, status(labeled.copy(manualCategories = setOf(tx.key))))
        assertEquals(CategoryStatus.NOT_SPENDING, categoryStatus(income(), labeled) { "fp" })
    }

    @Test
    fun allPendingScopeCoversEveryMonthButSkipsLabeledAndManualPayments() {
        val old = spending("old").copy(occurredAt = Instant.parse("2024-01-10T03:00:00Z"))
        val recent = spending("recent").copy(occurredAt = Instant.parse("2026-10-10T03:00:00Z"))
        val labeled = spending("labeled")
        val manual = spending("manual")
        val snapshot = AnalysisSnapshot(
            categories = mapOf(labeled.key to "Food & Drinks", manual.key to "Shopping"),
            reasons = mapOf(labeled.key to "AI suggestion: Coffee"),
            manualCategories = setOf(manual.key),
            aiRecords = mapOf(labeled.key to AiRecord(AiCategorization.fingerprint(labeled, AnalysisSnapshot()), 90, null, false)),
        )
        val keys = AiCategorization.scopeKeys(RecategorizeScope.AllPending, listOf(old, recent, labeled, manual), snapshot)
        assertEquals(setOf(old.key, recent.key), keys)
    }
}
