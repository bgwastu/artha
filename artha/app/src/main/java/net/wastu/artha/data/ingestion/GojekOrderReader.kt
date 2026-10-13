@file:android.annotation.SuppressLint("SdCardPath")
package net.wastu.artha.data.ingestion

import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONObject

/** Read-only native source snapshots and GETs; no handmade exports or session refresh. */
internal class GojekOrderReader(private val files: RootFileReader) {
    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        val rows = ImportRows()
        val details = linkedMapOf<String, JSONObject>()
        val orders = mutableListOf<GojekProviderParser.NativeOrder>()
        var cachedPayments = false
        try {
            val base = "/data/data/com.gojek.app/cache/okhttp_cache"
            val names = files.list(base).filter { it.matches(Regex("[a-f0-9]{32}\\.0")) }.take(MAX_CACHE_ENTRIES)
            val headers = files.readBatch(names.map { "$base/$it" })
            val selected = headers.mapNotNull { (path, bytes) ->
                try { bytes?.let(GojekProviderParser::cacheEntry)?.let { path to it } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { rows.issue("One Gojek history cache header is unrecognized"); null }
            }.sortedByDescending { it.second.receivedMillis }
            val snapshot = files.stableBatch(selected.flatMap { (path, _) -> listOf(path, path.removeSuffix(".0") + ".1") })
            for ((path, _) in selected) {
                coroutineContext.ensureActive()
                try {
                    val entry = GojekProviderParser.cacheEntry(requireNotNull(snapshot[path])) ?: continue
                    requireGojekSuccess(entry.status)
                    val body = GojekProviderParser.decodeBody(requireNotNull(snapshot[path.removeSuffix(".0") + ".1"]), entry.encoding)
                    when (entry.kind) {
                        GojekProviderParser.CacheKind.PAYMENTS -> {
                            importPage(body, rows)
                            cachedPayments = true
                        }
                        GojekProviderParser.CacheKind.DETAILS -> {
                            val detail = GojekProviderParser.detail(body)
                            val id = detail.opt("order_id") as? String
                            if (!id.isNullOrBlank()) details.putIfAbsent(id, detail)
                        }
                        GojekProviderParser.CacheKind.ORDERS -> orders += GojekProviderParser.orderCards(body)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: ProviderReadException) { rows.issue(e) }
                catch (_: Exception) { rows.issue("One Gojek history cache page is missing or unrecognized") }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: ProviderReadException) {
            if (e.status == AccountStatus.APP_NOT_INSTALLED) throw e
            rows.issue(e)
        }
        catch (_: Exception) { /* A cold cache does not block authenticated history. */ }

        val started = System.nanoTime()
        var historyComplete = false
        try {
            val headers = sessionHeaders()
            var next: String? = GojekProviderParser.HISTORY_URL
            val visited = mutableSetOf<String>()
            var pages = 0
            while (next != null && pages < MAX_PAGES && !deadlineExceeded(started)) {
                coroutineContext.ensureActive()
                val url = GojekProviderParser.historyCursor(next)
                if (!visited.add(url)) { rows.issue("GoPay history repeated a pagination cursor"); break }
                val page = importPage(request(url, headers), rows, preferLive = true)
                pages++
                next = page.next
                if (next == null && page.malformedRows == 0) historyComplete = true
            }
            if (!historyComplete && next != null && (pages >= MAX_PAGES || deadlineExceeded(started))) {
                rows.issue("GoPay history reached the bounded import limit; older payments remain unavailable")
            }
            // Order cards enrich actual payments and never become inferred debits.
            if (!deadlineExceeded(started)) {
                try { orders += GojekProviderParser.orderCards(request(GojekProviderParser.ORDERS_URL, headers)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { rows.issue("Gojek native order enrichment is unavailable") }
            }
            var reads = 0
            for (transaction in rows.transactions.toList()) {
                coroutineContext.ensureActive()
                if (reads >= MAX_DETAILS || deadlineExceeded(started)) break
                val payload = JSONObject(transaction.rawPayload)
                if (payload.optString("serviceTier") !in ENRICHED_SERVICES) continue
                val id = transaction.providerTransactionId.removePrefix("gopay:")
                if (id in details) continue
                reads++
                try {
                    val escaped = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
                    details[id] = GojekProviderParser.detail(request("https://customer.gopayapi.com/v2/users/order-history/$escaped/details?country_code=ID", headers))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { rows.issue("One GoPay payment detail is unavailable") }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: ProviderReadException) { rows.issue(e) }
        catch (_: Exception) { rows.issue(ProviderReadException(AccountStatus.ERROR, "Gojek live history is unavailable; valid cached payments are retained")) }

        for (index in rows.transactions.indices) {
            coroutineContext.ensureActive()
            val payment = rows.transactions[index]
            val detail = details[payment.providerTransactionId.removePrefix("gopay:")] ?: continue
            try { rows.transactions[index] = GojekProviderParser.enrich(payment, detail, orders) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { rows.issue("One GoPay detail could not be matched to its settled payment") }
        }
        if (cachedPayments && !historyComplete) rows.issue("Cached GoPay history covers only pages previously opened in Gojek")
        if (orders.isNotEmpty()) rows.issue("Gojek service orders without verified payments are excluded; order cards provide enrichment only")
        rows.finish().sortedByDescending { it.occurredAt }
    }

    private fun importPage(body: JSONObject, rows: ImportRows, preferLive: Boolean = false): GojekProviderParser.PaymentPage {
        val page = GojekProviderParser.paymentPage(body)
        if (page.malformedRows > 0) rows.issue("GoPay history contains ${page.malformedRows} unrecognized rows or pagination fields")
        for (item in page.items) {
            try {
                val payment = GojekProviderParser.payment(item) ?: continue
                val existing = rows.transactions.indexOfFirst { it.key == payment.key }
                if (existing < 0) rows.transactions += payment
                else if (preferLive) rows.transactions[existing] = payment
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: ProviderReadException) { rows.issue(e) }
            catch (_: Exception) { rows.issue(ProviderReadException(AccountStatus.ERROR, "One GoPay row lacks recognized settled payment fields")) }
        }
        return page
    }

    private suspend fun sessionHeaders(): Map<String, String> {
        val base = "/data/data/com.gojek.app/shared_prefs"
        val paths = listOf("goto-scp-login.xml", "AuthPreferences.xml", "gojek-identity-sso.xml", "network_persistence.xml").map { "$base/$it" }
        val snapshot = files.stableBatch(paths)
        fun preferences(name: String): Map<String, String> = snapshot["$base/$name"]?.let(GojekProviderParser::preferences).orEmpty()
        val encrypted = preferences("goto-scp-login.xml")["key_scp_login_access_token"]
            ?: throw ProviderReadException(AccountStatus.NEEDS_LOGIN, "Gojek login data is missing. Open Gojek and log in again.")
        val token = GojekProviderParser.decryptAccessToken(encrypted)
        val auth = preferences("AuthPreferences.xml")
        val identity = preferences("gojek-identity-sso.xml")
        // AuthData.deviceId matches this package's SSAID, unlike Artha's own Android ID.
        val deviceId = identity["ID_SSO_AuthData"]?.let { runCatching { JSONObject(it).optString("deviceId") }.getOrNull() }
        val info = files.context.packageManager.getPackageInfo("com.gojek.app", 0)
        val version = info.versionName.orEmpty()
        return buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/json"); put("Accept-Encoding", "identity"); put("Content-Type", "application/json")
            put("X-AppId", "com.gojek.app"); put("X-AppVersion", version.substringBefore('-'))
            put("X-Platform", "Android"); put("X-User-Type", "customer")
            put("X-DeviceOS", "Android,${Build.VERSION.RELEASE}")
            put("X-PhoneMake", Build.MANUFACTURER); put("X-PhoneModel", "${Build.BRAND},${Build.MODEL}")
            put("User-Agent", "Gojek/$version (com.gojek.app; build:${PackageInfoCompat.getLongVersionCode(info)}; Android, ${Build.VERSION.RELEASE})")
            auth["id"]?.takeIf { it.isNotBlank() }?.let { put("User-uuid", it) }
            auth["locale"]?.takeIf { it.isNotBlank() }?.let { put("Accept-Language", it) }
            auth["fcmPushToken"]?.takeIf { it.isNotBlank() }?.let { put("X-DeviceToken", it); put("X-PushTokenType", "FCM") }
            deviceId?.takeIf { it.isNotBlank() }?.let { put("X-UniqueId", it) }
            preferences("network_persistence.xml")["fingerprint"]?.takeIf { it.isNotBlank() }?.let { put("D1", it) }
        }
    }

    private fun request(url: String, headers: Map<String, String>): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 10_000; readTimeout = 15_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val code = connection.responseCode
            requireGojekSuccess(code)
            return GojekProviderParser.decodeBody(GojekProviderParser.readBounded(connection.inputStream), connection.contentEncoding.orEmpty())
        } finally { connection.disconnect() }
    }

    private fun requireGojekSuccess(code: Int) {
        if (code == 401 || code == 403) requireHttpSuccess(code, "Gojek")
        if (code !in 200..299) throw ProviderReadException(AccountStatus.ERROR, "Gojek history request failed (HTTP $code); valid cached payments are retained")
    }

    private fun deadlineExceeded(started: Long): Boolean = System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(60)

    private companion object {
        const val MAX_CACHE_ENTRIES = 1000
        const val MAX_PAGES = 50
        const val MAX_DETAILS = 20
        val ENRICHED_SERVICES = setOf("GORIDE", "GOCAR", "GOFOOD", "GOSEND", "GOBLUEBIRD")
    }
}
