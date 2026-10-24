package net.wastu.artha.data.analysis

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

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
    runCatching { AiClient.endpoint(config.baseUrl) }.isFailure -> "Enter a valid provider URL"
    else -> null
}

/** OpenAI-compatible chat client shared by categorization and the connection test. */
object AiClient {
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

    internal suspend fun call(config: AiConfig, prompt: String): String =
        chat(config, JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).getString("content")

    internal suspend fun chat(config: AiConfig, messages: JSONArray, tools: JSONArray? = null, toolChoice: String? = null,
        readTimeoutMs: Int = 30_000): JSONObject = suspendCancellableCoroutine { continuation ->
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
