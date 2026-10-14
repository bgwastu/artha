package net.wastu.artha.data.analysis

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import net.wastu.artha.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpendingAnalysisAgentTest {
    private val month = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 10, 14)
    private fun payment(id: String, date: String, amount: Long = 10000) = UnifiedTransaction(
        Provider.JAGO, id, Direction.DEBIT, Money(amount), Instant.parse(date), "Fictional merchant", "{\"private\":\"never-send\"}", category = "Groceries")
    private val rows = listOf(payment("current", "2026-10-01T03:00:00Z"), payment("august", "2026-08-12T03:00:00Z"),
        payment("last-year", "2025-12-12T03:00:00Z"), payment("outside", "2025-10-01T03:00:00Z"), payment("old", "2023-01-05T03:00:00Z"),
        payment("older", "2023-09-05T03:00:00Z"),
        payment("future", "2026-10-24T03:00:00Z"), payment("reversed", "2026-10-01T03:00:00Z").copy(isReversal = true),
        payment("unconfirmed", "2026-10-01T03:00:00Z").copy(isConfirmed = false),
        payment("funding", "2026-10-01T03:00:00Z").copy(rawPayload = "{\"isWalletFunding\":true}"))
    private fun agent() = SpendingAnalysisAgent(month, rows, today)
    private fun dates(from: String = "2025-10", through: String = "2026-10") = JSONObject().put("from", from).put("through", through)
    private fun answer() = JSONObject().put("role", "assistant").put("content",
        """{"findings":["Recorded groceries recur."],"recommendations":["Review the next grocery shop."]}""")
    private fun call(name: String, args: String = dates().toString()) = JSONObject().put("role", "assistant")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "query").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args))))

    @Test fun agentChoosesDatesAcrossAllSavedHistoryWithoutAUserWindow() {
        val subject = agent()
        assertEquals("2023-01", subject.overview.getString("availableFrom"))
        assertEquals(6, subject.overview.getInt("recordedPaymentCount"))
        val recent = subject.inspect("largest_payments", dates("2026-08"))
        assertEquals(2, recent.getInt("recordedPaymentCount"))
        val older = subject.inspect("largest_payments", dates("2023-01", "2023-12"))
        assertEquals(2, older.getInt("recordedPaymentCount"))
        assertEquals(4, subject.inspect("largest_payments", dates()).getInt("recordedPaymentCount"))
        assertTrue(subject.inspect("monthly_trends", dates("2023-01")).has("error"))
        assertTrue(subject.inspect("monthly_trends", dates("2026-10", "2025-01")).has("error"))
        assertTrue(subject.inspect("monthly_trends", dates("2026-01", "2027-01")).has("error"))
        assertFalse(older.toString().contains("never-send"))
        assertFalse(older.toString().contains("older"))
        val months = subject.inspect("monthly_trends", dates("2026-08")).getJSONArray("months")
        assertTrue(months.getJSONObject(2).getBoolean("partial"))
        assertFalse(months.getJSONObject(1).getBoolean("hasRecordedPayments"))
    }

    @Test fun agentChoosesFollowupAndReviewsBeforePublishing() = runBlocking {
        val stages = mutableListOf<AnalysisStage>()
        var requests = 0
        val result = agent().run("", "", stages::add) { messages, tools, choice ->
            when (requests++) {
                0 -> { assertNotNull(tools); assertEquals("required", choice); call("category_details", dates("2026-08").put("category", "Groceries").toString()) }
                1 -> {
                    val tool = messages.getJSONObject(messages.length() - 1)
                    assertEquals("tool", tool.getString("role"))
                    assertEquals(2, JSONObject(tool.getString("content")).getInt("recordedPaymentCount"))
                    call("merchant_details", dates("2023-01", "2023-12").put("merchant", "Fictional merchant").toString())
                }
                2 -> {
                    val result = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                    assertEquals("2023-01", result.getString("from"))
                    assertEquals(2, result.getInt("recordedPaymentCount"))
                    answer()
                }
                else -> { assertNull(tools); answer() }
            }
        }
        assertEquals(4, requests)
        assertTrue(stages.containsAll(listOf(AnalysisStage.CATEGORIES, AnalysisStage.MERCHANTS, AnalysisStage.REVIEWING)))
        assertTrue(result.monthlySummary.contains("### Recommendations\n- Review"))
    }

    @Test fun unsupportedToolsAndArgumentsCannotAccessOrEditRecords() {
        val subject = agent()
        assertTrue(subject.inspect("delete_payment", JSONObject()).has("error"))
        assertTrue(subject.inspect("merchant_details", JSONObject().put("merchant", "Fictional merchant").put("sql", "anything")).has("error"))
        assertEquals(0, subject.inspect("category_details", dates().put("category", "Missing")).getInt("recordedPaymentCount"))
        assertEquals(4, subject.inspect("largest_payments", dates()).getInt("recordedPaymentCount"))
    }

    @Test fun nonAgenticReplyAndEndlessToolLoopNeverPublish() = runBlocking {
        assertTrue(runCatching { agent().run("", "", {}) { _, _, _ -> answer() } }.isFailure)
        var count = 0
        assertTrue(runCatching { agent().run("", "", {}) { _, _, _ -> count++; call("monthly_trends") } }.isFailure)
        assertEquals(5, count)
    }

    @Test fun cancellationDuringFollowupCannotPublishADraft() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        var count = 0
        var published = false
        val job = launch {
            agent().run("", "", {}) { _, _, _ ->
                if (count++ == 0) call("largest_payments")
                else { waiting.complete(Unit); awaitCancellation() }
            }
            published = true
        }
        withTimeout(2000) { waiting.await(); job.cancelAndJoin() }
        assertFalse(published)
    }

    @Test fun modelListMarkersAreNormalizedBeforeNativeRendering() {
        val result = AiAnalysisService.parseCommentary("""{"findings":["* First finding","1. Second finding"],"recommendations":["- Review spending"]}""")
        assertEquals("- First finding\n- Second finding\n\n### Recommendations\n- Review spending", result.monthlySummary)
    }

    @Test fun malformedFindingsCannotBecomeSuccessfulAnalysis() {
        listOf("""{"findings":[],"recommendations":[]}""", """{"findings":[1],"recommendations":[]}""",
            """{"findings":["a\nb"],"recommendations":[]}""").forEach { raw ->
            assertTrue(runCatching { AiAnalysisService.parseCommentary(raw) }.isFailure)
        }
    }
}
