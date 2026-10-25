package net.wastu.artha.data.analysis

import java.time.Instant
import java.time.YearMonth
import net.wastu.artha.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

class AiCategorizationTest {
    @Test fun temporaryCategoryFailureRetriesOnceAndPreservesSuccessfulResult() = runTest {
        var calls = 0
        val result = AiCategorization.requestWithRetry {
            calls++
            if (calls == 1) throw java.io.IOException("temporary")
            mapOf("fixture" to CategorySuggestion("Groceries", "AI suggestion: Fixture"))
        }
        assertEquals(2, calls)
        assertEquals("Groceries", result.getValue("fixture").category)
        calls = 0
        assertTrue(runCatching { AiCategorization.requestWithRetry<Unit> { calls++; throw AiHttpException(503) } }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun invalidAiResponsesRetryButCredentialsAndConfigurationDoNot() = runTest {
        var calls = 0
        assertTrue(runCatching { AiCategorization.requestWithRetry<Unit> { calls++; throw InvalidCategoryResponseException(IllegalArgumentException()) } }.isFailure)
        assertEquals(2, calls)
        for (failure in listOf(AiHttpException(401), AiHttpException(403), AiHttpException(400), IllegalArgumentException("configuration"))) {
            calls = 0
            assertTrue(runCatching { AiCategorization.requestWithRetry<Unit> { calls++; throw failure } }.isFailure)
            assertEquals(1, calls)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun canceledRetryNeverSendsAnotherCategoryRequest() = runTest {
        var calls = 0
        val job = launch { AiCategorization.requestWithRetry<Unit> { calls++; throw java.io.IOException("temporary") } }
        runCurrent()
        assertEquals(1, calls)
        job.cancelAndJoin()
        testScheduler.advanceUntilIdle()
        assertEquals(1, calls)
    }

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
        assertEquals(CategorySuggestion("Groceries", "AI suggestion: Food shop", CategoryConfidence.LOW), result[payment.key])
        assertEquals(original, payment)
    }

    @Test
    fun parseReplyConfidenceAndMerchantConsistent() {
        val raw = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Fresh food","confidence":"high","merchantConsistent":true}]}"""
        val result = AiCategorization.parse(raw, batch, AiCategorization.categories(snapshot))
        val sug = result.getValue(payment.key)
        assertEquals("Groceries", sug.category)
        assertEquals("AI suggestion: Fresh food", sug.reason)
        assertEquals(CategoryConfidence.HIGH, sug.confidence)
        assertTrue(sug.merchantConsistent)

        // Missing confidence is treated as unsure (LOW, so it gets reviewed), missing merchantConsistent defaults to false
        val minimal = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Food"}]}"""
        val minResult = AiCategorization.parse(minimal, batch, AiCategorization.categories(snapshot))
        val minSug = minResult.getValue(payment.key)
        assertEquals(CategoryConfidence.LOW, minSug.confidence)
        assertFalse(minSug.merchantConsistent)

        // Unknown confidence is unsure too
        val unknownConf = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Food","confidence":"uncertain","merchantConsistent":false}]}"""
        val unkResult = AiCategorization.parse(unknownConf, batch, AiCategorization.categories(snapshot))
        assertEquals(CategoryConfidence.LOW, unkResult.getValue(payment.key).confidence)

        assertEquals(CategoryConfidence.LOW, AiCategorization.parseConfidence(69))
        assertEquals(CategoryConfidence.MEDIUM, AiCategorization.parseConfidence(70))
        assertEquals(CategoryConfidence.MEDIUM, AiCategorization.parseConfidence(84))
        assertEquals(CategoryConfidence.HIGH, AiCategorization.parseConfidence(85))
        assertEquals(CategoryConfidence.HIGH, AiCategorization.parseConfidence(0.92))
        assertEquals(CategoryConfidence.LOW, AiCategorization.parseConfidence("55%"))

        // Low confidence
        val lowConf = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Food","confidence":"low"}]}"""
        val lowResult = AiCategorization.parse(lowConf, batch, AiCategorization.categories(snapshot))
        assertEquals(CategoryConfidence.LOW, lowResult.getValue(payment.key).confidence)
    }

    @Test
    fun requestDataIncludesUserExamplesUpTo40DedupedByMerchantWithRecency() {
        val rows = (1..50).map { i ->
            UnifiedTransaction(
                provider = Provider.JAGO,
                providerTransactionId = "tx$i",
                direction = Direction.DEBIT,
                money = Money(10000 * i.toLong()),
                occurredAt = Instant.parse("2026-10-01T10:00:00Z"),
                description = "Merchant $i",
                rawPayload = "{}",
                classificationIsManual = true,
                category = "Cat $i",
                userNote = "Note $i",
            )
        }

        // Set updatedAt for recency: tx50 is newest, tx1 is oldest
        val updatedAt = rows.associate { "cat:${it.key}" to (it.providerTransactionId.removePrefix("tx").toLong() * 1000) }
        val snap = snapshot.copy(
            customCategories = (1..50).map { "Cat $it" },
            manualCategories = rows.map { it.key }.toSet(),
            categories = rows.associate { it.key to "Cat ${it.providerTransactionId.removePrefix("tx")}" },
            updatedAt = updatedAt,
        )

        val req = AiCategorization.requestData(snap, batch, rows)
        assertTrue(req.has("userExamples"))
        val examples = req.getJSONArray("userExamples")
        assertEquals(40, examples.length())

        // tx50 should be first (highest timestamp)
        val first = examples.getJSONObject(0)
        assertEquals("Merchant 50", first.getString("merchant"))
        assertEquals("Cat 50", first.getString("category"))

        // Examples must not include amount, notes or IDs
        assertFalse(first.has("amount"))
        assertFalse(first.has("amountMinor"))
        assertFalse(first.has("userNote"))
        assertFalse(first.has("note"))
        assertFalse(first.has("id"))
        assertFalse(first.has("key"))

        // Deduping by merchant: duplicate merchants should only appear once
        val dup1 = payment.copy(providerTransactionId = "d1", description = "Indomaret - Sudirman", classificationIsManual = true)
        val dup2 = payment.copy(providerTransactionId = "d2", description = "Indomaret - Thamrin", classificationIsManual = true)
        val dupSnap = snapshot.copy(
            categories = mapOf(dup1.key to "Groceries", dup2.key to "Groceries"),
            manualCategories = setOf(dup1.key, dup2.key),
            updatedAt = mapOf("cat:${dup1.key}" to 100L, "cat:${dup2.key}" to 200L),
        )
        val dupReq = AiCategorization.requestData(dupSnap, batch, listOf(dup1, dup2))
        val dupExamples = dupReq.getJSONArray("userExamples")
        assertEquals(1, dupExamples.length())
        assertEquals("Indomaret", dupExamples.getJSONObject(0).getString("merchant"))
    }

    @Test
    fun noMassRelabelAfterPersonalContextModelOrCategoriesChange() {
        val baseFp = AiCategorization.fingerprint(payment, snapshot)
        val withKnowledge = snapshot.copy(knowledge = "User is vegetarian and lives in Jakarta")
        val withModel = snapshot.copy(aiConfig = AiConfig(baseUrl = "https://ai.wastu.net", apiKey = "key", model = "new-model"))
        val withCats = snapshot.copy(customCategories = listOf("Custom 1", "Custom 2"))

        assertEquals(baseFp, AiCategorization.fingerprint(payment, withKnowledge))
        assertEquals(baseFp, AiCategorization.fingerprint(payment, withModel))
        assertEquals(baseFp, AiCategorization.fingerprint(payment, withCats))

        // When payment note or source data changes, fingerprint DOES change
        val withNote = snapshot.copy(userNotes = mapOf(payment.key to "Personal expense"))
        assertNotEquals(baseFp, AiCategorization.fingerprint(payment, withNote))
        assertNotEquals(baseFp, AiCategorization.fingerprint(payment.copy(rawPayload = """{"changed":true}"""), snapshot))
    }

    @Test
    fun monthPendingCoversUnlabeledAndFailedButNotLabeledReviewOrManualPayments() {
        val month = YearMonth.of(1970, 1)
        val unlabeled = payment
        val labeled = payment.copy(providerTransactionId = "l1")
        val review = payment.copy(providerTransactionId = "r1")
        val failed = payment.copy(providerTransactionId = "f1")
        val manual = payment.copy(providerTransactionId = "m1", classificationIsManual = true)
        val otherMonth = payment.copy(providerTransactionId = "o1", occurredAt = Instant.parse("1970-03-10T00:00:00Z"))
        val rows = listOf(unlabeled, labeled, review, failed, manual, otherMonth)
        fun aiLabeled(tx: UnifiedTransaction) = Triple(
            tx.key, "Groceries", AiCategorization.fingerprint(tx, snapshot),
        )
        val snap = snapshot.copy(
            categories = listOf(labeled, review, failed).associate { it.key to "Groceries" },
            reasons = listOf(labeled, review, failed).associate { it.key to AI_REASON_PREFIX + "Evidence" },
            aiCategoryFingerprints = listOf(labeled, review, failed).associate { aiLabeled(it).first to aiLabeled(it).third },
            aiConfidence = mapOf(review.key to CategoryConfidence.LOW),
            categoryFailures = mapOf(failed.key to "Timed out"),
            manualCategories = setOf(manual.key),
        )
        assertEquals(
            setOf(unlabeled.key, failed.key),
            AiCategorization.scopeKeys(RecategorizeScope.MonthPending(month), rows, snap),
        )
    }

    @Test
    fun scopeKeysNeverIncludeManualPicksExceptPaymentReplaceManual() {
        val manualTx = payment.copy(providerTransactionId = "m1", classificationIsManual = true, description = "Manual tx")
        val snapshotManual = snapshot.copy(
            manualCategories = setOf(manualTx.key),
            categories = mapOf(manualTx.key to "Groceries"),
        )
        val rows = listOf(payment, manualTx)

        // Payment scope without replaceManual -> excludes manual
        assertEquals(emptySet<String>(), AiCategorization.scopeKeys(RecategorizeScope.Payment(manualTx.key, replaceManual = false), rows, snapshotManual))
        // Payment scope with replaceManual = true -> includes manual
        assertEquals(setOf(manualTx.key), AiCategorization.scopeKeys(RecategorizeScope.Payment(manualTx.key, replaceManual = true), rows, snapshotManual))

        // All scope -> only non-manual spending
        assertEquals(setOf(payment.key), AiCategorization.scopeKeys(RecategorizeScope.All, rows, snapshotManual))

        // CategoryInMonth scope -> excludes manual even if in that category and month
        val octMonth = YearMonth.of(1970, 1) // Instant.EPOCH in AccountingTime.zone (UTC+7) is 1970-01
        assertEquals(emptySet<String>(), AiCategorization.scopeKeys(RecategorizeScope.CategoryInMonth("Groceries", octMonth), rows, snapshotManual))

        // Month scope -> excludes manual
        assertEquals(setOf(payment.key), AiCategorization.scopeKeys(RecategorizeScope.Month(octMonth), rows, snapshotManual))

        // NeedsReview scope -> excludes manual even if marked LOW confidence
        val snapWithLow = snapshotManual.copy(aiConfidence = mapOf(manualTx.key to CategoryConfidence.LOW, payment.key to CategoryConfidence.LOW))
        assertEquals(setOf(payment.key), AiCategorization.scopeKeys(RecategorizeScope.NeedsReview, rows, snapWithLow))
    }

    @Test
    fun paymentsAnsweredByMerchantMemoryAreNotSentToAi() {
        val earlierManual = payment.copy(providerTransactionId = "earlier", description = "Indomaret - Sudirman", classificationIsManual = true)
        val currentIndomaret = payment.copy(providerTransactionId = "current", description = "Indomaret - Thamrin")
        val otherPayment = payment.copy(providerTransactionId = "other", description = "Unique Store")

        val snap = snapshot.copy(
            categories = mapOf(earlierManual.key to "Groceries"),
            manualCategories = setOf(earlierManual.key),
        )
        val rows = listOf(earlierManual, currentIndomaret, otherPayment)

        // fromMemory should resolve currentIndomaret
        val fromMemory = AiCategorization.fromMemory(rows, snap)
        assertTrue(fromMemory.containsKey(currentIndomaret.key))
        assertEquals("Groceries", fromMemory[currentIndomaret.key]?.category)
        assertEquals("Same merchant: Same as earlier Indomaret payments", fromMemory[currentIndomaret.key]?.reason)
        assertFalse(fromMemory.containsKey(otherPayment.key))

        // pending should EXCLUDE currentIndomaret because merchant memory can answer it
        val (total, pending) = AiCategorization.pending(rows, snap)
        assertEquals(2, total) // currentIndomaret + otherPayment (earlierManual is manual, so not eligible)
        assertEquals(listOf(otherPayment.key), pending.map { it.transaction.key })
    }
}
