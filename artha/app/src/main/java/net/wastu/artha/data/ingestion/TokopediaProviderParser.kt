package net.wastu.artha.data.ingestion

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.Reader
import java.util.Base64
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject

/** Contracts observed in Tokopedia 45.0.5 APK and its cached Lynx order-list bundle. */
internal object TokopediaProviderParser {
    data class Page(val cards: JSONArray, val hasMore: Boolean, val nextCursor: Any?)

    fun sessionToken(snapshot: ByteArray?): String {
        if (snapshot == null) throw ProviderReadException(AccountStatus.NEEDS_LOGIN,
            "Tokopedia session is missing. Open Tokopedia and log in.")
        if (snapshot.size > 512_000) unsupportedSession()
        if (!snapshot.toString(Charsets.ISO_8859_1).contains("ACCESS_TOKEN_v2")) unsupportedSession()
        // The access token on the audited phone is a five-part JWE, not a decoded
        // JWT. Never use the opaque refresh token as an access token.
        val tokens = decryptedStrings(snapshot).filter { value ->
            value.startsWith("eyJ") && value.split('.').let { parts ->
                parts.size in setOf(3, 5) && parts.all { it.matches(Regex("[A-Za-z0-9_-]*")) }
            }
        }.distinct()
        if (tokens.isEmpty()) throw ProviderReadException(AccountStatus.NEEDS_LOGIN,
            "Tokopedia session is unavailable. Open Tokopedia and log in.")
        if (tokens.size != 1) unsupportedSession()
        return tokens.single()
    }

    fun sessionId(snapshot: ByteArray?): String {
        if (snapshot == null || snapshot.size > 512_000 ||
            !snapshot.toString(Charsets.ISO_8859_1).contains("gcm_id_v2")) unsupportedSession()
        val values = decryptedStrings(snapshot).filter { it.isNotBlank() }.distinct()
        if (values.size != 1 || values.single().any { it == '\r' || it == '\n' }) unsupportedSession()
        return values.single()
    }

    private fun decryptedStrings(snapshot: ByteArray): List<String> {
        if (snapshot.size > 512_000) unsupportedSession()
        val text = snapshot.toString(Charsets.ISO_8859_1)
        return Regex("[A-Za-z0-9+/=\\n]{40,}").findAll(text).mapNotNull { match ->
            runCatching {
                val bytes = Base64.getMimeDecoder().decode(match.value)
                if (bytes.isEmpty() || bytes.size % 16 != 0) return@runCatching null
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE,
                    SecretKeySpec("ggggtttttujkrrrr".toByteArray(Charsets.UTF_8), "AES"),
                    IvParameterSpec("tokopedia1234567".toByteArray(Charsets.UTF_8)))
                String(cipher.doFinal(bytes), Charsets.UTF_8)
            }.getOrNull()
        }.toList()
    }

    private fun unsupportedSession(): Nothing = throw ProviderReadException(AccountStatus.ERROR,
        "Tokopedia session format is unsupported. Saved history is kept.")

    /** ttnetCookieStore uses hex Java serialization; only decode the audited primitive/string layout. */
    fun nativeCookies(snapshot: ByteArray?, nowMillis: Long = System.currentTimeMillis()): String {
        if (snapshot == null) return ""
        try {
            require(snapshot.size <= 512_000)
            val xml = snapshot.toString(Charsets.UTF_8)
            require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(snapshot))
            val entries = document.getElementsByTagName("string")
            val cookies = mutableMapOf<String, String>()
            for (index in 0 until entries.length) {
                val entry = entries.item(index)
                val key = entry.attributes.getNamedItem("name")?.nodeValue.orEmpty()
                if (!key.startsWith("http://tiktokv.com/|")) continue
                val name = key.substringAfter('|')
                require(name.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                val hex = entry.textContent
                require(hex.length in 2..65_536 && hex.length % 2 == 0)
                val bytes = ByteArray(hex.length / 2) { offset ->
                    hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte()
                }
                val cookie = nativeCookie(bytes, name)
                require(cookie.value.all { it.code in 0x21..0x7e && it !in ";," })
                if (cookie.value.isNotEmpty() && cookie.appliesToHistory(nowMillis)) cookies[name] = cookie.value
            }
            return cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
                .also { require(it.length <= 32_768) }
        } catch (_: Exception) {
            throw ProviderReadException(AccountStatus.ERROR,
                "Tokopedia native cookie format is unsupported. Saved history is kept.")
        }
    }

    private data class NativeCookie(
        val value: String, val domain: String?, val path: String?, val ports: String?,
        val maxAge: Long, val createdMillis: Long
    ) {
        fun appliesToHistory(nowMillis: Long): Boolean {
            require(nowMillis >= 0 && createdMillis >= 0)
            // X.Um1.hasExpired uses elapsed whole seconds and a strict > comparison.
            if (maxAge != -1L && (nowMillis - createdMillis) / 1_000 > maxAge) return false
            val scope = domain?.lowercase(Locale.US)?.removePrefix(".") ?: return false
            val host = "oec-api.tiktokv.com"
            if (scope != "tiktokv.com" && scope != host) return false
            val cookiePath = path ?: "/"
            val requestPath = "/api/v1/trade/bff/order_list/get"
            if (!cookiePath.startsWith('/') || !(requestPath == cookiePath ||
                    requestPath.startsWith(if (cookiePath.endsWith('/')) cookiePath else "$cookiePath/"))) return false
            return ports == null || ports.split(',').any { it.trim() == "443" }
        }
    }

    /** Exact X.Um1.writeObject layout; never instantiate serialized source classes. */
    private fun nativeCookie(bytes: ByteArray, expectedName: String): NativeCookie =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0xaced0005.toInt())
            require(input.readUnsignedByte() == 0x73 && input.readUnsignedByte() == 0x72)
            require(input.readUTF() == "X.Um1")
            require(input.readLong() == 6374381323722046732L)
            require(input.readUnsignedByte() == 3 && input.readUnsignedShort() == 1)
            require(input.readUnsignedByte() == 'J'.code && input.readUTF() == "LLFFF")
            require(input.readUnsignedByte() == 0x78 && input.readUnsignedByte() == 0x70)
            // Class descriptor and object occupy handles 0x7e0000 and 0x7e0001.
            val strings = mutableListOf<String>()
            fun string(): String? = when (input.readUnsignedByte()) {
                0x70 -> null
                0x74 -> input.readUTF().also { strings.add(it) }
                0x71 -> strings.getOrNull(input.readInt() - 0x7e0002).also { require(it != null) }
                else -> throw IllegalArgumentException()
            }
            fun block(size: Int): DataInputStream {
                // The audited serializer emits separate 8-byte and 15-byte primitive blocks.
                require(input.readUnsignedByte() == 0x77 && input.readUnsignedByte() == size)
                return DataInputStream(ByteArrayInputStream(ByteArray(size).also { input.readFully(it) }))
            }
            require(string() == expectedName)
            val value = string() ?: throw IllegalArgumentException()
            string() // comment
            string() // comment URL
            val domain = string()
            val maxAge = block(8).use { it.readLong() }
            val path = string()
            val ports = string()
            val createdMillis = block(15).use { tail ->
                require(tail.readInt() in 0..1) // cookie version
                repeat(3) { require(tail.readUnsignedByte() in 0..1) } // secure, discard, HttpOnly
                // The destination is always HTTPS, so both secure and ordinary cookies apply.
                tail.readLong()
            }
            require(input.readUnsignedByte() == 0x78 && input.read() == -1)
            NativeCookie(value, domain, path, ports, maxAge, createdMillis)
        }

    fun request(cursor: Any? = null): JSONObject = JSONObject()
        .put("fe_version", "2").put("page_size", 10).put("filter_info", JSONObject())
        .put("request_type", if (cursor == null) "init_data" else "load_more_data")
        .apply { if (cursor != null) put("next_cursor", cursor) }

    fun requireResponse(httpCode: Int, loginState: String?, verification: String?, parameters: String?) {
        // Live reads returned HTTP 200/code 10000 with these verification headers.
        // Do not turn a verification challenge into an expired-login diagnosis.
        if (!verification.isNullOrBlank() || !parameters.isNullOrBlank()) throw ProviderReadException(AccountStatus.ERROR,
            "Tokopedia blocked this reader with a verification challenge. Its own history screen may still work. Saved history is kept.")
        // The APK treats Tokopro-Toko-Login: 0 as server not logged in.
        if (loginState?.trim() == "0" || httpCode == 401) throw ProviderReadException(AccountStatus.NEEDS_LOGIN,
            "Tokopedia login is unavailable. Open Tokopedia and log in again.")
        if (httpCode !in 200..299) throw ProviderReadException(AccountStatus.ERROR,
            "Tokopedia history request failed (HTTP $httpCode). Saved history is kept.")
    }

    fun response(reader: Reader, checkActive: () -> Unit): JSONObject {
        val buffer = CharArray(8_192)
        val text = StringBuilder()
        while (true) {
            checkActive()
            val size = reader.read(buffer)
            checkActive()
            if (size < 0) break
            if (text.length + size > 2_000_000) throw ProviderReadException(AccountStatus.ERROR,
                "Tokopedia history response is too large. Saved history is kept.")
            text.append(buffer, 0, size)
        }
        return try { JSONObject(text.toString()) } catch (_: org.json.JSONException) {
            throw ProviderReadException(AccountStatus.ERROR,
                "Tokopedia returned an unsupported history response. Saved history is kept.")
        }
    }

    fun page(response: JSONObject): Page {
        val code = response.opt("code")
        if (code !is Number || code.toString().toLongOrNull() == null) unsupportedPage()
        if (code.toLong() != 0L) throw ProviderReadException(AccountStatus.ERROR,
            "Tokopedia rejected history (provider code ${code.toLong()}). Request authentication or verification is required. Saved history is kept.")
        val data = response.optJSONObject("data") ?: unsupportedPage()
        val cards = data.optJSONObject("facade")?.optJSONArray("masonry") ?: unsupportedPage()
        val global = data.optJSONObject("global") ?: unsupportedPage()
        val more = global.opt("has_more") as? Boolean ?: unsupportedPage()
        val cursor = global.opt("next_cursor").takeUnless { it == JSONObject.NULL }
        if (more && (cursor == null ||
                (cursor !is String && (cursor !is Number || cursor.toString().toLongOrNull() == null)) ||
                cursor.toString().isBlank() || cursor.toString().length > 2_048)) unsupportedPage()
        return Page(cards, more, cursor)
    }

    fun requireVerifiedPayments(page: Page) {
        if (page.cards.length() > 0) throw ProviderReadException(AccountStatus.ERROR,
            "Tokopedia returned order presentation cards; settled payment and refund fields are not yet verified. Saved history is kept.")
    }

    private fun unsupportedPage(): Nothing = throw ProviderReadException(AccountStatus.ERROR,
        "Tokopedia history schema is unsupported. Saved history is kept.")
}
