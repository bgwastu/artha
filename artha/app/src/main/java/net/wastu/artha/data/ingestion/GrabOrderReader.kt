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
import kotlinx.coroutines.async
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
        val started = System.nanoTime()
        val wallet = mutableListOf<UnifiedTransaction>()
        var cursor = 0L
        val seen = mutableSetOf<Long>()
        for (page in 0 until 30) {
            coroutineContext.ensureActive()
            if (java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) >= 45) {
                rows.issue("Grab history time limit reached; older charges may be missing"); break
            }
            val history = try { GrabProviderParser.walletPage(request(token,
                "/api/passenger/v2/grabpay/history/credit/?currency=IDR&pageSize=50&lastElementID=$cursor")) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { rows.issue(error); break }
            if (history.partial) rows.issue("Grab reports partially unavailable charge history")
            for (index in 0 until history.items.length()) {
                coroutineContext.ensureActive()
                try { GrabProviderParser.wallet(history.items.getJSONObject(index))?.let(wallet::add) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { rows.issue(error) }
            }
            val next = history.cursor ?: break
            if (next == cursor || !seen.add(next)) { rows.issue("Grab charge cursor repeated; history incomplete"); break }
            cursor = next
            if (page == 29) rows.issue("Grab charge page limit reached; older history may be missing")
        }
        val rideRows = mutableListOf<UnifiedTransaction>()
        try {
            val history = request(token, "/api/passenger/v3/rides/bookings/list?bookingTypes=PAST")
            val rides = history.optJSONArray("rides") ?: throw ProviderReadException(net.wastu.artha.data.providers.AccountStatus.ERROR, "Grab ride list schema changed")
            val receiptIndices = GrabProviderParser.receiptIndices(rides)
            // Bound both active requests and coroutine allocation, even if the source
            // returns a large recent window. Canceled entries do not consume receipts.
            for (batchStart in 0 until rides.length() step 3) {
                coroutineContext.ensureActive()
                val results = kotlinx.coroutines.coroutineScope {
                    (batchStart until minOf(batchStart + 3, rides.length())).map { index -> async {
                        try {
                            val ride = rides.getJSONObject(index)
                            var receipt: JSONObject? = null
                            var warning: Exception? = null
                            if (index in receiptIndices) {
                                try {
                                    val id = ride.getString("bookingCode")
                                    require(id.matches(Regex("[A-Za-z0-9_-]+")))
                                    receipt = request(token, "/api/passenger/v3/rides/history/$id")
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (error: Exception) { warning = error }
                            }
                            Result.success(GrabProviderParser.ride(ride, receipt) to warning)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) { Result.failure<Pair<UnifiedTransaction?, Exception?>>(error) }
                    } }.map { it.await() }
                }
                for (result in results) result.fold(onSuccess = { (ride, warning) ->
                    ride?.let(rideRows::add); warning?.let(rows::issue)
                }, onFailure = { rows.issue(it as Exception) })
            }
            // Recent ride enrichment has no cursor. Its scope belongs in coverage,
            // rather than making every otherwise successful payment import an error.
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { rows.issue(error) }

        // Exact booking identity enriches an authoritative charge; it never replaces charge amount/date.
        val charges = GrabProviderParser.uniqueCharges(wallet)
        if (charges.conflictCount > 0) rows.issue("Conflicting Grab charge identities; saved records are retained")
        for (charge in charges.transactions) {
            val payload = JSONObject(charge.rawPayload)
            val ride = rideRows.singleOrNull { JSONObject(it.rawPayload).optString("orderNumber") == payload.optString("orderNumber") }
            rows.transactions += if (ride != null) GrabProviderParser.enrichCharge(charge, ride) else charge
        }
        rows.transactions += rideRows
        // Food history requires native location/context and has no verified payment
        // receipt contract. Food wallet charges above still import normally; do not
        // issue an unsupported discovery request or turn it into a payment failure.
        rows.finish().sortedByDescending { it.occurredAt }
    }

    internal suspend fun readSessionToken(): String {
        val path = "/data/data/com.grabtaxi.passenger/shared_prefs/com.grabtaxi.passenger_preferences.xml"
        val prefs = parsePreferencesXml(requireNotNull(files.stableBatch(listOf(path))[path]).inputStream())
        val sessionId = prefs["sessionId"] ?: error("Grab session missing. Open Grab and log in again.")
        val salt = prefs["salt"] ?: error("Grab session salt missing. Open Grab and log in again.")
        val installTime = installTimeOverride ?: files.packageFirstInstallTime("com.grabtaxi.passenger").takeIf { it > 0 }
            ?: error("Cannot verify Grab install time for the primary Android profile")
        return decryptSession(sessionId, salt, installTime, prefs["KEY_GENERATION_ID"], prefs["appsFlyerAppId"])
    }

    private suspend fun request(token: String, path: String): JSONObject {
        kotlin.coroutines.coroutineContext.ensureActive()
        require(path.startsWith("/api/passenger/") && !path.contains(".."))
        val deadlineNanos = System.nanoTime() + 20_000_000_000L
        val connection = (URL("https://p.grabtaxi.com$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", token); setRequestProperty("x-mts-ssid", token)
            setRequestProperty("User-Agent", "Grab/5.430.0 (Android)")
            setRequestProperty("X-Grab-id-ServiceId", "PASSENGER"); setRequestProperty("Accept", "application/json")
        }
        try {
            requireHttpSuccess(connection.responseCode, "Grab")
            val bytes = connection.inputStream.use { input ->
                readResponse(input, deadlineNanos, prepareRead = { connection.readTimeout = it })
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    companion object {
        internal suspend fun readResponse(
            input: InputStream,
            deadlineNanos: Long,
            nanoTime: () -> Long = System::nanoTime,
            prepareRead: (Int) -> Unit = {},
        ): ByteArray {
            fun remainingMillis(): Int {
                val remaining = deadlineNanos - nanoTime()
                if (remaining <= 0) throw ProviderReadException(net.wastu.artha.data.providers.AccountStatus.ERROR,
                    "Grab history request time limit reached")
                return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, 10_000L).toInt()
            }
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                prepareRead(remainingMillis())
                val count = input.read(buffer)
                kotlin.coroutines.coroutineContext.ensureActive()
                remainingMillis()
                if (count < 0) break
                require(output.size() + count <= 4 * 1024 * 1024) { "Grab history response exceeds the safe import limit" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

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
            val bytes = input.readBytes()
            require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) { "Unsupported Grab preferences XML" }
            builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
            val doc = builder.parse(bytes.inputStream())
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
