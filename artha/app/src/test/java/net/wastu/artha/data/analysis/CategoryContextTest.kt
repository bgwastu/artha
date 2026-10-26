package net.wastu.artha.data.analysis

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.wastu.artha.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CategoryContextTest {
    private fun payment(id: String = "fixture", raw: String = "{}") = UnifiedTransaction(
        Provider.SHOPEE, id, Direction.DEBIT, Money(123400), Instant.parse("2026-10-02T03:00:00Z"),
        "Merchant " + "description ".repeat(40), raw, Pocket("pocket-fixture", "Daily spending"),
        isQris = true, cardLast4 = "1234", userNote = "note ".repeat(200), category = "Uncategorized")

    @Test fun completeProductsUnknownFieldsAndLinkedSourceJsonReachTheRequestWithoutEmptyFields() {
        val detail = JSONObject().put("productDescription", "detail ".repeat(200)).put("quantity", 3)
            .put("unitAmountMinor", 123400).put("unknownProviderField", JSONObject().put("flag", false).put("missing", JSONObject.NULL))
        val bank = JSONObject().put("fields", JSONObject().put("f3", "Card merchant").put("f41", "IDR"))
        val raw = JSONObject().put("items", JSONArray().put(detail).put(JSONObject().put("name", "Second product")))
            .put("groupedTransactions", JSONArray().put(JSONObject().put("provider", "JAGO").put("rawPayload", bank.toString())))
            .put("restaurantName", "Full restaurant name").put("feeMinor", 1500).toString()
        val tx = payment(raw = raw)
        val snapshot = AnalysisSnapshot(knowledge = "personal context ".repeat(400))
        val request = AiCategorization.requestData(snapshot, AiCategorization.nextBatch(listOf(tx), snapshot))
        assertEquals(snapshot.knowledge, request.getString("personalContext"))
        val context = request.getJSONArray("transactions").getJSONObject(0).getJSONObject("transaction")
        assertEquals(tx.description, context.getString("description"))
        assertEquals(tx.userNote, context.getString("userNote"))
        assertEquals(tx.pocket!!.name, context.getJSONObject("pocket").getString("name"))
        assertEquals("1234", context.getString("cardLast4"))
        assertEquals(tx.money.amountMinor, context.getLong("amountMinor"))
        listOf("key", "providerTransactionId", "canonicalKey", "isConfirmed", "classificationIsManual").forEach { assertFalse(context.has(it)) }
        val source = context.getJSONObject("sourceData")
        val sentDetail = JSONObject(detail.toString()).apply { getJSONObject("unknownProviderField").remove("missing") }
        assertEquals(CategoryContext.canonical(sentDetail), CategoryContext.canonical(source.getJSONArray("items").getJSONObject(0)))
        assertEquals(2, source.getJSONArray("items").length())
        assertEquals(1500, source.getInt("feeMinor"))
        assertEquals(CategoryContext.canonical(bank), CategoryContext.canonical(source.getJSONArray("groupedTransactions")
            .getJSONObject(0).getJSONObject("rawPayload")))
        assertEquals(raw, tx.rawPayload)
    }

    @Test fun requestDropsOpaqueIdsAndEmptyValuesButFingerprintStillHashesThem() {
        val raw = JSONObject().put("orderId", "3f2a9c4e-81b0-4d6f-9e2a-7c1b5d8e0f42").put("reference", "202610150123456789")
            .put("notes", "").put("shopName", "Toko Obat Sehat").put("tags", JSONArray()).put("qty", 2).toString()
        val tx = payment(raw = raw)
        val sent = CategoryContext.request(tx, AnalysisSnapshot()) as JSONObject
        val source = sent.getJSONObject("sourceData")
        assertEquals("Toko Obat Sehat", source.getString("shopName"))
        assertEquals(2, source.getInt("qty"))
        listOf("orderId", "reference", "notes", "tags").forEach { assertFalse(source.has(it)) }
        val fp = AiCategorization.fingerprint(tx, AnalysisSnapshot())
        assertNotEquals(fp, AiCategorization.fingerprint(tx.copy(rawPayload = raw.replace("3f2a9c4e", "00000000")), AnalysisSnapshot()))
    }

    @Test fun allSourceEvidenceInvalidatesCacheButPriorAiOutputAndJsonFormattingDoNot() {
        val tx = payment(raw = """{"items":[{"name":"Printer","qty":1}],"fee":100}""")
        val snapshot = AnalysisSnapshot()
        val fp = AiCategorization.fingerprint(tx, snapshot)
        assertNotEquals(fp, AiCategorization.fingerprint(tx.copy(rawPayload = """{"items":[{"name":"Groceries","qty":1}],"fee":100}"""), snapshot))
        assertNotEquals(fp, AiCategorization.fingerprint(tx.copy(rawPayload = """{"items":[{"name":"Printer","qty":2}],"fee":100}"""), snapshot))
        assertEquals(fp, AiCategorization.fingerprint(tx.copy(rawPayload = """{ "fee":100, "items": [ {"qty":1,"name":"Printer"} ] }"""), snapshot))
        assertEquals(fp, AiCategorization.fingerprint(tx.copy(category = "Shopping & Hardware", aiReasoning = "AI suggestion: New result"), snapshot))
        val saved = snapshot.copy(reasons = mapOf(tx.key to "AI suggestion: Saved"), aiRecords = aiRecords(fingerprints = mapOf(tx.key to fp)))
        assertTrue(AiCategorization.nextBatch(listOf(tx), saved).isEmpty())
        assertEquals(1, AiCategorization.nextBatch(listOf(tx.copy(rawPayload = """{"newSourceField":"receipt context"}""")), saved).size)
    }

    @Test fun authenticationAndConfigurationAreNotPartOfFinancialContext() {
        val raw = """{"items":[{"name":"Food","authorization":"credential-one"}],"access_token":"credential-two","groupedTransactions":[{"rawPayload":"{\"apiKey\":\"credential-three\",\"amount\":1234}"}]}"""
        val snapshot = AnalysisSnapshot(aiConfig = AiConfig("https://fixture.invalid/v1", "configured-private-key", "fixture-model"))
        val request = AiCategorization.requestData(snapshot, AiCategorization.nextBatch(listOf(payment(raw = raw)), snapshot))
        listOf("credential-one", "credential-two", "credential-three", "configured-private-key").forEach { assertFalse(request.toString().contains(it)) }
        val source = request.getJSONArray("transactions").getJSONObject(0).getJSONObject("transaction").getJSONObject("sourceData")
        assertEquals("Food", source.getJSONArray("items").getJSONObject(0).getString("name"))
        assertEquals(1234, source.getJSONArray("groupedTransactions").getJSONObject(0).getJSONObject("rawPayload").getInt("amount"))
    }

    @Test fun largerContextsReduceBatchSizeWithoutTruncatingAnyRecord() {
        val text = "x".repeat(70000)
        val rows = (1..3).map { payment("fixture-$it", JSONObject().put("fullProductDetails", text).toString()) }
        val batch = AiCategorization.nextBatch(rows, AnalysisSnapshot())
        assertEquals(1, batch.size)
        assertEquals(text, AiCategorization.requestData(AnalysisSnapshot(), batch).getJSONArray("transactions")
            .getJSONObject(0).getJSONObject("transaction").getJSONObject("sourceData").getString("fullProductDetails"))
        val huge = payment(raw = JSONObject().put("fullProductDetails", "x".repeat(200000)).toString())
        val one = AiCategorization.nextBatch(listOf(huge), AnalysisSnapshot())
        assertEquals(1, one.size)
        assertEquals(200000, AiCategorization.requestData(AnalysisSnapshot(), one).getJSONArray("transactions")
            .getJSONObject(0).getJSONObject("transaction").getJSONObject("sourceData").getString("fullProductDetails").length)
    }

    @Test fun nonJsonSourceTextAndUserNoteOverrideRemainComplete() {
        val text = "Original source text ".repeat(80)
        val tx = payment(raw = text)
        val note = "Saved user note ".repeat(100)
        val snapshot = AnalysisSnapshot(userNotes = mapOf(tx.key to note))
        val context = CategoryContext.transaction(tx, snapshot)
        assertEquals(text, context.getString("sourceData"))
        assertEquals(note, context.getString("userNote"))
        val trailing = "{\"merchant\":\"shop\"} retained trailing source text"
        assertEquals(trailing, CategoryContext.transaction(tx.copy(rawPayload = trailing), snapshot).getString("sourceData"))
    }

    @Test fun actualCategoryTransportSendsStructuredCompleteContextAndOnlyReturnsASuggestion() = runBlocking {
        var received: JSONObject? = null
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { server.accept().use { socket ->
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) { val next = input.read(); if (next < 0 || next == 10) break; if (next != 13) bytes.write(next) }
                    return bytes.toString("UTF-8")
                }
                line()
                var length = 0
                while (true) { val header = line(); if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                val bytes = ByteArray(length)
                java.io.DataInputStream(input).readFully(bytes)
                received = JSONObject(bytes.toString(Charsets.UTF_8))
                val content = """{"classifications":[{"id":"p0","category":"Shopping & Hardware","reason":"Receipt lists a printer"}]}"""
                val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString().toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } catch (_: java.io.IOException) { }
        }
        val tx = payment(raw = """{"items":[{"name":"Printer","specification":"Complete product specification"}]}""")
        val snapshot = AnalysisSnapshot()
        try {
            val result = withTimeout(4000) { AiCategorization.classify(AiConfig("http://127.0.0.1:${server.localPort}", "fixture-key", "fixture-model"), snapshot,
                AiCategorization.nextBatch(listOf(tx), snapshot)) }
            val messages = received!!.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            val prefix = JSONObject(messages.getJSONObject(1).getString("content"))
            assertTrue(prefix.has("allowedCategories"))
            assertFalse(prefix.has("transactions"))
            val context = JSONObject(messages.getJSONObject(2).getString("content")).getJSONArray("transactions")
                .getJSONObject(0).getJSONObject("transaction")
            assertEquals("Complete product specification", context.getJSONObject("sourceData").getJSONArray("items").getJSONObject(0).getString("specification"))
            assertEquals("Shopping & Hardware", result.getValue(tx.key).category)
            assertEquals("Uncategorized", tx.category)
            assertEquals(123400L, tx.money.amountMinor)
        } finally { server.close(); worker.join(1000) }
    }
}
