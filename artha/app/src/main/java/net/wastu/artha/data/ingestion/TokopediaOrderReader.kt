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
            if (token.isNullOrBlank()) return@withContext emptyList()
            val cookies = readCookies()
            fetchOrders(token, cookies)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
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

    private suspend fun readCookies(): String {
        return try {
            val bytes = files.read("/data/data/com.tokopedia.tkpd/app_webview/Default/Cookies")
            val tmp = File.createTempFile("tokopedia_cookies", ".db")
            try {
                tmp.writeBytes(bytes)
                SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT name, value FROM cookies WHERE host_key LIKE '%tokopedia%'", null).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val name = cursor.getString(0).orEmpty()
                                val value = cursor.getString(1).orEmpty()
                                if (name.isNotBlank() && value.isNotBlank()) add("$name=$value")
                            }
                        }.joinToString("; ")
                    }
                }
            } finally {
                tmp.delete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchOrders(token: String, cookies: String): List<UnifiedTransaction> {
        val connection = URL("https://oec-api.tiktokv.com/api/v1/trade/bff/order_list/get")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accounts-Authorization", "Bearer $token")
            if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "com.tokopedia.tkpd/45.0.5 (Android 16)")
            val body = JSONObject().put("fe_version", "2").put("page_size", 20).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return emptyList()
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val list = response.optJSONObject("data")?.optJSONArray("order_list") ?: return emptyList()
            return buildList {
                for (i in 0 until list.length()) {
                    val order = list.optJSONObject(i) ?: continue
                    val id = order.optString("order_id").ifBlank { order.optString("main_order_id") }
                    if (id.isBlank()) continue
                    val total = order.optLong("total_amount", 0L).coerceAtLeast(100L)
                    val title = order.optString("product_name").ifBlank { "Tokopedia Order" }
                    add(
                        UnifiedTransaction(
                            provider = Provider.TOKOPEDIA,
                            providerTransactionId = "tokopedia:$id",
                            direction = Direction.DEBIT,
                            money = Money(total, "IDR"),
                            occurredAt = Instant.now(),
                            description = title,
                            rawPayload = order.toString(),
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
