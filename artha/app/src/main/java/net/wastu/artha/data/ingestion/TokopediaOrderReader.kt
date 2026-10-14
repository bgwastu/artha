@file:android.annotation.SuppressLint("SdCardPath")

package net.wastu.artha.data.ingestion

import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONObject

internal class TokopediaOrderReader(private val files: RootFileReader) {
    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        val sessionPath = "$BASE/files/keva/repo/LOGIN_SESSION_v2/LOGIN_SESSION_v2.blk"
        val devicePath = "$BASE/files/keva/repo/GCM_STORAGE_v2/GCM_STORAGE_v2.blk"
        val cookiePath = "$BASE/shared_prefs/ttnetCookieStore.xml"
        val snapshot = files.stableBatch(listOf(sessionPath, devicePath, cookiePath))
        val token = TokopediaProviderParser.sessionToken(snapshot[sessionPath])
        val sessionId = TokopediaProviderParser.sessionId(snapshot[devicePath])
        val cookies = TokopediaProviderParser.nativeCookies(snapshot[cookiePath])
        val cursors = mutableSetOf<String>()
        var cursor: Any? = null
        repeat(MAX_PAGES) {
            coroutineContext.ensureActive()
            val response = fetchPage(token, sessionId, cookies, TokopediaProviderParser.request(cursor)) {
                coroutineContext.ensureActive()
            }
            val page = TokopediaProviderParser.page(response)
            // Lynx cards are presentation data. Their displayed totals and creation
            // dates do not establish settled payments or refund credits.
            TokopediaProviderParser.requireVerifiedPayments(page)
            if (!page.hasMore) return@withContext emptyList()
            val next = page.nextCursor ?: throw ProviderReadException(AccountStatus.ERROR,
                "Tokopedia history has more pages but no cursor. Saved history is kept.")
            if (!cursors.add(next.toString())) throw ProviderReadException(AccountStatus.ERROR,
                "Tokopedia repeated a history cursor. Saved history is kept.")
            cursor = next
        }
        throw ProviderReadException(AccountStatus.ERROR,
            "Tokopedia history reached its page limit. Saved history is kept.")
    }

    private fun fetchPage(
        token: String, sessionId: String, cookies: String, body: JSONObject, checkActive: () -> Unit
    ): JSONObject {
        checkActive()
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            // TTSTokoSessionInterceptor in the audited APK uses these headers.
            connection.setRequestProperty("Tokopro-Accounts-Authorization", "Bearer $token")
            connection.setRequestProperty("Tokopro-SessionId", sessionId)
            if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "TokopediaPro/45.0.5 (Android; zh_CN) Cronet")
            checkActive()
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            checkActive()
            val status = connection.responseCode
            checkActive()
            TokopediaProviderParser.requireResponse(
                status,
                connection.getHeaderField("Tokopro-Toko-Login"),
                connection.getHeaderField("Bdturing-Verify"),
                connection.getHeaderField("X-Vc-Bdturing-Parameters"),
            )
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                TokopediaProviderParser.response(reader, checkActive)
            }
        } catch (_: IOException) {
            checkActive()
            throw ProviderReadException(AccountStatus.ERROR,
                "Tokopedia history could not be read. Saved history is kept.")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE = "/data/data/com.tokopedia.tkpd"
        const val ENDPOINT = "https://oec-api.tiktokv.com/api/v1/trade/bff/order_list/get"
        const val MAX_PAGES = 20
    }
}
