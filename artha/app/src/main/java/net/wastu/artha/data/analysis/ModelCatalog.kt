package net.wastu.artha.data.analysis

import java.io.File
import java.io.Reader
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.artha.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** What models.dev knows about one model. Prices are USD per million tokens; null means unknown. */
data class ModelInfo(
    val inputPerM: Double?,
    val outputPerM: Double?,
    val context: Long?,
    val structuredOutput: Boolean,
    val deprecated: Boolean,
) {
    val free get() = inputPerM == 0.0 && outputPerM == 0.0
}

/**
 * Optional extra from the community catalog at models.dev: prices, context size and deprecation for the models a provider lists.
 * It never decides what the provider offers, so when it is unreachable or changes shape the model picker just has less to show.
 * Only the few providers the app offers are kept from the 6 MB file, cached on disk and refreshed daily with its ETag.
 */
object ModelCatalog {
    private const val CATALOG_URL = "https://models.dev/api.json"
    private const val MAX_CHARS = 30_000_000
    private const val FRESH_MS = 24L * 60 * 60 * 1000

    /** Cached data first, then a fresh copy if the cache is missing or a day old. Never throws except on cancellation. */
    suspend fun forProvider(cache: File, providerId: String, onUpdate: (Map<String, ModelInfo>) -> Unit) {
        if (BuildConfig.DEMO) return
        val wanted = AiProviders.presets.map { it.catalogId }.toSet()
        try {
            val saved = withContext(Dispatchers.IO) { read(cache) }
            saved?.models?.get(providerId)?.takeIf { it.isNotEmpty() }?.let(onUpdate)
            val complete = saved != null && saved.models.keys.containsAll(wanted)
            if (complete && System.currentTimeMillis() - saved!!.at < FRESH_MS) return
            val fresh = withContext(Dispatchers.IO) { download(cache, if (complete) saved else null, wanted) }
            fresh.models[providerId]?.takeIf { it.isNotEmpty() }?.let(onUpdate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Prices are a nicety; the provider's own list still works.
        }
    }

    internal class Saved(val etag: String?, val at: Long, val models: Map<String, Map<String, ModelInfo>>)

    private fun download(cache: File, saved: Saved?, wanted: Set<String>): Saved {
        val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            saved?.etag?.let { connection.setRequestProperty("If-None-Match", it) }
            val result = when (connection.responseCode) {
                304 -> Saved(saved?.etag, System.currentTimeMillis(), saved?.models.orEmpty())
                200 -> {
                    val extracted = connection.inputStream.bufferedReader(Charsets.UTF_8).use { extractProviders(it, wanted) }
                    Saved(connection.getHeaderField("ETag"), System.currentTimeMillis(), parse(extracted, wanted))
                }
                else -> throw AiHttpException(connection.responseCode)
            }
            cache.writeText(encode(result))
            return result
        } finally { connection.disconnect() }
    }

    /**
     * The text of the top-level objects named in [wanted], found by scanning without building the whole document.
     * The file is `{"provider": {...}, ...}` and almost all of it belongs to providers the app never shows.
     */
    internal fun extractProviders(reader: Reader, wanted: Set<String>): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        var depth = 0
        var inString = false
        var escaped = false
        val token = StringBuilder()
        var lastKey: String? = null
        var capture: StringBuilder? = null
        var captureKey = ""
        var total = 0
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_CHARS) { "Catalog is too large" }
            for (i in 0 until count) {
                val c = buffer[i]
                capture?.append(c)
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> { inString = false; if (depth == 1 && capture == null) lastKey = token.toString() }
                        depth == 1 && capture == null -> token.append(c)
                    }
                    continue
                }
                when (c) {
                    '"' -> { inString = true; if (depth == 1 && capture == null) token.setLength(0) }
                    '{', '[' -> {
                        depth++
                        val key = lastKey
                        if (depth == 2 && capture == null && c == '{' && key != null && key in wanted) {
                            capture = StringBuilder().append(c); captureKey = key
                        }
                    }
                    '}', ']' -> {
                        depth--
                        if (depth == 1 && capture != null) { found[captureKey] = capture.toString(); capture = null }
                    }
                }
            }
        }
        return found
    }

    /** Every wanted provider gets an entry, empty when models.dev doesn't have it, so the cache counts as complete. */
    internal fun parse(extracted: Map<String, String>, wanted: Set<String>): Map<String, Map<String, ModelInfo>> =
        wanted.associateWith { id ->
            val models = extracted[id]?.let { JSONObject(it).optJSONObject("models") } ?: return@associateWith emptyMap()
            buildMap {
                models.keys().forEach { modelId ->
                    val model = models.optJSONObject(modelId) ?: return@forEach
                    val cost = model.optJSONObject("cost")
                    put(
                        modelId,
                        ModelInfo(
                            inputPerM = cost?.takeIf { it.has("input") }?.optDouble("input"),
                            outputPerM = cost?.takeIf { it.has("output") }?.optDouble("output"),
                            context = model.optJSONObject("limit")?.takeIf { it.has("context") }?.optLong("context"),
                            structuredOutput = model.optBoolean("structured_output", false),
                            deprecated = model.optString("status") == "deprecated",
                        ),
                    )
                }
            }
        }

    internal fun encode(saved: Saved): String {
        val models = JSONObject()
        saved.models.forEach { (provider, byId) ->
            val out = JSONObject()
            byId.forEach { (id, m) ->
                out.put(id, JSONArray().put(m.inputPerM ?: JSONObject.NULL).put(m.outputPerM ?: JSONObject.NULL)
                    .put(m.context ?: JSONObject.NULL).put(m.structuredOutput).put(m.deprecated))
            }
            models.put(provider, out)
        }
        return JSONObject().put("etag", saved.etag ?: JSONObject.NULL).put("at", saved.at).put("models", models).toString()
    }

    internal fun decode(raw: String): Saved {
        val root = JSONObject(raw)
        val models = root.getJSONObject("models")
        return Saved(
            etag = root.optString("etag").takeIf { it.isNotEmpty() && !root.isNull("etag") },
            at = root.getLong("at"),
            models = models.keys().asSequence().associateWith { provider ->
                val byId = models.getJSONObject(provider)
                byId.keys().asSequence().associateWith { id ->
                    val a = byId.getJSONArray(id)
                    ModelInfo(
                        inputPerM = a.takeUnless { it.isNull(0) }?.getDouble(0),
                        outputPerM = a.takeUnless { it.isNull(1) }?.getDouble(1),
                        context = a.takeUnless { it.isNull(2) }?.getLong(2),
                        structuredOutput = a.getBoolean(3),
                        deprecated = a.getBoolean(4),
                    )
                }
            },
        )
    }

    private fun read(cache: File): Saved? = runCatching { decode(cache.readText()) }.getOrNull()

    /** The provider's own models minus the ones models.dev marks deprecated. Unknown models stay. */
    fun visible(available: List<String>, info: Map<String, ModelInfo>): List<String> =
        available.filterNot { info[it]?.deprecated == true }

    /** "$0.20 in · $1.20 out per 1M tokens", "Free", or null when the prices are unknown. */
    fun priceLabel(info: ModelInfo?): String? {
        val input = info?.inputPerM ?: return null
        val output = info.outputPerM ?: return null
        if (info.free) return "Free"
        return "$${money(input)} in · $${money(output)} out per 1M tokens"
    }

    private fun money(value: Double): String {
        val trimmed = BigDecimal.valueOf(value).stripTrailingZeros()
        return (if (trimmed.scale() < 2) trimmed.setScale(2) else trimmed).toPlainString()
    }
}
