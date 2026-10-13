@file:android.annotation.SuppressLint("SdCardPath")

package net.wastu.artha.data.ingestion

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class TokopediaOrderReader(private val files: RootFileReader) {
    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        try {
            val blk = files.read("/data/data/com.tokopedia.tkpd/files/keva/repo/LOGIN_SESSION_v2/LOGIN_SESSION_v2.blk")
            val token = decryptSessionToken(blk)
            check(!token.isNullOrBlank()) { "Tokopedia session missing or unrecognized. Please log in again." }
            val cookies = readCookies()
            fetchOrders(token, cookies)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun decryptSessionToken(blk: ByteArray): String? {
        val key = "ggggtttttujkrrrr".toByteArray(Charsets.UTF_8)
        val iv = "tokopedia1234567".toByteArray(Charsets.UTF_8)
        val text = blk.toString(Charsets.ISO_8859_1)
        for (match in Regex("[A-Za-z0-9+/=\\n]{40,}").findAll(text)) {
            val b64 = match.value.replace("\n", "").trim()
            val raw = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            if (raw.size >= 16 && raw.size % 16 == 0) {
                val pt = runCatching {
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.IvParameterSpec(iv),
                    )
                    String(cipher.doFinal(raw), Charsets.UTF_8)
                }.getOrNull() ?: continue
                if (pt.startsWith("eyJ")) return pt
            }
        }
        return null
    }

    private suspend fun readCookies(): String = files.readSqlite("/data/data/com.tokopedia.tkpd/app_webview/Default/Cookies") { db ->
        db.rawQuery("SELECT name, value FROM cookies WHERE host_key LIKE '%tokopedia%'", null).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val name = cursor.getString(0).orEmpty(); val value = cursor.getString(1).orEmpty()
                if (name.isNotBlank() && value.isNotBlank()) add("$name=$value")
            } }.joinToString("; ")
        }
    }

    private fun fetchOrders(token: String, cookies: String): List<UnifiedTransaction> {
        val connection = URL("https://oec-api.tiktokv.com/api/v1/trade/bff/order_list/get")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accounts-Authorization", "Bearer $token")
            if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "com.tokopedia.tkpd/45.0.5 (Android 16)")
            val body = JSONObject().put("fe_version", "2").put("page_size", 20).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            requireHttpSuccess(connection.responseCode, "Tokopedia")
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val list = response.optJSONObject("data")?.optJSONArray("order_list")
                ?: throw ProviderReadException(net.wastu.artha.data.providers.AccountStatus.ERROR,
                    "Tokopedia's experimental response is unsupported. Saved history is kept.")
            val rows = ImportRows()
            for (i in 0 until list.length()) rows.readRow {
                ProviderRowParser.tokopedia(list.getJSONObject(i))
            }
            return rows.finish()
        } finally {
            connection.disconnect()
        }
    }
}
