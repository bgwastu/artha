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
    private class TestServer(handler: (JSONObject, Socket) -> Unit) {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
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
                var offset = 0
                while (offset < length) { val count = input.read(bytes, offset, length - offset); if (count < 0) break; offset += count }
                handler(JSONObject(bytes.toString(Charsets.UTF_8)), socket)
            } } catch (_: java.io.IOException) { }
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
        var prompt = ""
        val server = TestServer { request, socket ->
            prompt = request.getJSONArray("messages").getJSONObject(0).getString("content")
            val response = """{"choices":[{"message":{"content":"{\"monthlySummary\":\"- Complete recorded totals\"}"}}]}""".toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
            }
        }
        try {
            val rows = (1..40).map { UnifiedTransaction(Provider.JAGO, "jago:$it", Direction.DEBIT, Money(123400), Instant.parse("2026-10-02T00:00:00Z"), "Coffee", "{}", category = "Dining Out") }
            val previous = rows.first().copy(providerTransactionId = "jago:previous", money = Money(200000), occurredAt = Instant.parse("2026-09-02T00:00:00Z"))
            AiAnalysisService.analyzeMonth(AiConfig("http://127.0.0.1:${server.port}", "fake-test-key", "fake-model"), "", "2026-10", rows + previous)
            assertTrue(prompt.contains("\"confirmedSpendingRupiah\":49360"))
            assertTrue(prompt.contains("\"previousCompleteMonthRupiah\":2000"))
            assertTrue(prompt.contains("\"transactionCount\":40"))
        } finally { server.close() }
    }

    @Test fun usefulLocalInsightsAndCategoriesNeedNoAiProvider() {
        val row = UnifiedTransaction(Provider.JAGO, "jago:1", Direction.DEBIT, Money(123400), Instant.parse("2026-10-02T00:00:00Z"), "Kopi Kenangan", "{}")
        val category = CategoryClassifier.classify(row).category
        assertEquals("Dining Out", category)
        val analysis = BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 10), listOf(row.copy(category = category)))
        assertTrue(analysis.localSummary.contains("Spending:"))
        assertTrue(analysis.localSummary.contains("Dining Out"))
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
