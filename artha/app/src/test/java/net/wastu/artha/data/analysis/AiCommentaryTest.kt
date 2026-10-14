package net.wastu.artha.data.analysis

import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import net.wastu.artha.core.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiCommentaryTest {
    @Test fun connectionTestUsesDraftModelAndSyntheticPromptOnly() = runBlocking {
        var request: JSONObject? = null
        val server = TestServer { json, socket ->
            request = json
            val response = """{"choices":[{"message":{"content":"OK"}}]}""".toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
            }
        }
        val config = AiConfig("http://127.0.0.1:${server.port}", "fake-key", "draft-model")
        try {
            AiAnalysisService.testConnection(config)
            assertEquals("draft-model", request!!.getString("model"))
            assertEquals("Reply only with OK.", request!!.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("fake-key", config.apiKey)
            assertFalse(request.toString().contains("fake-key"))
        } finally { server.close() }
    }

    @Test fun connectionTestRejectsBlankModelReply() = runBlocking {
        val server = TestServer { _, socket ->
            val response = """{"choices":[{"message":{"content":" "}}]}""".toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
            }
        }
        try {
            val failure = runCatching { AiAnalysisService.testConnection(AiConfig("http://127.0.0.1:${server.port}", "fake", "fake")) }.exceptionOrNull()
            assertTrue(failure is EmptyAiResponseException)
            assertEquals("The model returned no reply", aiConnectionFailure(failure as Exception))
        } finally { server.close() }
    }

    @Test fun connectionErrorsAreActionableAndNeverExposeProviderBodies() = runBlocking {
        val server = TestServer { _, socket ->
            val response = "private provider diagnostic".toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 401 Unauthorized\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
            }
        }
        try {
            val failure = runCatching { AiAnalysisService.testConnection(AiConfig("http://127.0.0.1:${server.port}", "fake", "fake")) }.exceptionOrNull()
            assertTrue(failure is AiHttpException)
            assertEquals("Check the API key", aiConnectionFailure(failure as Exception))
            assertEquals("Check the URL and model", aiConnectionFailure(AiHttpException(404)))
            assertEquals("Rate limited. Try again", aiConnectionFailure(AiHttpException(429)))
            assertEquals("Connection timed out. Try again", aiConnectionFailure(java.net.SocketTimeoutException("private")))
            assertNotNull(aiConfigValidation(AiConfig("https://example.com", "", "model")))
            assertNotNull(aiConfigValidation(AiConfig("http://remote.invalid", "fake", "model")))
        } finally { server.close() }
    }

    private class TestServer(private val calls: Int = 1, handler: (JSONObject, Socket) -> Unit) {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { repeat(calls) { server.accept().use { socket ->
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
                var offset = 0
                while (offset < length) { val count = input.read(bytes, offset, length - offset); if (count < 0) break; offset += count }
                handler(JSONObject(bytes.toString(Charsets.UTF_8)), socket)
            } } } catch (_: java.io.IOException) { }
        }
        fun close() { server.close(); worker.join(1000) }
    }

    @Test fun baseUrlsDoNotRepeatVersionPaths() {
        assertEquals("https://example.com/v1/chat/completions", AiAnalysisService.endpoint("https://example.com/v1/"))
        assertEquals("https://example.com/v1/chat/completions", AiAnalysisService.endpoint("https://example.com"))
        assertEquals("https://example.com/v1beta/openai/chat/completions", AiAnalysisService.endpoint("https://example.com/v1beta/openai"))
    }

    @Test fun invalidAndEmptyResponsesNeverMarkAnalysisComplete() {
        assertThrows(Exception::class.java) { AiAnalysisService.parseCommentary("not JSON") }
        assertThrows(Exception::class.java) { AiAnalysisService.parseCommentary("{}") }
        assertThrows(Exception::class.java) { AiAnalysisService.parseCommentary("{\"monthlySummary\":\"\"}") }
        assertThrows(Exception::class.java) { AiAnalysisService.parseCommentary("{\"monthlySummary\":123}") }
        assertTrue(AiAnalysisService.parseCommentary("{\"monthlySummary\":\"- Valid\",\"items\":[{\"id\":\"other\",\"category\":\"Transfers\"}]}").monthlySummary.contains("Valid"))
    }

    @Test fun entireRecordedMonthAndPreviousMonthReachCommentary() = runBlocking {
        val prompts = mutableListOf<String>()
        var number = 0
        val server = TestServer(calls = 3) { request, socket ->
            prompts += request.toString()
            val message = if (number++ == 0) JSONObject("""{"role":"assistant","tool_calls":[{"id":"test-1","type":"function","function":{"name":"monthly_trends","arguments":"{}"}}]}""")
                else JSONObject().put("role", "assistant").put("content", """{"findings":["Recorded totals reviewed"],"recommendations":[]}""")
            val response = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", message))).toString().toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
            }
        }
        try {
            val rows = (1..40).map { UnifiedTransaction(Provider.JAGO, "jago:$it", Direction.DEBIT, Money(123400), Instant.parse("2026-10-02T00:00:00Z"), "Coffee", "{}", category = "Dining Out") }
            val previous = rows.first().copy(providerTransactionId = "jago:previous", money = Money(200000), occurredAt = Instant.parse("2026-09-02T00:00:00Z"))
            AiAnalysisService.analyzeMonth(AiConfig("http://127.0.0.1:${server.port}", "fake-test-key", "fake-model"), "", "2026-10", rows + previous)
            assertEquals(3, prompts.size)
            val evidence = JSONObject(JSONObject(prompts.first()).getJSONArray("messages").getJSONObject(1).getString("content")).getJSONObject("evidence")
            val months = evidence.getJSONArray("months")
            assertEquals(49360L, months.getJSONObject(2).getLong("spendingRupiah"))
            assertEquals(2000L, months.getJSONObject(1).getLong("spendingRupiah"))
            assertEquals(40, months.getJSONObject(2).getInt("paymentCount"))
            assertTrue(prompts[1].contains("tool_call_id"))
            assertFalse(JSONObject(prompts.last()).has("tools"))
        } finally { server.close() }
    }

    @Test fun localTotalsRemainAvailableWithoutInventingCategories() {
        val row = UnifiedTransaction(Provider.JAGO, "jago:1", Direction.DEBIT, Money(123400), Instant.parse("2026-10-02T00:00:00Z"), "Kopi Kenangan", "{}")
        val category = TransactionMetadata.enrich(row, AnalysisSnapshot()).category
        assertEquals("Uncategorized", category)
        val analysis = BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 10), listOf(row.copy(category = category)))
        assertTrue(analysis.localSummary.contains("Spending:"))
        assertTrue(analysis.localSummary.contains("Uncategorized"))
        assertTrue(formatMoney(Money(1234)).contains("12,34"))
    }

    @Test fun paymentConfirmationAndReversalChangesInvalidateSavedCommentary() {
        val row = UnifiedTransaction(Provider.GRAB, "grab:1", Direction.DEBIT, Money(100), Instant.parse("2026-10-02T00:00:00Z"), "Payment", "{}")
        fun fingerprint(value: UnifiedTransaction) = BudgetAnalyzer.computeMonthFingerprint(listOf(value), "", "", emptyList())
        assertNotEquals(fingerprint(row), fingerprint(row.copy(isConfirmed = false)))
        assertNotEquals(fingerprint(row), fingerprint(row.copy(isReversal = true)))
    }

    @Test fun canceledCommentaryDoesNotWaitForTheServerOrProduceAResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val server = TestServer { _, socket ->
            socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n".toByteArray()); flush() }
            started.complete(Unit)
            release.await(4, java.util.concurrent.TimeUnit.SECONDS)
        }
        try {
            var delivered = false
            val row = UnifiedTransaction(Provider.JAGO, "jago:1", Direction.DEBIT, Money(100), Instant.parse("2026-10-02T00:00:00Z"), "Payment", "{}")
            val job = launch {
                AiAnalysisService.analyzeMonth(AiConfig("http://127.0.0.1:${server.port}", "fake", "fake"), "", "2026-10", listOf(row))
                delivered = true
            }
            withTimeout(2000) { started.await() }
            withTimeout(2000) { job.cancelAndJoin() }
            assertFalse(delivered)
        } finally { release.countDown(); server.close() }
    }
}
