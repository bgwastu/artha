package net.wastu.artha.ui

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AI_REASON_PREFIX
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.ui.screens.CategoryReviewLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CategoryReviewLogicTest {

    private fun sampleTx(
        id: String,
        description: String = "Test tx",
        payload: String = "{}",
        category: String? = null,
        manual: Boolean = false,
        aiReasoning: String? = null,
    ) = UnifiedTransaction(
        provider = Provider.JAGO,
        providerTransactionId = id,
        direction = Direction.DEBIT,
        money = Money(25000),
        occurredAt = Instant.parse("2026-03-01T10:00:00Z"),
        description = description,
        rawPayload = payload,
        category = category,
        classificationIsManual = manual,
        aiReasoning = aiReasoning,
        isConfirmed = true,
    )

    @Test
    fun transactionMerchantExtraction() {
        // From merchantName
        val tx1 = sampleTx("1", payload = """{"merchantName":"Starbucks"}""")
        assertEquals("Starbucks", CategoryReviewLogic.transactionMerchant(tx1))

        // From shopName
        val tx2 = sampleTx("2", payload = """{"shopName":"Tokopedia Store"}""")
        assertEquals("Tokopedia Store", CategoryReviewLogic.transactionMerchant(tx2))

        // From restaurantName
        val tx3 = sampleTx("3", payload = """{"restaurantName":"Warung Padang"}""")
        assertEquals("Warung Padang", CategoryReviewLogic.transactionMerchant(tx3))

        // From counterpart
        val tx4 = sampleTx("4", payload = """{"counterpart":"Budi"}""")
        assertEquals("Budi", CategoryReviewLogic.transactionMerchant(tx4))

        // Fallback to description
        val tx5 = sampleTx("5", description = "Grocery Store Payment")
        assertEquals("Grocery Store Payment", CategoryReviewLogic.transactionMerchant(tx5))
    }

    @Test
    fun canConfirmAllRules() {
        val tx1 = sampleTx("p1")
        val tx2 = sampleTx("p2")
        val snapshot = AnalysisSnapshot(
            categories = mapOf(tx1.key to "Groceries", tx2.key to "Groceries"),
        )

        // All share "Groceries"
        val (canConfirm, category) = CategoryReviewLogic.canConfirmAll(listOf(tx1, tx2), snapshot)
        assertTrue(canConfirm)
        assertEquals("Groceries", category)

        // Multiple different categories
        val mixedSnapshot = AnalysisSnapshot(
            categories = mapOf(tx1.key to "Groceries", tx2.key to "Dining Out"),
        )
        val (canConfirmMixed, categoryMixed) = CategoryReviewLogic.canConfirmAll(listOf(tx1, tx2), mixedSnapshot)
        assertFalse(canConfirmMixed)
        assertNull(categoryMixed)

        // Uncategorized does not allow confirm all
        val uncatSnapshot = AnalysisSnapshot(
            categories = mapOf(tx1.key to "Uncategorized", tx2.key to "Uncategorized"),
        )
        val (canConfirmUncat, categoryUncat) = CategoryReviewLogic.canConfirmAll(listOf(tx1, tx2), uncatSnapshot)
        assertFalse(canConfirmUncat)
        assertNull(categoryUncat)

        // Empty list
        val (canConfirmEmpty, categoryEmpty) = CategoryReviewLogic.canConfirmAll(emptyList(), snapshot)
        assertFalse(canConfirmEmpty)
        assertNull(categoryEmpty)
    }

    @Test
    fun suggestedAlternativesPreferences() {
        val txPeer1 = sampleTx("p1", payload = """{"merchantName":"Kopi Kenangan"}""")
        val txPeer2 = sampleTx("p2", payload = """{"merchantName":"Kopi Kenangan"}""")
        val txPeer3 = sampleTx("p3", payload = """{"merchantName":"Kopi Kenangan"}""")
        val allTx = listOf(txPeer1, txPeer2, txPeer3)

        val categoriesMap = mapOf(
            txPeer1.key to "Coffee",
            txPeer2.key to "Coffee",
            txPeer3.key to "Dining Out",
        )

        val topCategories = listOf("Dining Out", "Groceries", "Transport", "Tech & Subscriptions")

        // Current category is "Dining Out" -> should suggest "Coffee", then "Groceries"
        val suggestions = CategoryReviewLogic.suggestedAlternatives(
            currentCategory = "Dining Out",
            merchant = "Kopi Kenangan",
            allTransactions = allTx,
            categoriesMap = categoriesMap,
            topCategories = topCategories,
            limit = 2,
        )

        assertEquals(listOf("Coffee", "Groceries"), suggestions)
    }

    @Test
    fun displayReasonExtraction() {
        val tx1 = sampleTx("p1")
        val failedSnapshot = AnalysisSnapshot(
            categoryFailures = mapOf(tx1.key to "Could not determine vendor"),
            manualCategories = setOf(),
            analysisEnabled = true,
            aiConfig = AiConfig(baseUrl = "https://ai.example.com", model = "test", apiKey = "key"),
        )
        assertEquals("Could not determine vendor", CategoryReviewLogic.displayReason(tx1, failedSnapshot))

        val tx2 = sampleTx("p2", aiReasoning = "${AI_REASON_PREFIX}Coffee shop detected")
        val reviewSnapshot = AnalysisSnapshot(
            reasons = mapOf(tx2.key to "${AI_REASON_PREFIX}Coffee shop detected"),
            manualCategories = setOf(),
            analysisEnabled = true,
            aiConfig = AiConfig(baseUrl = "https://ai.example.com", model = "test", apiKey = "key"),
        )
        assertEquals("Coffee shop detected", CategoryReviewLogic.displayReason(tx2, reviewSnapshot))
    }

    @Test
    fun groupByMerchant() {
        val tx1 = sampleTx("1", payload = """{"merchantName":"Merchant A"}""")
        val tx2 = sampleTx("2", payload = """{"merchantName":"Merchant B"}""")
        val tx3 = sampleTx("3", payload = """{"merchantName":"Merchant A"}""")

        val grouped = CategoryReviewLogic.groupByMerchant(listOf(tx1, tx2, tx3))
        assertEquals(2, grouped.size)
        assertEquals(listOf(tx1, tx3), grouped["Merchant A"])
        assertEquals(listOf(tx2), grouped["Merchant B"])
    }

    @Test
    fun filterReviewPaymentsExcludesConfirmedKeys() {
        val tx1 = sampleTx("1")
        val tx2 = sampleTx("2")
        val snapshot = AnalysisSnapshot(
            manualCategories = setOf(),
            analysisEnabled = true,
            aiConfig = AiConfig(baseUrl = "https://ai.example.com", model = "test", apiKey = "key"),
        )

        val filtered = CategoryReviewLogic.filterReviewPayments(
            transactions = listOf(tx1, tx2),
            snapshot = snapshot,
            confirmedKeys = setOf(tx1.key),
        )
        // tx1 is excluded via confirmedKeys
        assertTrue(tx1 !in filtered)
    }
}
