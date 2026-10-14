@file:android.annotation.SuppressLint("SdCardPath")

package net.wastu.artha.data.ingestion

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject

internal class ShopeeOrderReader(private val files: RootFileReader) {
    var accountUsername: String? = null
        private set

    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        accountUsername = null
        val cookies = readCookies()
        if (cookies.isBlank()) throw ProviderReadException(
            AccountStatus.NEEDS_LOGIN, "Shopee session cookies missing. Open Shopee and log in again.",
        )
        val rows = ImportRows()
        val budget = RequestBudget()
        val market = linkedMapOf<String, JSONObject>()
        // Live lists: shipping, completed, cancelled, returns. Unpaid cancellations are checked in detail.
        for (listType in listOf(8, 3, 4, 7)) {
            var offset = 0
            for (page in 0 until MAX_MARKET_PAGES) {
                coroutineContext.ensureActive()
                val response = try {
                    ShopeeProviderParser.marketplacePage(request(
                        cookies, MARKET_HOST, "/api/v4/order/get_order_list?limit=20&list_type=$listType&offset=$offset", budget,
                    ))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { rows.issue(error); break }
                collect(response.orders, food = false, market, rows)
                response.paginationIssue?.let(rows::issue)
                val next = response.nextOffset ?: break
                if (next <= offset || response.orders.length() == 0) {
                    rows.issue("Marketplace cursor did not advance; history incomplete"); break
                }
                if (page == MAX_MARKET_PAGES - 1) rows.issue("Marketplace page limit reached; older orders may be missing")
                offset = next
            }
        }
        if (market.size > MAX_MARKET_DETAILS) rows.issue("Marketplace detail limit reached; payment history incomplete")
        val marketplace = boundedMap(market.entries.take(MAX_MARKET_DETAILS)) { entry ->
            val detail = ShopeeProviderParser.data(request(
                cookies, MARKET_HOST, "/api/v4/order/get_order_detail?order_id=${entry.key}", budget,
            ), food = false)
            ShopeeProviderParser.marketplace(entry.key, entry.value, detail)
        }
        val identities = linkedMapOf<String, UnifiedTransaction>()
        for (result in marketplace) {
            result.fold(onSuccess = { payment ->
                if (payment != null) {
                    add(payment.transaction, identities, rows)
                    if (payment.refundExpected) rows.issue("Paid cancelled/returned marketplace order found; refund settlement is unresolved")
                }
            }, onFailure = { rows.issue(it as Exception) })
        }

        val food = linkedMapOf<String, JSONObject>()
        var listComplete = false
        var listFailed = false
        var lastId = ""
        val visited = mutableSetOf<String>()
        for (page in 0 until MAX_FOOD_PAGES) {
            coroutineContext.ensureActive()
            val response = try {
                // App Hermes getOrderListApi: GET /orders; EnumFilterType.HISTORY=20.
                // This phone currently returns a challenge, while search remains readable.
                ShopeeProviderParser.foodPage(request(
                    cookies, FOOD_HOST,
                    "/api/buyer/orders?filter_type=20&page_size=50&last_id=${encode(lastId)}", budget,
                ), search = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { rows.issue(error); listFailed = true; break }
            collect(response.orders, food = true, food, rows)
            val paginationIssue = response.paginationIssue
            if (paginationIssue != null) {
                rows.issue(paginationIssue); listFailed = true; break
            }
            val next = response.nextId
            if (next == null) { listComplete = true; break }
            if (next == lastId || !visited.add(next) || response.orders.length() == 0) {
                rows.issue("Food list cursor did not advance; history incomplete"); listFailed = true; break
            }
            lastId = next
        }
        if (!listComplete && !listFailed) rows.issue("Food list page limit reached; older orders may be missing")
        if (!listComplete) {
            rows.issue("Food history is keyword-matched only; orders without these keywords may be missing")
            val searches = boundedMap(FOOD_KEYWORDS) { keyword ->
                val searchRows = mutableListOf<JSONObject>()
                val issues = mutableListOf<Exception>()
                var cursor = ""
                val seen = mutableSetOf<String>()
                for (page in 0 until MAX_SEARCH_PAGES) {
                    val response = try {
                        ShopeeProviderParser.foodPage(request(
                            cookies, FOOD_HOST, "/api/buyer/orders/search-history", budget,
                            JSONObject().put("keyword", keyword).put("last_id", cursor).put("page_size", 50),
                        ), search = true)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { issues += error; break }
                    for (index in 0 until response.orders.length()) {
                        val row = response.orders.optJSONObject(index)
                        if (row == null) issues += schema("food search row") else searchRows += row
                    }
                    response.paginationIssue?.let(issues::add)
                    val next = response.nextId ?: break
                    if (next == cursor || !seen.add(next) || response.orders.length() == 0) {
                        issues += schema("food search cursor did not advance"); break
                    }
                    if (page == MAX_SEARCH_PAGES - 1) issues += schema("food search page limit reached")
                    cursor = next
                }
                searchRows to issues
            }
            for (result in searches) result.fold(onSuccess = { (found, issues) ->
                issues.forEach(rows::issue)
                found.forEach { row -> collect(JSONArray().put(row), food = true, food, rows) }
            }, onFailure = { rows.issue(it as Exception) })
        } else {
            // The app explicitly shows an orders-within-one-year boundary; this is not all financial history.
            rows.issue("Food listing covers the provider's available order window; older/digital purchases are not verified")
        }
        if (food.size > MAX_FOOD_DETAILS) rows.issue("Food detail limit reached; remaining orders use source payment fields only")
        val foods = boundedMap(food.entries.toList().withIndex().toList()) { (detailIndex, entry) ->
            var detail: JSONObject? = null
            val issues = mutableListOf<Exception>()
            if (detailIndex < MAX_FOOD_DETAILS) {
                try {
                    detail = ShopeeProviderParser.data(request(
                        cookies, FOOD_HOST, "/api/buyer/orders/${entry.key}", budget,
                    ), food = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { issues += error }
            }
            val payment = ShopeeProviderParser.food(entry.value, detail)
            val refunds = mutableListOf<UnifiedTransaction>()
            var refundHistoryVerified = false
            if (payment != null && payment.refundExpected && detail != null) {
                try {
                    val data = ShopeeProviderParser.data(request(
                        cookies, FOOD_HOST, "/api/buyer/orders/${entry.key}/refunds", budget,
                    ), food = true)
                    val source = data.optJSONArray("refunds") ?: throw schema("food refund list")
                    for (index in 0 until source.length()) {
                        try {
                            val refund = source.optJSONObject(index) ?: throw schema("food refund row")
                            ShopeeProviderParser.foodRefund(payment.transaction, refund)?.let(refunds::add)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { issues += error }
                    }
                    refundHistoryVerified = refunds.isNotEmpty() && issues.isEmpty()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { issues += error }
            }
            if (payment != null && payment.refundExpected && !refundHistoryVerified) {
                issues += schema("food refund settlement unresolved")
            }
            val charge = payment?.transaction?.let { transaction ->
                val raw = JSONObject(transaction.rawPayload)
                    .put("grossPaidAmountMinor", transaction.money.amountMinor)
                if (refundHistoryVerified) raw.put("refundState", "verified")
                if (refunds.isNotEmpty()) {
                    val total = runCatching { refunds.distinctBy { it.key }
                        .fold(0L) { sum, refund -> Math.addExact(sum, refund.money.amountMinor) } }
                    total.onSuccess { raw.put("refundedAmountMinor", it) }
                        .onFailure { issues += schema("refund total exceeds supported range") }
                }
                transaction.copy(rawPayload = raw.toString())
            }
            Triple(charge, refunds, issues)
        }
        for (result in foods) result.fold(onSuccess = { (charge, refunds, issues) ->
            issues.forEach(rows::issue)
            charge?.let { add(it, identities, rows) }
            refunds.forEach { add(it, identities, rows) }
        }, onFailure = { rows.issue(it as Exception) })
        rows.finish().sortedByDescending { it.occurredAt }
    }

    private suspend fun readCookies(): String = files.readSqlite(
        "/data/data/com.shopee.id/app_webview_com.shopee.id/Default/Cookies",
    ) { database ->
        database.rawQuery(
            "SELECT name, value FROM cookies WHERE host_key = 'shopee.co.id' OR host_key LIKE '%.shopee.co.id'", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0).orEmpty()
                    val value = cursor.getString(1).orEmpty()
                    if (name == "username" && value.isNotBlank()) accountUsername = "@$value"
                    if (name.isNotBlank() && value.isNotBlank()) add("$name=$value")
                }
            }.joinToString("; ")
        }
    }

    private fun collect(source: JSONArray, food: Boolean, target: MutableMap<String, JSONObject>, rows: ImportRows) {
        for (index in 0 until source.length()) {
            try {
                val row = source.optJSONObject(index) ?: throw schema("order list row")
                val id = ShopeeProviderParser.orderId(row, food)
                val previous = target.putIfAbsent(id, row)
                if (food && previous != null &&
                    (previous.optString("payment_time") != row.optString("payment_time") ||
                        previous.optJSONObject("amount")?.optString("total_amount") != row.optJSONObject("amount")?.optString("total_amount"))) {
                    rows.issue("Conflicting duplicate food payment fields; first source row retained for detail verification")
                }
            } catch (error: Exception) { rows.issue(error) }
        }
    }

    private fun add(transaction: UnifiedTransaction, identities: MutableMap<String, UnifiedTransaction>, rows: ImportRows) {
        val existing = identities.putIfAbsent(transaction.key, transaction)
        if (existing == null) rows.transactions += transaction
        else if (existing.money != transaction.money || existing.occurredAt != transaction.occurredAt ||
            existing.direction != transaction.direction ||
            JSONObject(existing.rawPayload).optString("origin") != JSONObject(transaction.rawPayload).optString("origin")) {
            rows.issue("Conflicting Shopee order identity; first valid payment retained")
        }
    }

    private suspend fun <T, R> boundedMap(source: List<T>, read: suspend (T) -> R): List<Result<R>> = coroutineScope {
        val semaphore = Semaphore(3)
        source.map { value -> async(Dispatchers.IO) {
            semaphore.withPermit {
                coroutineContext.ensureActive()
                try { Result.success(read(value)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            }
        } }.awaitAll()
    }

    private suspend fun request(
        cookies: String, host: String, path: String, budget: RequestBudget, payload: JSONObject? = null,
    ): JSONObject {
        kotlin.coroutines.coroutineContext.ensureActive()
        budget.take()
        val connection = URL(host + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("User-Agent", "Shopee Android Beeshop locale/id version=38336 appver=38336")
            connection.setRequestProperty("X-Shopee-Client-Timezone", "Asia/Jakarta")
            connection.setRequestProperty("Referer", "https://shopee.co.id/")
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            if (host == FOOD_HOST) {
                connection.setRequestProperty("X-App-Type", "shopee")
                connection.setRequestProperty("X-SF-Trace-ID", UUID.randomUUID().toString())
                connection.setRequestProperty("X-SF-Publish-Context-ID", UUID.randomUUID().toString())
            }
            if (payload != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 403) {
                val challenge = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val buffer = CharArray(16_384)
                        val length = reader.read(buffer)
                        if (length > 0) JSONObject(String(buffer, 0, length)).optBoolean("is_login") else false
                    } ?: false
                }.getOrDefault(false)
                throw ProviderReadException(AccountStatus.ERROR,
                    "Shopee access ${if (challenge) "challenge" else "blocked"} (HTTP 403). Open Shopee to check access; readable history will be retained.")
            }
            requireHttpSuccess(status, "Shopee")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    budget.checkTime()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 4_000_000) throw schema("response size limit")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            return try { JSONObject(body) } catch (_: Exception) { throw schema("JSON response") }
        } finally { connection.disconnect() }
    }

    private class RequestBudget {
        private val count = AtomicInteger()
        private val deadline = System.nanoTime() + 180_000_000_000L
        fun take() {
            checkTime()
            if (count.incrementAndGet() > 700) throw schema("history request limit reached")
        }
        fun checkTime() { if (System.nanoTime() > deadline) throw schema("history time limit reached") }
    }

    companion object {
        private const val MARKET_HOST = "https://shopee.co.id"
        private const val FOOD_HOST = "https://foody.shopee.co.id"
        private const val MAX_MARKET_PAGES = 30
        private const val MAX_MARKET_DETAILS = 400
        private const val MAX_FOOD_PAGES = 20
        private const val MAX_SEARCH_PAGES = 4
        private const val MAX_FOOD_DETAILS = 150
        private val FOOD_KEYWORDS = listOf(
            "ayam", "nasi", "mie", "paket", "goreng", "sapi", "pizza", "martabak", "kebab", "burger",
            "kopi", "es", "bakar", "pedas", "crispy", "combo", "teh", "roti", "bakso", "soto",
        )
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        private fun schema(field: String) = ProviderReadException(AccountStatus.ERROR, "Shopee $field; valid rows retained.")
    }
}
