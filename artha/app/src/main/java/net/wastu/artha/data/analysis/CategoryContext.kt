package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONArray
import org.json.JSONObject

/** Keep financial source fields intact; authentication material is not transaction evidence. */
internal object CategoryContext {
    private val credentialKeys = setOf("authorization", "proxyauthorization", "cookie", "setcookie", "apikey",
        "accesstoken", "refreshtoken", "authtoken", "sessiontoken", "password", "secret", "clientsecret", "privatekey")

    private fun source(value: Any?, depth: Int = 0): Any {
        require(depth <= 128) { "Transaction source data is too deeply nested" }
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().apply {
                for (key in value.keys()) {
                    val normalized = key.lowercase().filter(Char::isLetterOrDigit)
                    put(key, if (normalized in credentialKeys) "[credential redacted]" else {
                        val entry = value.get(key)
                        // Grouped source records store their original JSON as a String.
                        if (normalized == "rawpayload" && entry is String) parsedSource(entry, depth + 1)
                        else source(entry, depth + 1)
                    })
                }
            }
            is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(source(value.get(i), depth + 1)) }
            else -> value
        }
    }

    private fun parsedSource(raw: String, depth: Int = 0): Any {
        val parsed = runCatching {
            val reader = org.json.JSONTokener(raw)
            val value = reader.nextValue()
            if (reader.nextClean() == 0.toChar()) value else null
        }.getOrNull()
        return if (parsed is JSONObject || parsed is JSONArray) source(parsed, depth) else raw
    }

    fun transaction(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): JSONObject = JSONObject()
        .put("key", tx.key).put("provider", tx.provider.name).put("providerName", tx.provider.displayName)
        .put("providerTransactionId", tx.providerTransactionId).put("canonicalKey", tx.canonicalKey ?: JSONObject.NULL)
        .put("description", tx.description).put("direction", tx.direction.name)
        .put("amountMinor", tx.money.amountMinor).put("currency", tx.money.currencyCode)
        .put("occurredAt", tx.occurredAt.toString())
        .put("pocket", tx.pocket?.let { JSONObject().put("id", it.providerPocketId).put("name", it.name) } ?: JSONObject.NULL)
        .put("isQris", tx.isQris).put("cardLast4", tx.cardLast4 ?: JSONObject.NULL)
        .put("userNote", snapshot.userNotes[tx.key] ?: tx.userNote ?: JSONObject.NULL)
        .put("isConfirmed", tx.isConfirmed).put("isReversal", tx.isReversal)
        .put("classificationIsManual", tx.classificationIsManual || tx.key in snapshot.manualCategories)
        .put("currentCategory", tx.category ?: JSONObject.NULL).put("previousAiReason", tx.aiReasoning ?: JSONObject.NULL)
        .put("sourceData", parsedSource(tx.rawPayload))

    /** JSON key order/whitespace do not change the evidence; arrays keep their original order. */
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
