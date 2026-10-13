@file:android.annotation.SuppressLint("SdCardPath")

package net.wastu.artha.data.ingestion

import android.database.sqlite.SQLiteDatabase
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class ShopeeOrderReader(private val files: RootFileReader) {
    var accountUsername: String? = null
        private set

    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        try {
            val cookies = readCookies()
            check(cookies.isNotBlank()) { "Shopee session cookies not found. Please log in to Shopee app." }
            val orders = linkedMapOf<String, UnifiedTransaction>()

            // 1. Shopee Marketplace Orders
            for (listType in listOf(8, 3)) {
                for (offset in listOf(0, 20)) {
                    coroutineContext.ensureActive()
                    val response = runCatching { fetchOrders(cookies, listType, offset) }.getOrNull() ?: continue
                    if (response.optInt("error", 0) != 0) continue
                    val details = response.optJSONObject("data")?.optJSONArray("details_list") ?: continue
                    for (index in 0 until details.length()) {
                        val order = details.optJSONObject(index) ?: continue
                        val transaction = parseOrder(order, cookies, fetchDetail = orders.size < 5) ?: continue
                        orders.putIfAbsent(transaction.providerTransactionId, transaction)
                    }
                }
            }

            // 2. ShopeeFood Orders (concurrently fetched across key queries)
            val foodDeferreds = coroutineScope {
                SHOPEEFOOD_SEARCH_KEYWORDS.map { kw ->
                    async { runCatching { fetchShopeeFoodOrders(cookies, kw) }.getOrNull() }
                }
            }
            for (foodOrders in foodDeferreds.awaitAll().filterNotNull()) {
                for (index in 0 until foodOrders.length()) {
                    val foodOrder = foodOrders.optJSONObject(index) ?: continue
                    val transaction = parseShopeeFoodOrder(foodOrder) ?: continue
                    orders.putIfAbsent(transaction.providerTransactionId, transaction)
                }
            }

            orders.values.sortedByDescending { it.occurredAt }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw e
        }
    }

    private suspend fun readCookies(): String {
        val bytes = files.read("/data/data/com.shopee.id/app_webview_com.shopee.id/Default/Cookies")
        val tmp = File.createTempFile("shopee_cookies", ".db")
        try {
            tmp.writeBytes(bytes)
            return SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT name, value FROM cookies WHERE host_key LIKE '%shopee.co.id'", null).use { cursor ->
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
        } finally {
            tmp.delete()
        }
    }

    private fun fetchOrders(cookies: String, listType: Int, offset: Int): JSONObject {
        val connection = URL("https://shopee.co.id/api/v4/order/get_order_list?limit=20&list_type=$listType&offset=$offset")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("User-Agent", "Shopee Android Beeshop locale/id version=38336 appver=38336")
            connection.setRequestProperty("X-Shopee-Client-Timezone", "Asia/Jakarta")
            connection.setRequestProperty("Referer", "https://shopee.co.id/")
            check(connection.responseCode in 200..299) { "Shopee order history unavailable" }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchShopeeFoodOrders(cookies: String, keyword: String): JSONArray? {
        val connection = URL("https://foody.shopee.co.id/api/buyer/orders/search-history")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("User-Agent", "Shopee Android Beeshop locale/id version=38336 appver=38336")
            connection.setRequestProperty("X-Shopee-Client-Timezone", "Asia/Jakarta")
            connection.setRequestProperty("Referer", "https://shopee.co.id/")
            connection.setRequestProperty("X-SF-Trace-ID", UUID.randomUUID().toString())
            connection.setRequestProperty("X-SF-Publish-Context-ID", UUID.randomUUID().toString())
            connection.setRequestProperty("X-App-Type", "shopee")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")

            val payload = JSONObject()
                .put("keyword", keyword)
                .put("last_id", "")
                .put("page_size", 50)
                .toString()

            connection.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode !in 200..299) return null
            val responseJson = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            responseJson.optJSONObject("data")?.optJSONArray("orders")
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseShopeeFoodOrder(order: JSONObject): UnifiedTransaction? {
        val orderId = order.optString("id").trim()
        if (orderId.isBlank() || orderId == "0") return null

        // Skip cancelled orders (status >= 900 or cancel_time > 0)
        val status = order.optInt("status", 0)
        val cancelTime = order.optString("cancel_time", "0").toLongOrNull() ?: 0L
        if (status >= 900 || cancelTime > 0L) return null

        val amountObj = order.optJSONObject("amount") ?: return null
        val totalRaw = amountObj.optString("total_amount", "0").toLongOrNull() ?: 0L
        val finalTotalMinor = totalRaw / 1000L
        if (finalTotalMinor <= 0L) return null

        val paymentTimeMs = order.optString("payment_time", "0").toLongOrNull()?.takeIf { it > 0L }
            ?: order.optString("place_time", "0").toLongOrNull()?.takeIf { it > 0L }
            ?: order.optString("create_time", "0").toLongOrNull()?.takeIf { it > 0L }
            ?: return null

        val storeObj = order.optJSONObject("store")
        val storeName = storeObj?.optString("name").orEmpty().trim()
        val storeAddress = storeObj?.optJSONObject("location")?.optString("address").orEmpty().trim()
        val dropoffAddress = order.optJSONObject("delivery_address")?.optJSONObject("location")?.optString("address").orEmpty().trim()

        val rawItems = order.optJSONArray("items") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until rawItems.length()) {
            val itemObj = rawItems.optJSONObject(i) ?: continue
            val cartItem = itemObj.optJSONObject("cart_item")
            val dish = cartItem?.optJSONObject("detail")?.optJSONObject("dish")
            val dishName = dish?.optString("name").orEmpty().trim()
            if (dishName.isBlank()) continue
            val qty = cartItem?.optInt("quantity", 1) ?: 1
            val unitPriceRaw = itemObj.optString("unit_price", "0").toLongOrNull()
                ?: dish?.optString("price", "0")?.toLongOrNull()
                ?: 0L
            val unitPriceRupiah = unitPriceRaw / 100_000L
            items.put(
                JSONObject()
                    .put("name", dishName)
                    .put("qty", qty)
                    .put("price", "Rp$unitPriceRupiah")
            )
        }

        val firstDish = items.optJSONObject(0)?.optString("name").orEmpty()
        val dishSummary = when {
            firstDish.isBlank() -> ""
            items.length() > 1 -> "$firstDish (+${items.length() - 1} more)"
            else -> firstDish
        }
        val description = when {
            storeName.isNotBlank() && dishSummary.isNotBlank() -> "$storeName - $dishSummary"
            storeName.isNotBlank() -> storeName
            dishSummary.isNotBlank() -> dishSummary
            else -> "ShopeeFood Order"
        }

        // Extract card last 4 if available in tip_info payment_channel_option
        val channelOptStr = order.optJSONObject("tip_info")?.optJSONObject("tip")?.optString("payment_channel_option").orEmpty()
        val cardLast4 = runCatching {
            val optJson = JSONObject(channelOptStr)
            val cardNum = optJson.optJSONObject("credit_card_data")?.optString("card_number").orEmpty()
            cardNum.takeLast(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
        }.getOrNull()

        return UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:$orderId",
            direction = Direction.DEBIT,
            money = Money(finalTotalMinor.coerceAtLeast(100L), "IDR"),
            occurredAt = Instant.ofEpochMilli(paymentTimeMs),
            description = description,
            cardLast4 = cardLast4,
            rawPayload = JSONObject()
                .put("origin", "shopeefood-order-api")
                .put("provider", Provider.SHOPEE.name)
                .put("paymentMethod", "ShopeeFood")
                .put("restaurantName", storeName)
                .put("shopName", storeName)
                .put("counterpart", storeName.ifBlank { "ShopeeFood" })
                .apply {
                    if (storeAddress.isNotBlank()) put("pickupLocation", storeAddress)
                    if (dropoffAddress.isNotBlank()) put("dropoffLocation", dropoffAddress)
                }
                .put("notes", if (items.length() > 1) "${items.length()} food items" else "")
                .put("items", items)
                .toString(),
        )
    }

    private fun fetchOrderTime(cookies: String, orderId: Long): Long? {
        val connection = URL("https://shopee.co.id/api/v4/order/get_order_detail?order_id=$orderId")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("User-Agent", "Shopee Android Beeshop locale/id version=38336 appver=38336")
            connection.setRequestProperty("X-Shopee-Client-Timezone", "Asia/Jakarta")
            connection.setRequestProperty("Referer", "https://shopee.co.id/")
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val rows = json.optJSONObject("data")?.optJSONObject("processing_info")?.optJSONArray("info_rows") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val label = row.optJSONObject("info_label")?.optString("text").orEmpty()
                if (label == "label_odp_order_time" || label == "label_odp_payment_time") {
                    val value = row.optJSONObject("info_value")?.optString("value")?.toLongOrNull()
                    if (value != null && value > 0L) return value
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOrder(order: JSONObject, cookies: String, fetchDetail: Boolean): UnifiedTransaction? {
        val infoCard = order.optJSONObject("info_card") ?: return null
        val orderId = infoCard.optLong("order_id")
        if (orderId <= 0L) return null
        // Shopee stores rupiah * 100,000; the ledger stores rupiah * 100.
        val finalTotal = infoCard.optLong("final_total") / 1000L
        val ctime = order.optJSONObject("shipping")?.optJSONObject("tracking_info")
            ?.optLong("ctime")?.takeUnless { it == 0L } ?: Instant.now().epochSecond
        val orderTime = if (fetchDetail) fetchOrderTime(cookies, orderId) ?: ctime else ctime
        val cards = infoCard.optJSONArray("order_list_cards") ?: order.optJSONArray("order_list_cards") ?: JSONArray()
        val shopName = cards.optJSONObject(0)?.optJSONObject("shop_info")?.optString("shop_name").orEmpty()
        val items = JSONArray()
        for (cardIndex in 0 until cards.length()) {
            val groups = cards.optJSONObject(cardIndex)?.optJSONObject("product_info")
                ?.optJSONArray("item_groups") ?: continue
            for (groupIndex in 0 until groups.length()) {
                val products = groups.optJSONObject(groupIndex)?.optJSONArray("items") ?: continue
                for (itemIndex in 0 until products.length()) {
                    val item = products.optJSONObject(itemIndex) ?: continue
                    val name = item.optString("name")
                    val model = item.optString("model_name")
                    val qty = item.optInt("amount", 1)
                    val unitPriceRupiah = item.optLong("item_price") / 100000L
                    items.put(
                        JSONObject()
                            .put("name", name)
                            .put("model", model)
                            .put("qty", qty)
                            .put("price", "Rp$unitPriceRupiah")
                    )
                }
            }
        }
        val firstName = items.optJSONObject(0)?.optString("name").orEmpty()
        val description = if (firstName.isBlank()) shopName.ifBlank { "Shopee Order" }
        else if (items.length() > 1) "$firstName (+${items.length() - 1} more)" else firstName
        return UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:$orderId",
            direction = Direction.DEBIT,
            money = Money(finalTotal.coerceAtLeast(100L), "IDR"),
            occurredAt = Instant.ofEpochSecond(orderTime),
            description = description,
            rawPayload = JSONObject()
                .put("origin", "shopee-order-api")
                .put("provider", Provider.SHOPEE.name)
                .put("paymentMethod", "Shopee")
                .put("shopName", shopName)
                .put("counterpart", shopName)
                .put("notes", if (items.length() > 1) "${items.length()} products" else "")
                .put("items", items)
                .toString(),
        )
    }

    companion object {
        private val SHOPEEFOOD_SEARCH_KEYWORDS = listOf(
            "ayam",
            "nasi",
            "mie",
            "paket",
            "goreng",
            "sapi",
            "pizza",
            "martabak",
            "kebab",
            "burger",
            "kopi",
            "es",
            "bakar",
            "pedas",
            "crispy",
            "combo",
            "teh",
            "roti",
            "bakso",
            "soto",
        )
    }
}
