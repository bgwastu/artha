@file:android.annotation.SuppressLint("SdCardPath")

package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val GRAB_CLIENT_SALT_BYTES = byteArrayOf(
    0x07, 0x53, 0x08, 0xd3.toByte(), 0x3c, 0xb5.toByte(), 0xf8.toByte(), 0xce.toByte(),
    0xb5.toByte(), 0x46, 0x72, 0x7e, 0xb6.toByte(), 0xc1.toByte(), 0xf7.toByte(), 0x90.toByte(),
    0x45, 0xcf.toByte(), 0xfa.toByte(), 0x62, 0x7c, 0x16, 0x2a, 0x7a,
    0x98.toByte(), 0x89.toByte(), 0x81.toByte(), 0x81.toByte(), 0x8e.toByte(), 0x35, 0x67, 0xc3.toByte(),
)

internal class GrabOrderReader(
    private val files: RootFileReader,
    private val installTimeOverride: Long? = null,
) {
    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        val token = readSessionToken()
        val rows = ImportRows()
        for (read in listOf<() -> List<UnifiedTransaction>>({ fetchRides(token) }, { fetchGrabPayTransactions(token) }, { fetchFoodOrders(token) })) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try { rows.transactions += read() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (partial: PartialImportException) { rows.transactions += partial.transactions; rows.issue(partial.message.orEmpty()) }
            catch (e: Exception) { rows.issue(e) }
        }
        rows.finish().sortedByDescending { it.occurredAt }
    }

    internal suspend fun readSessionToken(): String {
        val prefs = parsePreferencesXml(files.read("/data/data/com.grabtaxi.passenger/shared_prefs/com.grabtaxi.passenger_preferences.xml").inputStream())
        val sessionId = prefs["sessionId"] ?: error("Grab session missing. Please log in again.")
        val salt = prefs["salt"] ?: error("Grab session salt missing. Please log in again.")
        val installTime = installTimeOverride ?: files.packageFirstInstallTime("com.grabtaxi.passenger").takeIf { it > 0 }
            ?: readInstallTimeFromRestrictions() ?: error("Cannot resolve Grab install time")
        return decryptSession(sessionId, salt, installTime, prefs["KEY_GENERATION_ID"], prefs["appsFlyerAppId"])
    }

    private suspend fun readInstallTimeFromRestrictions(): Long? {
        val text = files.read("/data/system/users/0/package-restrictions.xml").toString(Charsets.UTF_8)
        val match = Regex("""<package\s+name="com\.grabtaxi\.passenger"[^>]*first-install-time="([0-9a-fA-F]+)"""").find(text)
        return match?.groupValues?.get(1)?.toLongOrNull(16)
    }

    private fun request(token: String, url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", token); setRequestProperty("x-mts-ssid", token)
            setRequestProperty("User-Agent", "Grab/5.430.0 (Android)")
            setRequestProperty("X-Grab-id-ServiceId", "PASSENGER"); setRequestProperty("Accept", "application/json")
        }
        try {
            requireHttpSuccess(connection.responseCode, "Grab")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    private fun fetchRides(token: String): List<UnifiedTransaction> {
        val rides = request(token, "https://p.grabtaxi.com/api/passenger/v3/rides/bookings/list?bookingTypes=PAST").getJSONArray("rides")
        val rows = ImportRows()
        for (i in 0 until rides.length()) rows.readRow {
            val ride = rides.getJSONObject(i)
            val id = ride.getString("bookingCode")
            val service = ride.optJSONObject("serviceInfo")?.optString("name").orEmpty().ifBlank { "GrabRide" }
            val amount = ride.getJSONObject("quote").getJSONObject("fare").getLong("lowerBound")
            val timeline = ride.getJSONObject("timeline")
            val time = timeline.optLong("completeTime").takeIf { it > 0 } ?: timeline.getLong("pickupTime").also { require(it > 0) }
            val route = ride.optJSONArray("itinerary")
            fun location(index: Int) = route?.optJSONObject(index)?.let { it.optString("keyword").ifBlank { it.optString("address") } }.orEmpty()
            val pickup = location(0); val dropoff = location((route?.length() ?: 1) - 1)
            val payload = JSONObject().put("origin", "grab-rides-api").put("orderNumber", id).put("serviceTier", service)
                .put("pickupLocation", pickup).put("dropoffLocation", dropoff).put("paymentMethod", "GrabPay")
            UnifiedTransaction(Provider.GRAB, "grab:$id", Direction.DEBIT, Money(amount), Instant.ofEpochMilli(time),
                if (pickup.isNotBlank() && dropoff.isNotBlank()) "$service: $pickup to $dropoff" else service, payload.toString())
        }
        return rows.finish()
    }

    private fun fetchGrabPayTransactions(token: String): List<UnifiedTransaction> {
        val items = request(token, "https://p.grabtaxi.com/api/passenger/v2/grabpay/history/credit/?currency=IDR&pageSize=50&lastElementID=0").getJSONArray("transactions")
        val rows = ImportRows()
        for (i in 0 until items.length()) rows.readRow {
            val item = items.getJSONObject(i)
            val id = item.getString("txID")
            val group = item.optString("partnerGroupTxID")
            val title = item.optString("title").ifBlank { "GrabPay Payment" }
            val partner = item.optString("partnerID")
            val type = item.optString("direction").ifBlank { item.optString("paymentType") }
            val direction = Direction.valueOf(type.uppercase(java.util.Locale.ROOT))
            val rupiah = item.getLong("amount").also { require(it != Long.MIN_VALUE) }
            val amount = Math.multiplyExact(kotlin.math.abs(rupiah), 100)
            val time = item.getLong("timestamp").also { require(it > 0) }
            val tip = partner.equals("Tipping", true) || title.contains("Tip", true)
            val order = if (!tip && group.isNotBlank()) group else id
            val payload = JSONObject().put("origin", "grab-wallet-api").put("orderNumber", order).put("item", item)
                .put("paymentMethod", item.optString("payMethod")).put("serviceTier", partner)
            // Optional route enrichment never changes the wallet payment's amount or timestamp.
            if (partner.equals("Transport", true) && group.isNotBlank() && i < 3) {
                try {
                    val receipt = request(token, "https://p.grabtaxi.com/api/passenger/v3/rides/history/$group").getJSONObject("response")
                    val route = receipt.optJSONArray("itinerary")
                    fun location(index: Int) = route?.optJSONObject(index)?.optJSONObject("details")?.let { it.optString("keywords").ifBlank { it.optString("address") } }.orEmpty()
                    payload.put("pickupLocation", location(0)).put("dropoffLocation", location((route?.length() ?: 1) - 1))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { rows.issue("Optional ride receipt unavailable; wallet payment retained") }
            }
            UnifiedTransaction(Provider.GRAB, if (tip) "grab:tip:$id" else "grab:$id", direction,
                Money(amount), Instant.ofEpochSecond(time), "GrabPay: $title", payload.toString())
        }
        return rows.finish()
    }

    private fun fetchFoodOrders(token: String): List<UnifiedTransaction> {
        val orders = request(token, "https://p.grabtaxi.com/api/passenger/v3/grabfood/orders/history?limit=20&offSet=0&latlng=-6.2088,106.8456&sortBy=CreatedAt&excludeOngoing=1&disablePagination=false").getJSONArray("orders")
        val rows = ImportRows()
        for (i in 0 until orders.length()) rows.readRow {
            val order = orders.getJSONObject(i)
            val id = order.getString("orderID")
            val amount = order.getLong("totalMinor")
            val time = order.getLong("orderTime").also { require(it > 0) }
            val merchant = order.optString("merchantName").ifBlank { "GrabFood" }
            val payload = JSONObject().put("origin", "grab-food-api").put("orderNumber", id)
                .put("restaurantName", merchant).put("paymentMethod", "GrabPay")
            UnifiedTransaction(Provider.GRAB, "grab:$id", Direction.DEBIT, Money(amount), Instant.ofEpochMilli(time), "GrabFood: $merchant", payload.toString())
        }
        return rows.finish()
    }

    companion object {
        internal fun decryptSession(
            sessionId: String,
            salt: String,
            installTime: Long,
            keyGenId: String?,
            appsFlyerId: String?,
        ): String {
            val parts = sessionId.split(":")
            require(parts.size == 3) { "Invalid Grab session format" }

            val decoder = Base64.getMimeDecoder()
            val iv = decoder.decode(parts[0].trim())
            val expectedMac = decoder.decode(parts[1].trim())
            val cipherBytes = decoder.decode(parts[2].trim())
            val saltBytes = decoder.decode(salt.trim())

            val candidateComponents = listOfNotNull(keyGenId, appsFlyerId)
            val candidateIterations = listOf(2000, 10000)

            for (iters in candidateIterations) {
                for (comp in candidateComponents) {
                    val prefix = GRAB_CLIENT_SALT_BYTES.joinToString("") { "%02x".format(it) }
                    val kdfMaterial = "$prefix|$installTime|$comp"
                    val keySpec = PBEKeySpec(kdfMaterial.toCharArray(), saltBytes, iters, 384)
                    val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    val derived = secretKeyFactory.generateSecret(keySpec).encoded

                    val aesKey = SecretKeySpec(derived.copyOfRange(0, 16), "AES")
                    val hmacKey = SecretKeySpec(derived.copyOfRange(16, 48), "HmacSHA256")

                    val mac = Mac.getInstance("HmacSHA256").apply {
                        init(hmacKey)
                        update(iv)
                        update(cipherBytes)
                    }
                    val computedMac = mac.doFinal()

                    if (MessageDigest.isEqual(computedMac, expectedMac)) {
                        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                            init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
                        }
                        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
                    }
                }
            }
            throw IllegalStateException("Failed to verify MAC or decrypt Grab session with available keys")
        }

        internal fun parsePreferencesXml(input: InputStream): Map<String, String> {
            val map = mutableMapOf<String, String>()
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(input)
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue
                val text = node.textContent
                if (name != null && text != null) {
                    map[name] = text
                }
            }
            return map
        }
    }
}
