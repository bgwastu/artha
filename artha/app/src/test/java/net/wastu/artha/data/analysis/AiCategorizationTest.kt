package net.wastu.artha.data.analysis

import java.time.Instant
import net.wastu.artha.core.model.*
import org.junit.Assert.*
import org.junit.Test

class AiCategorizationTest {
    private val payment = UnifiedTransaction(Provider.JAGO, "fixture", Direction.DEBIT, Money(10000), Instant.EPOCH, "Netflix restaurant tax taxi", "{}")
    private val snapshot = AnalysisSnapshot()
    private val batch get() = AiCategorization.nextBatch(listOf(payment), snapshot)

    @Test fun decorationAndSummaryChangesDoNotRestartCategoryRequests() {
        val changed = snapshot.copy(categoryIcons = mapOf("Groceries" to "leaf"), monthlySummaries = mapOf("2026-10" to "Summary"),
            monthlyNotes = mapOf("2026-10" to "Note"), analysisFingerprints = mapOf("2026-10" to "fingerprint"), updatedAt = mapOf("icon:Groceries" to 10))
        assertEquals(AiCategorization.requestSnapshot(snapshot), AiCategorization.requestSnapshot(changed))
        assertNotEquals(AiCategorization.requestSnapshot(snapshot), AiCategorization.requestSnapshot(changed.copy(userNotes = mapOf(payment.key to "Context"))))
        assertNotEquals(AiCategorization.requestSnapshot(snapshot), AiCategorization.requestSnapshot(changed.copy(manualCategories = setOf(payment.key))))
    }

    @Test fun noProviderKeywordOrPersonalNoteInventsACategory() {
        for (tx in listOf(payment, payment.copy(provider = Provider.SHOPEE), payment.copy(rawPayload = """{"serviceTier":"GoFood","pickupLocation":"here"}"""))) {
            assertEquals("Uncategorized", SpendingProjector().project(listOf(tx), snapshot).transactions.single().category)
            assertEquals("Uncategorized", TransactionMetadata.enrich(tx, snapshot.copy(userNotes = mapOf(tx.key to "groceries"))).category)
        }
        val oldRules = snapshot.copy(categories = mapOf(payment.key to "Dining Out"), reasons = mapOf(payment.key to "Local provider and merchant rule; you can choose a different category."))
        assertEquals("Uncategorized", SpendingProjector().project(listOf(payment), oldRules).transactions.single().category)
        assertEquals("Dining Out", SpendingProjector().project(listOf(payment), oldRules.copy(manualCategories = setOf(payment.key))).transactions.single().category)
        assertEquals("Dining Out", oldRules.categories[payment.key])
    }

    @Test fun onlyNonManualVerifiedSpendingIsSentAndUnchangedAiResultsAreCached() {
        val ignored = listOf(payment.copy(providerTransactionId = "manual", classificationIsManual = true),
            payment.copy(providerTransactionId = "credit", direction = Direction.CREDIT),
            payment.copy(providerTransactionId = "quote", isConfirmed = false),
            payment.copy(providerTransactionId = "transfer", rawPayload = """{"isPocketTransfer":true}"""))
        assertEquals(listOf(payment.key), AiCategorization.nextBatch(ignored + payment, snapshot).map { it.transaction.key })
        val done = snapshot.copy(categories = mapOf(payment.key to "Groceries"), reasons = mapOf(payment.key to "AI suggestion: Fixture"), aiCategoryFingerprints = mapOf(payment.key to batch.single().fingerprint))
        assertTrue(AiCategorization.nextBatch(listOf(payment), done).isEmpty())
        assertEquals(1, AiCategorization.nextBatch(listOf(payment), done.copy(userNotes = mapOf(payment.key to "New context"))).size)
        assertTrue(AiCategorization.nextBatch(listOf(payment), snapshot.copy(manualCategories = setOf(payment.key))).isEmpty())
    }

    @Test fun unknownDuplicateMissingAndAccountingCategoriesAreRejected() {
        for (raw in listOf(
            """{"classifications":[]}""",
            """{"classifications":[{"id":"unknown","category":"Groceries"}]}""",
            """{"classifications":[{"id":"p0","category":"Imaginary"}]}""",
            """{"classifications":[{"id":"p0","category":"Investments & Transfers"}]}""")) {
            assertTrue(runCatching { AiCategorization.parse(raw, batch, AiCategorization.categories(snapshot)) }.isFailure)
        }
        val two = batch + batch.single().copy(transaction = payment.copy(providerTransactionId = "second"))
        assertTrue(runCatching { AiCategorization.parse("""{"classifications":[{"id":"p0","category":"Groceries"},{"id":"p0","category":"Transport"}]}""", two, AiCategorization.categories(snapshot)) }.isFailure)
    }

    @Test fun aiMaySuggestLabelsButCannotChangePaymentFacts() {
        val original = payment.copy()
        val result = AiCategorization.parse("""{"classifications":[{"id":"p0","category":"Groceries","reason":"Food shop","amountMinor":1,"date":"2099","isConfirmed":false}]}""", batch, AiCategorization.categories(snapshot))
        assertEquals(CategorySuggestion("Groceries", "AI suggestion: Food shop"), result[payment.key])
        assertEquals(original, payment)
    }
}
