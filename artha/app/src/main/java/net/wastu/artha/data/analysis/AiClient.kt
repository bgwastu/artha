package net.wastu.artha.data.analysis

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import net.wastu.artha.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** [detail] is the provider's own short reason (for example "Request too large"), kept for the activity log. */
internal class AiHttpException(val status: Int, val detail: String? = null) : IOException("AI service returned HTTP $status")

internal fun aiConnectionFailure(error: Exception): String = when (error) {
    is AiHttpException -> when (error.status) {
        401, 403 -> "Check the API key"
        400, 404 -> "Check the URL and model"
        429 -> "Rate limited. Try again"
        else -> "Provider unavailable. Try again"
    }
    is SocketTimeoutException -> "Connection timed out. Try again"
    is IOException -> "Couldn’t connect. Try again"
    is org.json.JSONException, is IllegalArgumentException, is IllegalStateException -> "The model returned an unusable reply. Try again"
    else -> "Test failed. Try again"
}

internal fun aiConfigValidation(config: AiConfig): String? = when {
    config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank() -> "Enter the URL, API key and model"
    runCatching { AiClient.endpoint(config.baseUrl) }.isFailure -> "Enter a valid provider URL"
    else -> null
}

/** OpenAI-compatible chat client used for categorization and for listing a provider's models. */
object AiClient {
    private val disconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val whitespace = Regex("\\s+")

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

    /** The OpenAI-style model list that sits next to the chat endpoint. */
    internal fun modelsEndpoint(baseUrl: String): String =
        endpoint(baseUrl).removeSuffix("/chat/completions") + "/models"

    /** The `error.message` of a provider's JSON error body, on one line and short. Anything else is dropped. */
    internal fun errorDetail(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val error = runCatching { JSONObject(body).opt("error") }.getOrNull()
        val message = when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> ""
        }
        return message.replace(whitespace, " ").trim().take(160).ifEmpty { null }
    }

    /** The response text, refusing anything longer than [limit] characters. */
    private fun HttpURLConnection.readBody(limit: Int, what: String): String =
        inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(text.length + count <= limit) { "$what is too large" }
                text.append(buffer, 0, count)
            }
            text.toString()
        }

    private fun httpFailure(connection: HttpURLConnection): AiHttpException {
        val body = runCatching {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(8192)
                val count = reader.read(buffer)
                if (count > 0) String(buffer, 0, count) else null
            }
        }.getOrNull()
        return AiHttpException(connection.responseCode, errorDetail(body))
    }

    private val notChatModels = listOf("embed", "whisper", "tts", "transcribe", "moderation", "guard", "image", "imagen", "veo", "dall-e", "rerank")

    /** Model ids from a `{"data":[{"id":...}]}` reply: chat-capable ones only (by name), without Gemini's `models/` prefix, sorted. */
    internal fun parseModelIds(raw: String): List<String> {
        val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
        return (0 until data.length())
            .mapNotNull { data.optJSONObject(it)?.optString("id")?.removePrefix("models/")?.takeIf(String::isNotBlank) }
            .filter { id -> notChatModels.none { id.contains(it, ignoreCase = true) } }
            .distinct()
            .sorted()
    }

    /** What the provider offers for this key. Only the key travels; no ledger data is sent. */
    suspend fun listModels(config: AiConfig): List<String> {
        if (BuildConfig.DEMO) return emptyList()
        return withContext(Dispatchers.IO) {
            val connection = URL(modelsEndpoint(config.baseUrl)).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                if (connection.responseCode !in 200..299) throw httpFailure(connection)
                parseModelIds(connection.readBody(limit = 5_000_000, what = "Model list"))
            } finally { connection.disconnect() }
        }
    }

    internal suspend fun chat(
        config: AiConfig,
        messages: JSONArray,
        tools: JSONArray? = null,
        toolChoice: String? = null,
        responseFormat: JSONObject? = null,
        readTimeoutMs: Int = 30_000,
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val connection = URL(endpoint(config.baseUrl)).openConnection() as HttpURLConnection
        continuation.invokeOnCancellation { disconnectScope.launch { connection.disconnect() } }
        // HttpURLConnection may block inside disconnect on some runtimes. Keep transport
        // work independent so cancellation immediately discards obsolete replies.
        disconnectScope.launch {
            if (!continuation.isActive) { connection.disconnect(); return@launch }
            try {
                connection.requestMethod = "POST"; connection.connectTimeout = 15_000; connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("model", config.model).put("messages", messages)
                if (tools != null) body.put("tools", tools).put("tool_choice", toolChoice ?: "auto")
                if (responseFormat != null) body.put("response_format", responseFormat)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) throw httpFailure(connection)
                val json = JSONObject(connection.readBody(limit = 512_000, what = "AI response"))
                val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                if (continuation.isActive) continuation.resume(content)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            finally { connection.disconnect() }
        }
    }
}
