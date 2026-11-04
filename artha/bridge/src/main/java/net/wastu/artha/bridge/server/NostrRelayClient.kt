package net.wastu.artha.bridge.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import net.wastu.artha.bridge.crypto.Bip340

class NostrRelayClient(
    private val client: HttpClient = newHttpClient(),
) {
    suspend fun publish(
        relayUrl: String,
        channelId: String,
        encryptedContent: String,
        seckey: ByteArray,
        slot: String,
        timeoutMs: Long = 10_000,
    ): Boolean = withContext(Dispatchers.IO) {
        val eventObj = buildNostrEvent("$channelId:$slot", channelId, encryptedContent, seckey)
        val eventMsg = JSONArray().apply {
            put("EVENT")
            put(eventObj)
        }.toString()

        val success = withTimeoutOrNull(timeoutMs) {
            runCatching {
                var ok = false
                client.webSocket(urlString = relayUrl) {
                    send(Frame.Text(eventMsg))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val arr = runCatching { JSONArray(text) }.getOrNull() ?: continue
                            if (arr.length() >= 3 && arr.getString(0) == "OK") {
                                ok = arr.getBoolean(2)
                                if (!ok) android.util.Log.w("ArthaBridge", "$relayUrl rejected: ${arr.optString(3)}")
                                close()
                                break
                            }
                        }
                    }
                }
                ok
            }.onFailure { android.util.Log.w("ArthaBridge", "$relayUrl publish error: $it") }.getOrDefault(false)
        }
        success ?: false
    }

    /**
     * Sends [events] (slot to content) one after another over a single connection, since
     * relays throttle new connections far sooner than events. Returns the slots accepted.
     */
    suspend fun publishAll(
        relayUrl: String,
        channelId: String,
        events: List<Pair<String, String>>,
        seckey: ByteArray,
        timeoutMs: Long = 90_000,
    ): Set<String> = withContext(Dispatchers.IO) {
        val accepted = mutableSetOf<String>()
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                client.webSocket(urlString = relayUrl) {
                    for ((slot, content) in events) {
                        val event = buildNostrEvent("$channelId:$slot", channelId, content, seckey)
                        send(Frame.Text(JSONArray().put("EVENT").put(event).toString()))
                        val id = event.getString("id")
                        for (frame in incoming) {
                            val arr = (frame as? Frame.Text)?.let { runCatching { JSONArray(it.readText()) }.getOrNull() } ?: continue
                            if (arr.length() >= 3 && arr.getString(0) == "OK" && arr.getString(1) == id) {
                                if (arr.getBoolean(2)) accepted += slot
                                else android.util.Log.w("ArthaBridge", "$relayUrl rejected $slot: ${arr.optString(3)}")
                                break
                            }
                        }
                    }
                    close()
                }
            }.onFailure { android.util.Log.w("ArthaBridge", "$relayUrl batch publish error: $it") }
        }
        accepted
    }

    suspend fun fetch(
        relayUrl: String,
        channelId: String,
        seckey: ByteArray,
        limit: Int = 200,
        timeoutMs: Long = 10_000,
    ): List<String> = withContext(Dispatchers.IO) {
        val subId = "sub_" + UUID.randomUUID().toString().take(8)
        // Every message is signed with the pairing key, so its pubkey finds all of them.
        val filter = JSONObject().apply {
            put("kinds", JSONArray(listOf(30078)))
            put("authors", JSONArray(listOf(pubkeyHex(seckey))))
            put("limit", limit)
        }
        val reqMsg = JSONArray().apply {
            put("REQ")
            put(subId)
            put(filter)
        }.toString()

        val results = mutableListOf<String>()
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                client.webSocket(urlString = relayUrl) {
                    send(Frame.Text(reqMsg))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val arr = runCatching { JSONArray(text) }.getOrNull() ?: continue
                            when (arr.optString(0)) {
                                "EVENT" -> {
                                    if (arr.length() >= 3) {
                                        val ev = arr.getJSONObject(2)
                                        if (dTag(ev).startsWith(channelId)) results.add(ev.getString("content"))
                                    }
                                }
                                "EOSE" -> {
                                    close()
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }
        results
    }

    /**
     * Keeps a subscription open on [relayUrl] and calls [onEvent] with the d tag of
     * every new event signed with the pairing key. Returns when the connection drops.
     */
    suspend fun listen(
        relayUrl: String,
        seckey: ByteArray,
        onEvent: (dTag: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val subId = "live_" + UUID.randomUUID().toString().take(8)
        val filter = JSONObject().apply {
            put("kinds", JSONArray(listOf(30078)))
            put("authors", JSONArray(listOf(pubkeyHex(seckey))))
            put("since", System.currentTimeMillis() / 1000)
        }
        val reqMsg = JSONArray().apply {
            put("REQ")
            put(subId)
            put(filter)
        }.toString()
        client.webSocket(urlString = relayUrl) {
            send(Frame.Text(reqMsg))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val arr = runCatching { JSONArray(frame.readText()) }.getOrNull() ?: continue
                if (arr.optString(0) == "EVENT" && arr.length() >= 3) onEvent(dTag(arr.getJSONObject(2)))
            }
        }
    }

    /** A JSON string escaped exactly as NIP-01 specifies. */
    private fun nostrString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun pubkeyHex(seckey: ByteArray): String =
        Bip340.pubkeyGen(seckey).joinToString("") { "%02x".format(it) }

    private fun dTag(event: JSONObject): String {
        val tags = event.optJSONArray("tags") ?: return ""
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            if (tag.optString(0) == "d") return tag.optString(1)
        }
        return ""
    }

    /**
     * Kind 30078 is replaceable per (pubkey, d), so [dTag] gives each message its own
     * slot; a shared d tag would let every message overwrite the previous one.
     */
    private fun buildNostrEvent(dTag: String, channelId: String, content: String, seckey: ByteArray): JSONObject {
        val now = System.currentTimeMillis() / 1000
        val pubkey = pubkeyHex(seckey)
        val tags = JSONArray().apply {
            put(JSONArray().apply {
                put("d")
                put(dTag)
            })
            put(JSONArray().apply {
                put("p")
                put(channelId)
            })
            put(JSONArray().apply {
                put("expiration")
                put((now + 7 * 86400).toString())
            })
        }

        // NIP-01 hashes an exact serialization; org.json escapes "/" and would change the id.
        val tagsJson = (0 until tags.length()).joinToString(",", "[", "]") { i ->
            val tag = tags.getJSONArray(i)
            (0 until tag.length()).joinToString(",", "[", "]") { j -> nostrString(tag.getString(j)) }
        }
        val serialization = "[0,${nostrString(pubkey)},$now,30078,$tagsJson,${nostrString(content)}]"

        val msgBytes = MessageDigest.getInstance("SHA-256").digest(serialization.toByteArray(Charsets.UTF_8))
        val id = msgBytes.joinToString("") { "%02x".format(it) }
        val sigBytes = Bip340.sign(msgBytes, seckey)
        val sig = sigBytes.joinToString("") { "%02x".format(it) }

        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubkey)
            put("created_at", now)
            put("kind", 30078)
            put("tags", tags)
            put("content", content)
            put("sig", sig)
        }
    }

    companion object {
        fun newHttpClient(): HttpClient = HttpClient(CIO) {
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
        }
    }
}
