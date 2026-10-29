package net.wastu.artha.data.analysis

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.wastu.artha.core.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiCategorizationTest {

    private val payment = UnifiedTransaction(Provider.JAGO, "fixture", Direction.DEBIT, Money(10000), Instant.EPOCH, "Netflix restaurant tax taxi", "{}")
    private val snapshot = AnalysisSnapshot()
    private val batch get() = AiCategorization.nextBatch(listOf(payment), snapshot)

    @Test fun decorationAndSummaryChangesDoNotRestartCategoryRequests() {
        val changed = snapshot.copy(categoryIcons = mapOf("Groceries" to "leaf"),
            updatedAt = mapOf("icon:Groceries" to 10))
        assertEquals(AiCategorization.requestSnapshot(snapshot), AiCategorization.requestSnapshot(changed))
        assertNotEquals(AiCategorization.requestSnapshot(snapshot), AiCategorization.requestSnapshot(changed.copy(userNotes = mapOf(payment.key to "Context"))))
        assertNotEquals(AiCategorization.requestSnapshot(snapshot), AiCategorization.requestSnapshot(changed.copy(manualCategories = setOf(payment.key))))
    }

    @Test fun noProviderKeywordOrPersonalNoteInventsACategory() {
        for (tx in listOf(payment, payment.copy(provider = Provider.SHOPEE), payment.copy(rawPayload = """{"serviceTier":"GoFood","pickupLocation":"here"}"""))) {
            assertNull(SpendingProjector().project(listOf(tx), snapshot).transactions.single().category)
            assertNull(TransactionMetadata.enrich(tx, snapshot.copy(userNotes = mapOf(tx.key to "groceries"))).category)
        }
        val oldRules = snapshot.copy(categories = mapOf(payment.key to "Dining Out"), reasons = mapOf(payment.key to "Local provider and merchant rule; you can choose a different category."))
        assertNull(SpendingProjector().project(listOf(payment), oldRules).transactions.single().category)
        assertEquals("Dining Out", SpendingProjector().project(listOf(payment), oldRules.copy(manualCategories = setOf(payment.key))).transactions.single().category)
        assertEquals("Dining Out", oldRules.categories[payment.key])
    }

    @Test fun onlyNonManualVerifiedSpendingIsSentAndUnchangedAiResultsAreCached() {
        val ignored = listOf(payment.copy(providerTransactionId = "manual", classificationIsManual = true),
            payment.copy(providerTransactionId = "credit", direction = Direction.CREDIT),
            payment.copy(providerTransactionId = "quote", isConfirmed = false),
            payment.copy(providerTransactionId = "transfer", rawPayload = """{"isPocketTransfer":true}"""))
        assertEquals(listOf(payment.key), AiCategorization.nextBatch(ignored + payment, snapshot).map { it.transaction.key })
        val done = snapshot.copy(categories = mapOf(payment.key to "Groceries"), reasons = mapOf(payment.key to "AI suggestion: Fixture"), aiRecords = aiRecords(fingerprints = mapOf(payment.key to batch.single().fingerprint)))
        assertTrue(AiCategorization.nextBatch(listOf(payment), done).isEmpty())
        assertEquals(1, AiCategorization.nextBatch(listOf(payment), done.copy(userNotes = mapOf(payment.key to "New context"))).size)
        assertTrue(AiCategorization.nextBatch(listOf(payment), snapshot.copy(manualCategories = setOf(payment.key))).isEmpty())
    }

    @Test
    fun batchLevelFailuresThrowInvalidCategoryResponseException() {
        for (raw in listOf(
            "not json",
            """{"other":[]}""",
            """{"classifications":"not-an-array"}""",
            """{"classifications":null}""",
        )) {
            val failure = runCatching {
                AiCategorization.parse(raw, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
            }
            assertTrue("Expected failure for: $raw", failure.isFailure)
            assertTrue(failure.exceptionOrNull() is InvalidCategoryResponseException)
        }
    }

    @Test
    fun perItemParsingToleratesUnknownDuplicateMissingAndMalformedItems() {
        // Empty classifications returns empty map
        assertEquals(emptyMap<String, CategorySuggestion>(), AiCategorization.parse("""{"classifications":[]}""", batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE)))

        // Unknown id is skipped
        assertEquals(emptyMap<String, CategorySuggestion>(), AiCategorization.parse("""{"classifications":[{"id":"unknown","category":"Groceries"}]}""", batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE)))

        // Unknown category is dropped
        assertEquals(emptyMap<String, CategorySuggestion>(), AiCategorization.parse("""{"classifications":[{"id":"p0","category":"Imaginary"}]}""", batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE)))

        // Investments & Transfers is dropped (case-insensitive)
        assertEquals(emptyMap<String, CategorySuggestion>(), AiCategorization.parse("""{"classifications":[{"id":"p0","category":"Investments & Transfers"}]}""", batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE)))
        assertEquals(emptyMap<String, CategorySuggestion>(), AiCategorization.parse("""{"classifications":[{"id":"p0","category":"investments & transfers"}]}""", batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE)))

        // Duplicate id: first wins
        val duplicateRaw = """{"classifications":[{"id":"p0","category":"Groceries","reason":"First pick"},{"id":"p0","category":"Transport","reason":"Second pick"}]}"""
        val duplicateResult = AiCategorization.parse(duplicateRaw, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals(1, duplicateResult.size)
        assertEquals("Groceries", duplicateResult.getValue(payment.key).category)
        assertEquals("AI suggestion: First pick", duplicateResult.getValue(payment.key).reason)

        // Case-insensitive and trimmed category matching
        val caseRaw = """{"classifications":[{"id":"p0","category":"  groceries  "}]}"""
        val caseResult = AiCategorization.parse(caseRaw, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals("Groceries", caseResult.getValue(payment.key).category)

        // Truncate over-long reason to 240 chars
        val longReason = "A".repeat(300)
        val longReasonRaw = """{"classifications":[{"id":"p0","category":"Groceries","reason":"$longReason"}]}"""
        val longReasonResult = AiCategorization.parse(longReasonRaw, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals(AI_REASON_PREFIX + "A".repeat(240), longReasonResult.getValue(payment.key).reason)

        // Missing candidate is simply absent from map
        val p2 = payment.copy(providerTransactionId = "second")
        val twoBatch = batch + CategoryCandidate(p2, AiCategorization.fingerprint(p2, snapshot))
        val partialRaw = """{"classifications":[{"id":"p0","category":"Groceries"}]}"""
        val partialResult = AiCategorization.parse(partialRaw, twoBatch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals(1, partialResult.size)
        assertTrue(partialResult.containsKey(payment.key))
        assertFalse(partialResult.containsKey(p2.key))

        // Fenced JSON is parsed cleanly
        val fenced = """
            ```json
            {"classifications":[{"id":"p0","category":"Groceries","reason":"Clean"}]}
            ```
        """.trimIndent()
        val fencedResult = AiCategorization.parse(fenced, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals("Groceries", fencedResult.getValue(payment.key).category)

        // Fenced with preamble
        val preambleFenced = """
            Here is your output:
            ```json
            {"classifications":[{"id":"p0","category":"Groceries","reason":"Preamble"}]}
            ```
        """.trimIndent()
        val preambleResult = AiCategorization.parse(preambleFenced, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals("Groceries", preambleResult.getValue(payment.key).category)
    }

    @Test
    fun classificationSchemaGeneratesStrictOpenAiJsonSchema() {
        val allowed = listOf("Groceries", "Dining Out", "Transport")
        val schema = AiCategorization.classificationSchema(allowed)
        assertEquals("json_schema", schema.getString("type"))
        val jsonSchema = schema.getJSONObject("json_schema")
        assertEquals("classifications", jsonSchema.getString("name"))
        assertTrue(jsonSchema.getBoolean("strict"))

        val rootObj = jsonSchema.getJSONObject("schema")
        assertEquals("object", rootObj.getString("type"))
        assertFalse(rootObj.getBoolean("additionalProperties"))
        assertEquals("classifications", rootObj.getJSONArray("required").getString(0))

        val items = rootObj.getJSONObject("properties").getJSONObject("classifications").getJSONObject("items")
        assertEquals("object", items.getString("type"))
        assertFalse(items.getBoolean("additionalProperties"))

        val props = items.getJSONObject("properties")
        assertEquals("string", props.getJSONObject("id").getString("type"))
        assertEquals("string", props.getJSONObject("category").getString("type"))
        val categoryEnum = props.getJSONObject("category").getJSONArray("enum")
        assertEquals(3, categoryEnum.length())
        assertEquals("Groceries", categoryEnum.getString(0))
        assertEquals("Dining Out", categoryEnum.getString(1))
        assertEquals("Transport", categoryEnum.getString(2))

        assertEquals("integer", props.getJSONObject("confidence").getString("type"))
        assertEquals(0, props.getJSONObject("confidence").getInt("minimum"))
        assertEquals(100, props.getJSONObject("confidence").getInt("maximum"))

        assertEquals("boolean", props.getJSONObject("merchantConsistent").getString("type"))
        assertEquals("string", props.getJSONObject("reason").getString("type"))

        val required = items.getJSONArray("required")
        val requiredList = (0 until required.length()).map { required.getString(it) }.toSet()
        assertEquals(setOf("id", "category", "reason", "confidence", "merchantConsistent"), requiredList)
    }

    @Test
    fun classifySendsStructuredOutputAndFallsBackOnHttp400RememberingPerConfig() = runBlocking {
        AiCategorization.clearUnsupportedSchemaConfigs()
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val requestsReceived = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        var requestCount = 0

        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    socket.use { s ->
                        val input = s.getInputStream()
                        fun line(): String {
                            val bytes = ByteArrayOutputStream()
                            while (true) { val next = input.read(); if (next < 0 || next == 10) break; if (next != 13) bytes.write(next) }
                            return bytes.toString("UTF-8")
                        }
                        line()
                        var length = 0
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val bytes = ByteArray(length)
                        DataInputStream(input).readFully(bytes)
                        val reqJson = JSONObject(bytes.toString(Charsets.UTF_8))
                        requestsReceived.add(reqJson)
                        requestCount++

                        val output = s.getOutputStream()
                        if (requestCount == 1) {
                            val errBody = """{"error":{"message":"response_format not supported"}}""".toByteArray()
                            output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: ${errBody.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(errBody); output.flush()
                        } else {
                            val content = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Receipt"}]}"""
                            val body = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString().toByteArray()
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(body); output.flush()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val config = AiConfig("http://127.0.0.1:${server.localPort}", "test-key", "test-model")
        try {
            assertTrue(AiCategorization.isSchemaSupported(config))

            val result1 = withTimeout(5000) {
                AiCategorization.classify(config, snapshot, batch)
            }
            assertEquals("Groceries", result1.getValue(payment.key).category)
            assertEquals(2, requestsReceived.size)
            assertTrue("First request should have response_format", requestsReceived[0].has("response_format"))
            assertFalse("Retry request should not have response_format", requestsReceived[1].has("response_format"))
            assertFalse("Config should now be remembered as not supporting schema", AiCategorization.isSchemaSupported(config))

            val result2 = withTimeout(5000) {
                AiCategorization.classify(config, snapshot, batch)
            }
            assertEquals("Groceries", result2.getValue(payment.key).category)
            assertEquals(3, requestsReceived.size)
            assertFalse("Third request should directly skip response_format", requestsReceived[2].has("response_format"))
        } finally {
            server.close()
            worker.join(1000)
            AiCategorization.clearUnsupportedSchemaConfigs()
        }
    }

    @Test
    fun classifyPassesRowsExplicitlyToRequestData() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var received: JSONObject? = null
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    fun line(): String {
                        val bytes = ByteArrayOutputStream()
                        while (true) { val next = input.read(); if (next < 0 || next == 10) break; if (next != 13) bytes.write(next) }
                        return bytes.toString("UTF-8")
                    }
                    line()
                    var length = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val bytes = ByteArray(length)
                    DataInputStream(input).readFully(bytes)
                    received = JSONObject(bytes.toString(Charsets.UTF_8))
                    val content = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Receipt"}]}"""
                    val body = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString().toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: Exception) {}
        }

        val manualTx = payment.copy(providerTransactionId = "m1", classificationIsManual = true, description = "Supermarket")
        val snapWithManual = snapshot.copy(
            categories = mapOf(manualTx.key to "Groceries"),
            manualCategories = setOf(manualTx.key),
            updatedAt = mapOf("cat:${manualTx.key}" to 1000L),
        )
        val config = AiConfig("http://127.0.0.1:${server.localPort}", "test-key", "test-model")
        try {
            AiCategorization.classify(config, snapWithManual, batch, listOf(manualTx))
            val userContent = JSONObject(received!!.getJSONArray("messages").getJSONObject(1).getString("content"))
            val userExamples = userContent.getJSONArray("userExamples")
            assertEquals(1, userExamples.length())
            assertEquals("Supermarket", userExamples.getJSONObject(0).getString("merchant"))
            assertEquals("Groceries", userExamples.getJSONObject(0).getString("category"))
        } finally {
            server.close()
            worker.join(1000)
            AiCategorization.clearUnsupportedSchemaConfigs()
        }
    }

    @Test
    fun noHiddenGlobalRowsStateCoupling() {
        val manualTx = payment.copy(providerTransactionId = "m1", classificationIsManual = true, description = "Supermarket")
        val snapWithManual = snapshot.copy(
            categories = mapOf(manualTx.key to "Groceries"),
            manualCategories = setOf(manualTx.key),
            updatedAt = mapOf("cat:${manualTx.key}" to 1000L),
        )
        AiCategorization.pending(listOf(manualTx), snapWithManual)
        AiCategorization.fromMemory(listOf(manualTx), snapWithManual)
        AiCategorization.scopeKeys(RecategorizeScope.All, listOf(manualTx), snapWithManual)

        val req = AiCategorization.requestData(snapWithManual, batch)
        assertEquals(0, req.getJSONArray("userExamples").length())
    }

    @Test fun aiMaySuggestLabelsButCannotChangePaymentFacts() {
        val original = payment.copy()
        val result = AiCategorization.parse("""{"classifications":[{"id":"p0","category":"Groceries","reason":"Food shop","amountMinor":1,"date":"2099","isConfirmed":false}]}""", batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals(CategorySuggestion("Groceries", "AI suggestion: Food shop", percentOf(CategoryConfidence.LOW)), result[payment.key])
        assertEquals(original, payment)
    }

    @Test
    fun parseReplyConfidenceAndMerchantConsistent() {
        val raw = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Fresh food","confidence":"high","merchantConsistent":true}]}"""
        val result = AiCategorization.parse(raw, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        val sug = result.getValue(payment.key)
        assertEquals("Groceries", sug.category)
        assertEquals("AI suggestion: Fresh food", sug.reason)
        assertEquals(CategoryConfidence.HIGH, sug.confidence)
        assertTrue(sug.merchantConsistent)

        // Missing confidence is treated as unsure (LOW, so it gets reviewed), missing merchantConsistent defaults to false
        val minimal = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Food"}]}"""
        val minResult = AiCategorization.parse(minimal, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        val minSug = minResult.getValue(payment.key)
        assertEquals(CategoryConfidence.LOW, minSug.confidence)
        assertFalse(minSug.merchantConsistent)

        // Unknown confidence is unsure too
        val unknownConf = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Food","confidence":"uncertain","merchantConsistent":false}]}"""
        val unkResult = AiCategorization.parse(unknownConf, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
        assertEquals(CategoryConfidence.LOW, unkResult.getValue(payment.key).confidence)

        // Only picks above 90% are trusted; 90 itself goes to review.
        assertEquals(CategoryConfidence.LOW, CategoryConfidence.of(AiCategorization.parseConfidence(90)))
        assertEquals(CategoryConfidence.MEDIUM, CategoryConfidence.of(AiCategorization.parseConfidence(91)))
        assertEquals(CategoryConfidence.MEDIUM, CategoryConfidence.of(AiCategorization.parseConfidence(0.92)))
        assertEquals(CategoryConfidence.HIGH, CategoryConfidence.of(AiCategorization.parseConfidence(95)))
        assertEquals(CategoryConfidence.LOW, CategoryConfidence.of(AiCategorization.parseConfidence("55%")))

        // Low confidence
        val lowConf = """{"classifications":[{"id":"p0","category":"Groceries","reason":"Food","confidence":"low"}]}"""
        val lowResult = AiCategorization.parse(lowConf, batch, AiCategorization.categories(snapshot, TransactionType.EXPENSE))
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
            aiRecords = aiRecords(fingerprints = listOf(labeled, review, failed).associate { aiLabeled(it).first to aiLabeled(it).third }, confidence = mapOf(review.key to CategoryConfidence.LOW), failures = mapOf(failed.key to "Timed out")),
            manualCategories = setOf(manual.key),
        )
        assertEquals(
            setOf(unlabeled.key),
            AiCategorization.scopeKeys(RecategorizeScope.MonthPending(month), rows, snap),
        )
    }

    @Test
    fun monthPendingDoesNotResendPaymentsTheModelAnsweredUncategorized() {
        val answered = payment
        val snap = snapshot.copy(
            categories = emptyMap(),
            reasons = mapOf(answered.key to AI_REASON_PREFIX + "No evidence"),
            aiRecords = aiRecords(fingerprints = mapOf(answered.key to AiCategorization.fingerprint(answered, snapshot)), confidence = mapOf(answered.key to CategoryConfidence.HIGH)),
        )
        assertEquals(emptySet<String>(), AiCategorization.scopeKeys(RecategorizeScope.MonthPending(YearMonth.of(1970, 1)), listOf(answered), snap))
    }

    @Test
    fun monthPendingIgnoresPaymentsLabeledFromTheSameMerchant() {
        val copied = payment
        val snap = snapshot.copy(
            categories = mapOf(copied.key to "Groceries"),
            reasons = mapOf(copied.key to MERCHANT_REASON_PREFIX + "Indomaret"),
            aiRecords = aiRecords(confidence = mapOf(copied.key to CategoryConfidence.HIGH)),
        )
        assertEquals(emptySet<String>(), AiCategorization.scopeKeys(RecategorizeScope.MonthPending(YearMonth.of(1970, 1)), listOf(copied), snap))
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
        val snapWithLow = snapshotManual.copy(aiRecords = aiRecords(confidence = mapOf(manualTx.key to CategoryConfidence.LOW, payment.key to CategoryConfidence.LOW)))
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
