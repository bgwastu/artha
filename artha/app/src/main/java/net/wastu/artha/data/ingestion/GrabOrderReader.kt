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
        val token = runCatching { readSessionToken() }.getOrNull() ?: return@withContext emptyList()
        val allTransactions = mutableMapOf<String, UnifiedTransaction>()

        // 1. Ingest Rides (bookings list)
        runCatching {
            val rides = fetchRides(token)
            for (ride in rides) {
                allTransactions[ride.providerTransactionId] = ride
            }
        }

        // 2. Ingest GrabPay / OVO Wallet history (credit & transactions)
        runCatching {
            val walletTxs = fetchGrabPayTransactions(token, existingRideCodes = allTransactions.keys)
            for (tx in walletTxs) {
                allTransactions.putIfAbsent(tx.providerTransactionId, tx)
            }
        }

        // 3. Ingest GrabFood orders history if available
        runCatching {
            val foodOrders = fetchFoodOrders(token)
            for (order in foodOrders) {
                allTransactions.putIfAbsent(order.providerTransactionId, order)
            }
        }

        allTransactions.values.sortedByDescending { it.occurredAt }
    }

    internal suspend fun readSessionToken(): String {
        val xmlBytes = files.read("/data/data/com.grabtaxi.passenger/shared_prefs/com.grabtaxi.passenger_preferences.xml")
        val prefs = parsePreferencesXml(xmlBytes.inputStream())
        val sessionId = prefs["sessionId"] ?: throw IllegalStateException("Missing sessionId in Grab preferences")
        val salt = prefs["salt"] ?: throw IllegalStateException("Missing salt in Grab preferences")
        val keyGenId = prefs["KEY_GENERATION_ID"]
        val appsFlyerId = prefs["appsFlyerAppId"]

        val installTime = installTimeOverride ?: files.packageFirstInstallTime("com.grabtaxi.passenger").takeIf { it > 0L }
            ?: runCatching { readInstallTimeFromRestrictions() }.getOrNull()
            ?: throw IllegalStateException("Cannot resolve Grab install time")

        return decryptSession(sessionId, salt, installTime, keyGenId, appsFlyerId)
    }

    private suspend fun readInstallTimeFromRestrictions(): Long? {
        val xmlBytes = files.read("/data/system/users/0/package-restrictions.xml")
        val text = String(xmlBytes, Charsets.UTF_8)
        val match = Regex("""<package\s+name="com\.grabtaxi\.passenger"[^>]*first-install-time="([0-9a-fA-F]+)"""").find(text)
        return match?.groupValues?.get(1)?.toLongOrNull(16)
    }

    private fun fetchRides(token: String): List<UnifiedTransaction> {
        val url = "https://p.grabtaxi.com/api/passenger/v3/rides/bookings/list?bookingTypes=PAST"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", token)
            setRequestProperty("x-mts-ssid", token)
            setRequestProperty("User-Agent", "Grab/5.430.0 (Android)")
            setRequestProperty("X-Grab-id-ServiceId", "PASSENGER")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10000
            readTimeout = 15000
        }
        if (connection.responseCode !in 200..299) return emptyList()
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val rides = root.optJSONArray("rides") ?: return emptyList()

        return buildList {
            for (i in 0 until rides.length()) {
                val ride = rides.optJSONObject(i) ?: continue
                val code = ride.optString("bookingCode").ifBlank { continue }
                val serviceInfo = ride.optJSONObject("serviceInfo")
                val serviceName = serviceInfo?.optString("name")?.takeIf { it.isNotBlank() } ?: "GrabRide"

                val quote = ride.optJSONObject("quote")
                val fareObj = quote?.optJSONObject("fare")
                val fareMinor = fareObj?.optLong("lowerBound", 0L) ?: 0L

                val timeline = ride.optJSONObject("timeline")
                val completeTime = timeline?.optLong("completeTime", 0L)?.takeIf { it > 0L }
                    ?: timeline?.optLong("pickupTime", 0L)?.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                val occurredAt = Instant.ofEpochMilli(completeTime)

                val itinerary = ride.optJSONArray("itinerary")
                val pickup = itinerary?.optJSONObject(0)?.let {
                    it.optString("keyword").ifBlank { it.optString("address") }
                }.orEmpty().trim()
                val dropoff = itinerary?.optJSONObject(itinerary.length() - 1)?.let {
                    it.optString("keyword").ifBlank { it.optString("address") }
                }.orEmpty().trim()

                val description = if (pickup.isNotBlank() && dropoff.isNotBlank()) {
                    "$serviceName: $pickup to $dropoff"
                } else if (dropoff.isNotBlank()) {
                    "$serviceName to $dropoff"
                } else {
                    serviceName
                }

                val rawPayload = JSONObject()
                    .put("origin", "grab-rides-api")
                    .put("provider", Provider.GRAB.name)
                    .put("orderNumber", code)
                    .put("serviceTier", serviceName)
                    .put("pickupLocation", pickup)
                    .put("dropoffLocation", dropoff)
                    .put("paymentMethod", "GrabPay")
                    .toString()

                add(
                    UnifiedTransaction(
                        provider = Provider.GRAB,
                        providerTransactionId = "grab:$code",
                        direction = Direction.DEBIT,
                        money = Money(fareMinor, "IDR"),
                        occurredAt = occurredAt,
                        description = description,
                        rawPayload = rawPayload,
                    )
                )
            }
        }
    }

    private fun fetchGrabPayTransactions(token: String, existingRideCodes: Set<String>): List<UnifiedTransaction> {
        val url = "https://p.grabtaxi.com/api/passenger/v2/grabpay/history/credit/?currency=IDR&pageSize=50&lastElementID=0"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", token)
            setRequestProperty("x-mts-ssid", token)
            setRequestProperty("User-Agent", "Grab/5.430.0 (Android)")
            setRequestProperty("X-Grab-id-ServiceId", "PASSENGER")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10000
            readTimeout = 15000
        }
        if (connection.responseCode !in 200..299) return emptyList()
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val txs = root.optJSONArray("transactions") ?: return emptyList()

        return buildList {
            for (i in 0 until txs.length()) {
                val item = txs.optJSONObject(i) ?: continue
                val txId = item.optString("txID").ifBlank { continue }
                val group = item.optString("partnerGroupTxID")
                val partnerId = item.optString("partnerID")
                val title = item.optString("title", "GrabPay Payment")
                val subtitle = item.optString("subtitle")
                val amountRupiah = item.optLong("amount", 0L)
                val amountMinor = amountRupiah * 100L
                val tsSeconds = item.optLong("timestamp", 0L)
                val occurredAt = if (tsSeconds > 0L) Instant.ofEpochSecond(tsSeconds) else Instant.now()
                val payMethod = item.optString("payMethod", "GrabPay")

                // If this is a ride already recorded in recent rides, skip duplicating the ride charge.
                if (group.isNotBlank() && "grab:$group" in existingRideCodes && partnerId.equals("Transport", ignoreCase = true)) {
                    continue
                }

                val isTip = partnerId.equals("Tipping", ignoreCase = true) || title.contains("Tip", ignoreCase = true)
                val isRide = partnerId.equals("Transport", ignoreCase = true) || title.contains("Ride", ignoreCase = true)
                val isFood = partnerId.equals("GrabFood", ignoreCase = true) || subtitle.contains("GrabFood", ignoreCase = true)

                // For older rides not in the 7 recent rides list, try fetching their detailed receipt
                var detailedRide: UnifiedTransaction? = null
                if (isRide && group.isNotBlank() && "grab:$group" !in existingRideCodes) {
                    detailedRide = runCatching { fetchRideDetail(token, group) }.getOrNull()
                }

                if (detailedRide != null) {
                    add(detailedRide)
                    continue
                }

                val desc = when {
                    isTip -> "Grab: Tip${if (group.isNotBlank()) " ($group)" else ""}"
                    isFood -> "GrabFood: $title"
                    isRide -> "Grab: $title${if (group.isNotBlank()) " ($group)" else ""}"
                    else -> "GrabPay: $title"
                }

                val rawPayload = JSONObject()
                    .put("origin", "grab-wallet-api")
                    .put("provider", Provider.GRAB.name)
                    .put("orderNumber", if (group.isNotBlank()) group else txId)
                    .put("serviceTier", if (isFood) "GrabFood" else if (isRide) "GrabRide" else "GrabPay")
                    .put("paymentMethod", payMethod)
                    .toString()

                val txKey = if (isTip) "grab:tip:$txId" else "grab:$txId"
                add(
                    UnifiedTransaction(
                        provider = Provider.GRAB,
                        providerTransactionId = txKey,
                        direction = Direction.DEBIT,
                        money = Money(amountMinor, "IDR"),
                        occurredAt = occurredAt,
                        description = desc,
                        rawPayload = rawPayload,
                    )
                )
            }
        }
    }

    private fun fetchRideDetail(token: String, bookingCode: String): UnifiedTransaction? {
        val url = "https://p.grabtaxi.com/api/passenger/v3/rides/history/$bookingCode"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", token)
            setRequestProperty("x-mts-ssid", token)
            setRequestProperty("User-Agent", "Grab/5.430.0 (Android)")
            setRequestProperty("X-Grab-id-ServiceId", "PASSENGER")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8000
            readTimeout = 10000
        }
        if (connection.responseCode !in 200..299) return null
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(text).optJSONObject("response") ?: return null

        val serviceInfo = root.optJSONObject("serviceTypeInfo")
        val serviceName = serviceInfo?.optString("name")?.takeIf { it.isNotBlank() } ?: "GrabRide"

        val totalFareInfo = root.optJSONObject("totalFareInfo")
        val fareMinor = totalFareInfo?.optLong("amount", 0L) ?: 0L

        val itinerary = root.optJSONArray("itinerary")
        var pickup = ""
        var dropoff = ""
        var completedTime = 0L

        if (itinerary != null && itinerary.length() > 0) {
            val pObj = itinerary.optJSONObject(0)
            val dObj = itinerary.optJSONObject(itinerary.length() - 1)
            pickup = pObj?.optJSONObject("details")?.let {
                it.optString("keywords").ifBlank { it.optString("address") }
            }.orEmpty().trim()
            dropoff = dObj?.optJSONObject("details")?.let {
                it.optString("keywords").ifBlank { it.optString("address") }
            }.orEmpty().trim()
            completedTime = dObj?.optString("time")?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            } ?: 0L
        }

        val occurredAt = if (completedTime > 0L) Instant.ofEpochMilli(completedTime) else Instant.now()
        val desc = if (pickup.isNotBlank() && dropoff.isNotBlank()) {
            "$serviceName: $pickup to $dropoff"
        } else if (dropoff.isNotBlank()) {
            "$serviceName to $dropoff"
        } else {
            serviceName
        }

        val rawPayload = JSONObject()
            .put("origin", "grab-rides-detail-api")
            .put("provider", Provider.GRAB.name)
            .put("orderNumber", bookingCode)
            .put("serviceTier", serviceName)
            .put("pickupLocation", pickup)
            .put("dropoffLocation", dropoff)
            .put("paymentMethod", totalFareInfo?.optString("name", "GrabPay") ?: "GrabPay")
            .toString()

        return UnifiedTransaction(
            provider = Provider.GRAB,
            providerTransactionId = "grab:$bookingCode",
            direction = Direction.DEBIT,
            money = Money(fareMinor, "IDR"),
            occurredAt = occurredAt,
            description = desc,
            rawPayload = rawPayload,
        )
    }

    private fun fetchFoodOrders(token: String): List<UnifiedTransaction> {
        val url = "https://p.grabtaxi.com/api/passenger/v3/grabfood/orders/history?limit=20&offSet=0&latlng=-6.2088,106.8456&sortBy=CreatedAt&excludeOngoing=1&disablePagination=false"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", token)
            setRequestProperty("x-mts-ssid", token)
            setRequestProperty("User-Agent", "Grab/5.430.0 (Android)")
            setRequestProperty("X-Grab-id-ServiceId", "PASSENGER")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8000
            readTimeout = 10000
        }
        if (connection.responseCode !in 200..299) return emptyList()
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val orders = root.optJSONArray("orders") ?: return emptyList()

        return buildList {
            for (i in 0 until orders.length()) {
                val order = orders.optJSONObject(i) ?: continue
                val orderId = order.optString("orderID").ifBlank { continue }
                val merchantName = order.optString("merchantName", "GrabFood")
                val totalMinor = order.optLong("totalMinor", 0L)
                val orderTime = order.optLong("orderTime", 0L)
                val occurredAt = if (orderTime > 0L) Instant.ofEpochMilli(orderTime) else Instant.now()

                val rawPayload = JSONObject()
                    .put("origin", "grab-food-api")
                    .put("provider", Provider.GRAB.name)
                    .put("orderNumber", orderId)
                    .put("restaurant", merchantName)
                    .put("paymentMethod", "GrabPay")
                    .toString()

                add(
                    UnifiedTransaction(
                        provider = Provider.GRAB,
                        providerTransactionId = "grab:$orderId",
                        direction = Direction.DEBIT,
                        money = Money(totalMinor, "IDR"),
                        occurredAt = occurredAt,
                        description = "GrabFood: $merchantName",
                        rawPayload = rawPayload,
                    )
                )
            }
        }
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
