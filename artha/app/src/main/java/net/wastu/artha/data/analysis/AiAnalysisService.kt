package net.wastu.artha.data.analysis

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONArray
import org.json.JSONObject

data class MonthAnalysisResult(val monthlySummary: String)
internal class AiHttpException(val status: Int) : java.io.IOException("AI service returned HTTP $status")
internal class EmptyAiResponseException : IllegalStateException()

internal fun aiConnectionFailure(error: Exception): String = when (error) {
    is AiHttpException -> when (error.status) {
        401, 403 -> "Check the API key"
        400, 404 -> "Check the URL and model"
        429 -> "Rate limited. Try again"
        else -> "Provider unavailable. Try again"
    }
    is java.net.SocketTimeoutException -> "Connection timed out. Try again"
    is java.io.IOException -> "Couldn’t connect. Try again"
    is EmptyAiResponseException -> "The model returned no reply"
    is org.json.JSONException, is IllegalArgumentException, is IllegalStateException -> "The model returned an unusable reply. Try again"
    else -> "Test failed. Try again"
}

internal fun aiConfigValidation(config: AiConfig): String? = when {
    config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank() -> "Enter the URL, API key and model"
    runCatching { AiAnalysisService.endpoint(config.baseUrl) }.isFailure -> "Enter a valid provider URL"
    else -> null
}

/** AI commentary consumes authoritative totals and cannot change ledger facts or categories. */
object AiAnalysisService {
    private val disconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Exercises generation with the draft configuration; no ledger, notes or settings are sent or saved. */
    suspend fun testConnection(config: AiConfig) {
        require(aiConfigValidation(config) == null)
        if (call(config, "Reply only with OK.").isBlank()) throw EmptyAiResponseException()
    }

    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val uri = URI(base)
        require(uri.host != null && uri.userInfo == null) { "Enter a valid AI provider URL." }
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1")) { "AI providers require HTTPS." }
        require(uri.query == null && uri.fragment == null) { "Use the provider base URL without a query or fragment." }
        return when {
            base.endsWith("/chat/completions") -> base
            uri.path.isNullOrBlank() || uri.path == "/" -> "$base/v1/chat/completions"
            else -> "$base/chat/completions"
        }
    }

    fun parseCommentary(raw: String): MonthAnalysisResult {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = try { JSONObject(cleaned) } catch (_: Exception) { error("AI returned invalid JSON. Your previous commentary is kept.") }
        fun items(name: String, maximum: Int): List<String> {
            val array = json.opt(name) as? JSONArray ?: error("AI returned invalid $name.")
            require(array.length() <= maximum) { "AI returned too many $name." }
            return (0 until array.length()).map { index ->
                val value = (array.opt(index) as? String)?.trim() ?: error("AI returned invalid $name.")
                val clean = value.replace(Regex("^(?:[-*+]\\s+|[0-9]+[.)]\\s+|[\\u2022\\u25cf]\\s*)"), "").trim()
                require(clean.isNotEmpty() && clean.length <= 600 && !clean.contains('\n') && !clean.contains("\\n")) { "AI returned invalid $name." }
                clean
            }
        }
        if (json.has("findings")) {
            val findings = items("findings", 3)
            require(findings.isNotEmpty()) { "AI returned no findings." }
            val recommendations = items("recommendations", 2)
            return MonthAnalysisResult(buildString {
                append(findings.joinToString("\n") { "- $it" })
                if (recommendations.isNotEmpty()) {
                    append("\n\n### Recommendations\n")
                    append(recommendations.joinToString("\n") { "- $it" })
                }
            })
        }
        // Keep compatibility with saved responses from older app versions.
        val summary = (json.opt("monthlySummary") as? String)?.trim() ?: error("AI returned an invalid analysis.")
        require(summary.isNotEmpty() && summary.length <= 8000) { "AI returned an empty or oversized analysis." }
        val recommendations = if (json.has("recommendations")) (json.opt("recommendations") as? String)?.trim()
            ?: error("AI returned invalid recommendations.") else ""
        require(recommendations.length <= 8000) { "AI recommendations are too long." }
        return MonthAnalysisResult(if (recommendations.isEmpty()) summary else "$summary\n\n### Recommendations\n$recommendations")
    }

    suspend fun analyzeMonth(config: AiConfig, knowledge: String, monthLabel: String,
        transactions: List<UnifiedTransaction>, availableCategories: List<String> = INITIAL_DEFAULT_CATEGORIES,
        monthlyUserNote: String = "",
        onProgress: (AnalysisStage) -> Unit = {},
    ): MonthAnalysisResult = withTimeout(90_000) {
        onProgress(AnalysisStage.COMPARING)
        val agent = withContext(Dispatchers.Default) {
            SpendingAnalysisAgent(YearMonth.parse(monthLabel), transactions, LocalDate.now(AccountingTime.clock))
        }
        agent.run(knowledge, monthlyUserNote, onProgress) { messages, tools, choice -> chat(config, messages, tools, choice) }
    }

    internal suspend fun call(config: AiConfig, prompt: String): String =
        chat(config, JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).getString("content")

    internal suspend fun chat(config: AiConfig, messages: JSONArray, tools: JSONArray? = null, toolChoice: String? = null): JSONObject = suspendCancellableCoroutine { continuation ->
        val connection = URL(endpoint(config.baseUrl)).openConnection() as HttpURLConnection
        continuation.invokeOnCancellation { disconnectScope.launch { connection.disconnect() } }
        // HttpURLConnection may block inside disconnect on some runtimes. Keep transport
        // work independent so cancellation immediately discards obsolete commentary.
        disconnectScope.launch {
            if (!continuation.isActive) { connection.disconnect(); return@launch }
            try {
                connection.requestMethod = "POST"; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("model", config.model).put("messages", messages)
                if (tools != null) body.put("tools", tools).put("tool_choice", toolChoice ?: "auto")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) throw AiHttpException(connection.responseCode)
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val response = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(response.length + count <= 512_000) { "AI response is too large" }
                        response.append(buffer, 0, count)
                    }
                    JSONObject(response.toString())
                }
                val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                if (continuation.isActive) continuation.resume(content)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            finally { connection.disconnect() }
        }
    }
}
