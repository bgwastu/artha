package net.wastu.artha.data.analysis

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.*
import org.junit.Test

class MerchantMemoryTest {
    private fun payment(
        id: String = "tx1",
        description: String = "Indomaret",
        rawPayload: String = "{}",
        manual: Boolean = false,
        direction: Direction = Direction.DEBIT,
        occurredAt: Instant = Instant.parse("2026-10-10T10:00:00Z"),
    ) = UnifiedTransaction(
        provider = Provider.JAGO,
        providerTransactionId = id,
        direction = direction,
        money = Money(25000),
        occurredAt = occurredAt,
        description = description,
        rawPayload = rawPayload,
        classificationIsManual = manual,
    )

    @Test
    fun merchantKeyNormalization() {
        assertEquals("indomaret", MerchantMemory.merchantKey(payment(description = "Indomaret")))
        assertEquals("indomaret", MerchantMemory.merchantKey(payment(description = "Indomaret - Sudirman")))
        assertEquals("indomaret", MerchantMemory.merchantKey(payment(description = "  Indomaret   -   Thamrin  ")))
        assertEquals("kopi kenangan", MerchantMemory.merchantKey(payment(description = "Kopi Kenangan - Grand Indonesia")))
        assertEquals("kopi kenangan", MerchantMemory.merchantKey(payment(description = "QRIS - KOPI KENANGAN 1320")))
        assertEquals("circle k", MerchantMemory.merchantKey(payment(description = "Circle K - Jak0922 - Qris")))
        assertEquals("circle k", MerchantMemory.merchantKey(payment(description = "CIRCLE K - JAK0922")))
        assertEquals("pln", MerchantMemory.merchantKey(payment(description = "PLN")))
        assertEquals("seven-eleven", MerchantMemory.merchantKey(payment(description = "Seven-Eleven")))
        assertEquals("warung nasi", MerchantMemory.merchantKey(payment(description = "Warung Nasi (Reversed)")))
        assertEquals("dapur mentari", MerchantMemory.merchantKey(payment(description = "GoFood - Dapur Mentari")))

        // From rawPayload
        val payloadTx = payment(rawPayload = """{"merchantName":"Starbucks - T3 Departure"}""")
        assertEquals("starbucks", MerchantMemory.merchantKey(payloadTx))

        // Blank and unusable merchants return null
        // Blank and unusable merchants return null
        assertNull(MerchantMemory.merchantKey(payment(description = " - ")))
        assertNull(MerchantMemory.merchantKey(payment(description = "Generic Jago payment")))
        assertNull(MerchantMemory.merchantKey(payment(description = "GoPay")))
        assertNull(MerchantMemory.merchantKey(payment(description = "•• 1234")))
    }

    @Test
    fun displayMerchantFormatting() {
        assertEquals("Indomaret", MerchantMemory.displayMerchant(payment(description = "Indomaret - Sudirman")))
        assertEquals("Kopi Kenangan", MerchantMemory.displayMerchant(payment(description = "QRIS - KOPI KENANGAN 1320")))
        assertEquals("PLN", MerchantMemory.displayMerchant(payment(description = "PLN")))
        assertEquals("Circle K", MerchantMemory.displayMerchant(payment(description = "Circle K - Jak0922 - Qris")))
    }

    @Test
    fun rememberedManualBeatsAi() {
        val p1 = payment("p1", description = "Indomaret - Sudirman", manual = true)
        val p2 = payment("p2", description = "Indomaret - Thamrin")
        val p3 = payment("p3", description = "Indomaret - Kuningan")
        val target = payment("target", description = "Indomaret")

        val snapshot = AnalysisSnapshot(
            categories = mapOf(p1.key to "Groceries", p2.key to "Dining Out", p3.key to "Dining Out"),
            reasons = mapOf(
                p2.key to "AI suggestion: Food",
                p3.key to "AI suggestion: Food",
            ),
            manualCategories = setOf(p1.key),
            aiRecords = aiRecords(consistent = setOf(p2.key, p3.key)),
        )

        val rows = listOf(p1, p2, p3, target)
        val suggestion = MerchantMemory.remembered(target, rows, snapshot)
        assertNotNull(suggestion)
        assertEquals("Groceries", suggestion?.category)
        assertEquals("Same merchant: Same as earlier Indomaret payments", suggestion?.reason)
        assertEquals(CategoryConfidence.HIGH, suggestion?.confidence)
        assertTrue(suggestion?.merchantConsistent == true)
    }

    @Test
    fun rememberedLatestManualPickWins() {
        val p1 = payment("p1", description = "Kopi Kenangan - Mall A", manual = true)
        val p2 = payment("p2", description = "Kopi Kenangan - Mall B", manual = true)
        val target = payment("target", description = "Kopi Kenangan")

        val snapshot = AnalysisSnapshot(
            categories = mapOf(p1.key to "Dining Out", p2.key to "Groceries"),
            manualCategories = setOf(p1.key, p2.key),
            updatedAt = mapOf("cat:${p1.key}" to 1000L, "cat:${p2.key}" to 2000L),
        )

        val rows = listOf(p1, p2, target)
        val suggestion = MerchantMemory.remembered(target, rows, snapshot)
        assertNotNull(suggestion)
        assertEquals("Groceries", suggestion?.category)
    }

    @Test
    fun rememberedNeedsTwoAgreeingConsistentAiLabels() {
        val p1 = payment("p1", description = "PLN")
        val p2 = payment("p2", description = "PLN")
        val target = payment("target", description = "PLN")

        // Only 1 AI label -> null
        val snapshotOne = AnalysisSnapshot(
            categories = mapOf(p1.key to "Utilities & Bills"),
            reasons = mapOf(p1.key to "AI suggestion: Electricity"),
            aiRecords = aiRecords(consistent = setOf(p1.key)),
        )
        assertNull(MerchantMemory.remembered(target, listOf(p1, target), snapshotOne))

        // 2 AI labels that disagree -> null
        val snapshotDisagree = AnalysisSnapshot(
            categories = mapOf(p1.key to "Utilities & Bills", p2.key to "Tech & Subscriptions"),
            reasons = mapOf(p1.key to "AI suggestion: Bill", p2.key to "AI suggestion: Tech"),
            aiRecords = aiRecords(consistent = setOf(p1.key, p2.key)),
        )
        assertNull(MerchantMemory.remembered(target, listOf(p1, p2, target), snapshotDisagree))

        // 2 AI labels agree but merchantConsistent is false -> null
        val snapshotInconsistent = AnalysisSnapshot(
            categories = mapOf(p1.key to "Utilities & Bills", p2.key to "Utilities & Bills"),
            reasons = mapOf(p1.key to "AI suggestion: Bill", p2.key to "AI suggestion: Bill"),
            aiRecords = aiRecords(consistent = emptySet()),
        )
        assertNull(MerchantMemory.remembered(target, listOf(p1, p2, target), snapshotInconsistent))

        // 2 AI labels agree and merchantConsistent is true -> remembered!
        val snapshotConsistent = AnalysisSnapshot(
            categories = mapOf(p1.key to "Utilities & Bills", p2.key to "Utilities & Bills"),
            reasons = mapOf(p1.key to "AI suggestion: Bill", p2.key to "AI suggestion: Bill"),
            aiRecords = aiRecords(consistent = setOf(p1.key, p2.key)),
        )
        val suggestion = MerchantMemory.remembered(target, listOf(p1, p2, target), snapshotConsistent)
        assertNotNull(suggestion)
        assertEquals("Utilities & Bills", suggestion?.category)
        assertEquals("Same merchant: Same as earlier PLN payments", suggestion?.reason)
    }

    @Test
    fun rememberedMarketplaceNeverRememberedFromAiLabels() {
        val p1 = payment("p1", description = "Tokopedia")
        val p2 = payment("p2", description = "Tokopedia")
        val target = payment("target", description = "Tokopedia")

        // Marketplace payments have merchantConsistent = false, so they are not in aiMerchantConsistent
        val snapshot = AnalysisSnapshot(
            categories = mapOf(p1.key to "Shopping & Hardware", p2.key to "Shopping & Hardware"),
            reasons = mapOf(p1.key to "AI suggestion: Order", p2.key to "AI suggestion: Order"),
            aiRecords = aiRecords(consistent = emptySet()),
        )
        val suggestion = MerchantMemory.remembered(target, listOf(p1, p2, target), snapshot)
        assertNull(suggestion)
    }

    @Test
    fun rememberedNonSpendingIgnored() {
        val income = payment("p1", description = "Indomaret", manual = true, direction = Direction.CREDIT)
        val target = payment("target", description = "Indomaret")
        val snapshot = AnalysisSnapshot(
            categories = mapOf(income.key to "Groceries"),
            manualCategories = setOf(income.key),
        )
        assertNull(MerchantMemory.remembered(target, listOf(income, target), snapshot))
    }
}
