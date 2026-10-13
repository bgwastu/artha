package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.Direction
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MonthAnalysisResult(
    val monthlySummary: String,
    val classifications: Map<String, Pair<String, String>>,
)

data class FeedbackResult(
    val category: String,
    val reason: String,
    val rewrittenKnowledge: String,
)

object AiAnalysisService {
    suspend fun analyzeMonth(
        config: AiConfig,
        knowledge: String,
        monthLabel: String,
        transactions: List<UnifiedTransaction>,
        availableCategories: List<String> = INITIAL_DEFAULT_CATEGORIES,
        monthlyUserNote: String = "",
    ): MonthAnalysisResult = withContext(Dispatchers.IO) {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta"))
        val dayOfMonth = today.dayOfMonth
        val daysInMonth = today.lengthOfMonth()
        val remainingDays = daysInMonth - dayOfMonth
        val yearInt = monthLabel.substringBefore("-").toIntOrNull() ?: today.year
        val monthInt = monthLabel.substringAfter("-").toIntOrNull() ?: today.monthValue
        val isCurrentMonth = today.year == yearInt && today.monthValue == monthInt
        val dateContext = if (isCurrentMonth) {
            "Today is $today (Day $dayOfMonth of $daysInMonth in $monthLabel, with $remainingDays days remaining in this month)."
        } else {
            "Today is $today. Note that $monthLabel is a past completed month."
        }

        val expensesOnly = transactions.filter {
            it.direction == Direction.DEBIT &&
            it.category != CategoryConstants.INVESTMENTS_TRANSFERS &&
            !it.isReversal
        }.take(35)

        val txArray = JSONArray()
        for (tx in expensesOnly) {
            txArray.put(
                JSONObject()
                    .put("id", tx.key)
                    .put("title", tx.description)
                    .put("provider", tx.provider.displayName)
                    .put("amountRupiah", tx.money.amountMinor / 100L)
                    .put("pocket", tx.pocket?.name.orEmpty())
                    .put("userNote", tx.userNote.orEmpty())
                    .put("currentCategory", tx.category.orEmpty())
            )
        }

        val prompt = """
            You are a personal financial advisor.
            Knowledge base:
            $knowledge

            Date context:
            $dateContext

            User's Monthly Note / Spending Context for $monthLabel:
            ${monthlyUserNote.ifBlank { "No special monthly context provided." }}

            Allowed categories:
            ${availableCategories.joinToString(", ")}

            Analyze ONLY personal expenses for $monthLabel:
            $txArray

            Instructions:
            1. Focus EXCLUSIVELY on personal EXPENSES (spending). Ignore income and internal transfers.
            2. Crucially incorporate the User's Monthly Note for $monthLabel to accurately categorize each transaction and understand what the user spent money on this month.
            3. In "monthlySummary", provide a bulleted list (each item on a new line starting with "- ") of 2 to 4 key expense highlights, category drivers, and trends for $monthLabel.
            4. In "recommendations", provide a bulleted list (each item on a new line starting with "- ") of 2 to 3 actionable, pragmatic financial recommendations. Tailor your recommendations to the current date context ($dateContext): if $monthLabel is ongoing, advise on daily spending limits and budget pacing for the remaining $remainingDays days; if it is a past month, give retrospective takeaways and budget adjustments.

            Return ONLY valid JSON with this structure (no markdown code fences):
            {
              "monthlySummary": "- First key expense highlight...\n- Second expense highlight...",
              "recommendations": "- First actionable tip based on current date...\n- Second actionable tip...",
              "items": [
                {
                  "id": "<transaction id>",
                  "category": "<exact category from allowed list>"
                }
              ]
            }
        """.trimIndent()

        val rawResponse = callChatCompletion(config, prompt)
        val parsed = extractJsonObject(rawResponse)
        val summary = parsed.optString("monthlySummary", "Analysis completed for $monthLabel.")
        val recs = parsed.optString("recommendations").trim().takeIf { it.isNotBlank() }
        val fullSummary = if (recs != null) "$summary\n\nRECOMMENDATIONS:\n$recs" else summary
        val items = parsed.optJSONArray("items") ?: JSONArray()
        val map = mutableMapOf<String, Pair<String, String>>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val cat = obj.optString("category")
            val reason = obj.optString("reason")
            if (id.isNotBlank() && cat in availableCategories) {
                map[id] = cat to reason
            }
        }
        MonthAnalysisResult(fullSummary, map)
    }

    suspend fun submitFeedback(
        config: AiConfig,
        currentKnowledge: String,
        transaction: UnifiedTransaction,
        userFeedback: String,
        availableCategories: List<String> = INITIAL_DEFAULT_CATEGORIES,
    ): FeedbackResult = withContext(Dispatchers.IO) {
        val prompt = """
            You are a personal financial assistant maintaining a clean markdown Knowledge base for transaction classification.

            Current Knowledge markdown list:
            $currentKnowledge

            Allowed categories:
            ${availableCategories.joinToString(", ")}

            Transaction being corrected:
            - Title: ${transaction.description}
            - Provider: ${transaction.provider.displayName}
            - Amount: Rp${transaction.money.amountMinor / 100L}
            - Pocket: ${transaction.pocket?.name ?: "None"}
            - User Note: ${transaction.userNote ?: "None"}
            - Current Category: ${transaction.category ?: "Uncategorized"}

            User Feedback / Correction:
            "$userFeedback"

            Tasks:
            1. Assign the most accurate category from the Allowed categories list based on the user's feedback.
            2. Write a single short paragraph explaining why this transaction belongs to that category.
            3. Rewrite the ENTIRE Knowledge markdown document as a clean, simple, deduplicated markdown bullet list (`- ...`) incorporating this new rule or refinement seamlessly.

            Return ONLY valid JSON with this structure (no markdown code fences):
            {
              "category": "<exact category from allowed list>",
              "reason": "<single short paragraph>",
              "rewrittenKnowledge": "<full rewritten markdown bullet list>"
            }
        """.trimIndent()

        val rawResponse = callChatCompletion(config, prompt)
        val parsed = extractJsonObject(rawResponse)
        val cat = parsed.optString("category").takeIf { it in availableCategories }
            ?: transaction.category
            ?: "Uncategorized"
        val reason = parsed.optString("reason").ifBlank {
            "Updated based on your feedback: $userFeedback"
        }
        val rewritten = parsed.optString("rewrittenKnowledge").ifBlank {
            "$currentKnowledge\n- ${transaction.description}: $userFeedback -> $cat"
        }
        FeedbackResult(cat, reason, rewritten.trim())
    }

    private fun callChatCompletion(config: AiConfig, prompt: String): String {
        val endpoint = if (config.baseUrl.endsWith("/chat/completions")) config.baseUrl
        else "${config.baseUrl.trimEnd('/')}/v1/chat/completions"

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json")

            val messages = JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", prompt)
            )
            val body = JSONObject()
                .put("model", config.model)
                .put("messages", messages)
                .toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "AI service returned HTTP ${connection.responseCode}" }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseText)
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            connection.disconnect()
        }
    }

    private fun extractJsonObject(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val slice = raw.substring(start, end + 1)
            runCatching { return JSONObject(slice) }
        }
        return JSONObject()
    }
}
