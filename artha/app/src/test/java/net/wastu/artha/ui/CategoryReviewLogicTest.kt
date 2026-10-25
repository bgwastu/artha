package net.wastu.artha.ui

import java.time.Instant
import java.time.LocalDate
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AI_REASON_PREFIX
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategoryConfidence
import net.wastu.artha.data.analysis.CategoryConstants
import net.wastu.artha.ui.screens.buildReviewQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryReviewLogicTest {

    private fun sampleTx(
        id: String,
        description: String = "Test tx",
        payload: String = "{}",
        category: String? = null,
        manual: Boolean = false,
        direction: Direction = Direction.DEBIT,
        occurredAt: Instant = Instant.parse("2026-03-01T10:00:00Z"),
        aiReasoning: String? = null,
    ) = UnifiedTransaction(
        provider = Provider.JAGO,
        providerTransactionId = id,
        direction = direction,
        money = Money(25_000_00L),
        occurredAt = occurredAt,
        description = description,
        rawPayload = payload,
        category = category,
        classificationIsManual = manual,
        aiReasoning = aiReasoning,
        isConfirmed = true,
    )

    private val baseSnapshot = AnalysisSnapshot(
        manualCategories = emptySet(),
        analysisEnabled = true,
        aiConfig = AiConfig(baseUrl = "https://ai.example.com", model = "test", apiKey = "key"),
        customCategories = listOf("Dining Out", "Groceries", "Transport", "Shopping", "Tech & Subscriptions"),
    )

    private val today = LocalDate.of(2026, 3, 1)

    @Test
    fun buildReviewQueueIncludesReviewAndFailedOnly() {
        val txReview = sampleTx("tx_rev", description = "Coffee Shop")
        val txFailed = sampleTx("tx_fail", description = "Unknown Merchant")
        val txAi = sampleTx("tx_ai", description = "Supermarket")
        val txManual = sampleTx("tx_manual", description = "Corner Store", manual = true)
        val txCredit = sampleTx("tx_credit", description = "Salary", direction = Direction.CREDIT)

        val snapshot = baseSnapshot.copy(
            aiConfidence = mapOf(
                txReview.key to CategoryConfidence.LOW,
                txAi.key to CategoryConfidence.HIGH,
            ),
            categoryFailures = mapOf(txFailed.key to "Could not determine category"),
            categories = mapOf(
                txReview.key to "Dining Out",
                txAi.key to "Groceries",
            ),
        )

        val queue = buildReviewQueue(
            rows = listOf(txReview, txFailed, txAi, txManual, txCredit),
            snapshot = snapshot,
            busyKeys = emptySet(),
            today = today,
        )

        val keys = queue.map { it.tx.key }.toSet()
        assertTrue("Queue must include REVIEW items", txReview.key in keys)
        assertTrue("Queue must include FAILED items", txFailed.key in keys)
        assertFalse("Queue must exclude confident AI items", txAi.key in keys)
        assertFalse("Queue must exclude manual items", txManual.key in keys)
        assertFalse("Queue must exclude credit (non-spending) items", txCredit.key in keys)
        assertEquals(2, queue.size)
    }

    @Test
    fun reviewQueueAndAttentionCountsStayInTheSelectedMonth() {
        val march = sampleTx("m", occurredAt = Instant.parse("2026-03-10T10:00:00Z"))
        val marchFailed = sampleTx("mf", occurredAt = Instant.parse("2026-03-11T10:00:00Z"))
        val february = sampleTx("f", occurredAt = Instant.parse("2026-02-10T10:00:00Z"))
        val medium = sampleTx("md", occurredAt = Instant.parse("2026-03-12T10:00:00Z"))
        val snapshot = baseSnapshot.copy(
            aiConfidence = mapOf(
                march.key to CategoryConfidence.LOW,
                february.key to CategoryConfidence.LOW,
                medium.key to CategoryConfidence.MEDIUM,
            ),
            categoryFailures = mapOf(marchFailed.key to "No answer"),
        )
        val rows = listOf(march, marchFailed, february, medium)
        val ym = java.time.YearMonth.of(2026, 3)

        val queue = buildReviewQueue(rows, snapshot, emptySet(), today, ym)
        assertEquals(setOf(march.key, marchFailed.key), queue.map { it.tx.key }.toSet())
        assertEquals(net.wastu.artha.data.analysis.CategoryAttention(review = 1, failed = 1), net.wastu.artha.data.analysis.categoryAttention(rows, snapshot, ym))
        assertEquals(3, buildReviewQueue(rows, snapshot, emptySet(), today).size)
    }

    @Test
    fun buildReviewQueueExcludesBusyAndManual() {
        val txBusy = sampleTx("tx_busy", description = "Busy Merchant")
        val txManualField = sampleTx("tx_man_field", description = "Manual Field", manual = true)
        val txManualSnapshot = sampleTx("tx_man_snap", description = "Manual Snapshot")
        val txNormal = sampleTx("tx_normal", description = "Normal Merchant")

        val snapshot = baseSnapshot.copy(
            aiConfidence = mapOf(
                txBusy.key to CategoryConfidence.LOW,
                txManualField.key to CategoryConfidence.LOW,
                txManualSnapshot.key to CategoryConfidence.LOW,
                txNormal.key to CategoryConfidence.LOW,
            ),
            manualCategories = setOf(txManualSnapshot.key),
        )

        val queue = buildReviewQueue(
            rows = listOf(txBusy, txManualField, txManualSnapshot, txNormal),
            snapshot = snapshot,
            busyKeys = setOf(txBusy.key),
            today = today,
        )

        val keys = queue.map { it.tx.key }
        assertEquals(listOf(txNormal.key), keys)
    }

    @Test
    fun buildReviewQueueNewestFirst() {
        val txOld = sampleTx("tx_old", description = "M1", occurredAt = Instant.parse("2026-03-01T08:00:00Z"))
        val txMid = sampleTx("tx_mid", description = "M2", occurredAt = Instant.parse("2026-03-01T12:00:00Z"))
        val txNew = sampleTx("tx_new", description = "M3", occurredAt = Instant.parse("2026-03-01T15:00:00Z"))

        val snapshot = baseSnapshot.copy(
            aiConfidence = mapOf(
                txOld.key to CategoryConfidence.LOW,
                txMid.key to CategoryConfidence.LOW,
                txNew.key to CategoryConfidence.LOW,
            ),
        )

        // Pass in random order
        val queue = buildReviewQueue(
            rows = listOf(txMid, txOld, txNew),
            snapshot = snapshot,
            busyKeys = emptySet(),
            today = today,
        )

        assertEquals(listOf("tx_new", "tx_mid", "tx_old"), queue.map { it.tx.providerTransactionId })
    }

    @Test
    fun buildReviewQueueAlternativesExcludeAiCategoryAndUncategorizedAndPreferSameMerchant() {
        // Merchant A transactions
        val currentTx = sampleTx(
            "curr",
            payload = """{"merchantName":"Starbucks"}""",
            description = "Starbucks",
        )
        val peer1 = sampleTx(
            "peer1",
            payload = """{"merchantName":"Starbucks"}""",
            description = "Starbucks",
        )
        val peer2 = sampleTx(
            "peer2",
            payload = """{"merchantName":"Starbucks"}""",
            description = "Starbucks",
        )
        val peer3 = sampleTx(
            "peer3",
            payload = """{"merchantName":"Starbucks"}""",
            description = "Starbucks",
        )

        // Other merchant transactions to establish overall top categories
        val other1 = sampleTx("o1", description = "Other 1")
        val other2 = sampleTx("o2", description = "Other 2")

        val snapshot = baseSnapshot.copy(
            aiConfidence = mapOf(currentTx.key to CategoryConfidence.LOW),
            categories = mapOf(
                currentTx.key to "Dining Out", // Current aiCategory
                peer1.key to "Coffee",
                peer2.key to "Coffee",
                peer3.key to CategoryConstants.UNCATEGORIZED,
                other1.key to "Groceries",
                other2.key to "Groceries",
            ),
        )

        val queue = buildReviewQueue(
            rows = listOf(currentTx, peer1, peer2, peer3, other1, other2),
            snapshot = snapshot,
            busyKeys = emptySet(),
            today = today,
        )

        assertEquals(1, queue.size)
        val item = queue.first()
        // Peer category "Coffee" should come first; "Dining Out" is current so excluded;
        // "Uncategorized" is excluded; next overall top is "Groceries"
        assertEquals(listOf("Coffee", "Groceries"), item.alternatives)
        assertFalse(item.alternatives.contains("Dining Out"))
        assertFalse(item.alternatives.contains(CategoryConstants.UNCATEGORIZED))
        assertTrue(item.alternatives.size <= 2)
    }

    @Test
    fun buildReviewQueueSiblingKeysGrouping() {
        val txA1 = sampleTx(
            "a1",
            payload = """{"merchantName":"Starbucks"}""",
            occurredAt = Instant.parse("2026-03-01T14:00:00Z"),
        )
        val txA2 = sampleTx(
            "a2",
            payload = """{"merchantName":"Starbucks"}""",
            occurredAt = Instant.parse("2026-03-01T12:00:00Z"),
        )
        val txA3 = sampleTx(
            "a3",
            payload = """{"merchantName":"Starbucks"}""",
            occurredAt = Instant.parse("2026-03-01T10:00:00Z"),
        )
        val txB1 = sampleTx(
            "b1",
            payload = """{"merchantName":"Indomaret"}""",
            occurredAt = Instant.parse("2026-03-01T11:00:00Z"),
        )
        // A non-queue item for Starbucks (confident AI)
        val txA4Confident = sampleTx(
            "a4",
            payload = """{"merchantName":"Starbucks"}""",
            occurredAt = Instant.parse("2026-03-01T09:00:00Z"),
        )

        val snapshot = baseSnapshot.copy(
            aiConfidence = mapOf(
                txA1.key to CategoryConfidence.LOW,
                txA2.key to CategoryConfidence.LOW,
                txA3.key to CategoryConfidence.LOW,
                txB1.key to CategoryConfidence.LOW,
                txA4Confident.key to CategoryConfidence.HIGH,
            ),
        )

        val queue = buildReviewQueue(
            rows = listOf(txA1, txA2, txA3, txB1, txA4Confident),
            snapshot = snapshot,
            busyKeys = emptySet(),
            today = today,
        )

        val itemA1 = queue.first { it.tx.key == txA1.key }
        val itemA2 = queue.first { it.tx.key == txA2.key }
        val itemB1 = queue.first { it.tx.key == txB1.key }

        // Sibling keys must only contain other queue items with same merchant key
        assertEquals(listOf(txA2.key, txA3.key), itemA1.siblingKeys)
        assertEquals(listOf(txA1.key, txA3.key), itemA2.siblingKeys)
        assertEquals(emptyList<String>(), itemB1.siblingKeys)
        assertFalse(itemA1.siblingKeys.contains(txA4Confident.key))
    }

    @Test
    fun buildReviewQueuePerformanceWith3000Rows() {
        val baseInstant = Instant.parse("2026-03-01T10:00:00Z")
        val categories = listOf("Dining Out", "Groceries", "Transport", "Shopping", "Tech & Subscriptions")

        val confidences = mutableMapOf<String, CategoryConfidence>()
        val txCategories = mutableMapOf<String, String>()
        val failures = mutableMapOf<String, String>()

        val rows = (1..3000).map { i ->
            val merchantId = i % 100
            val tx = sampleTx(
                id = "p_$i",
                description = "Merchant $merchantId",
                payload = """{"merchantName":"Merchant $merchantId"}""",
                occurredAt = baseInstant.minusSeconds(i.toLong() * 60),
            )
            // 200 items needing review or failed
            when {
                i in 1..150 -> {
                    confidences[tx.key] = CategoryConfidence.LOW
                    txCategories[tx.key] = categories[i % categories.size]
                }
                i in 151..200 -> {
                    failures[tx.key] = "Could not parse"
                }
                else -> {
                    confidences[tx.key] = CategoryConfidence.HIGH
                    txCategories[tx.key] = categories[i % categories.size]
                }
            }
            tx
        }

        val snapshot = baseSnapshot.copy(
            aiConfidence = confidences,
            categories = txCategories,
            categoryFailures = failures,
        )

        val start = System.nanoTime()
        val queue = buildReviewQueue(
            rows = rows,
            snapshot = snapshot,
            busyKeys = emptySet(),
            today = today,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(200, queue.size)
        assertTrue(
            "buildReviewQueue with 3000 rows must complete in well under 1000 ms, took $elapsedMs ms",
            elapsedMs < 1000,
        )
    }

    @Test
    fun buildReviewQueueItemFields() {
        val tx = sampleTx(
            id = "tx_1",
            payload = """{"merchantName":"Kopi Kenangan"}""",
            aiReasoning = "${AI_REASON_PREFIX}Coffee shop detected",
        )
        val snapshot = baseSnapshot.copy(
            aiConfidence = mapOf(tx.key to CategoryConfidence.LOW),
            categories = mapOf(tx.key to "Coffee"),
            reasons = mapOf(tx.key to "${AI_REASON_PREFIX}Coffee shop detected"),
        )

        val queue = buildReviewQueue(
            rows = listOf(tx),
            snapshot = snapshot,
            busyKeys = emptySet(),
            today = today,
        )

        val item = queue.first()
        assertEquals("Kopi Kenangan", item.merchant)
        assertEquals("Coffee", item.aiCategory)
        assertEquals("Coffee shop detected", item.reason)
        assertEquals("Rp25.000", item.amountText)
        assertTrue(item.dateText.isNotBlank())
    }
}
