package net.wastu.artha.data.ingestion

import java.math.BigDecimal
import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject

/** Contracts checked against the installed Shopee app and its read-only API responses. */
internal object ShopeeProviderParser {
    data class Payment(val transaction: UnifiedTransaction, val refundExpected: Boolean)
    data class MarketplacePage(val orders: JSONArray, val nextOffset: Int?, val paginationIssue: ProviderReadException? = null)
    data class FoodPage(val orders: JSONArray, val nextId: String?, val paginationIssue: ProviderReadException? = null)

    fun allMarketplacePage(response: JSONObject): MarketplacePage {
        // The current v2 endpoint returns {data: ...} without the legacy error field.
        val body = if (response.has("error")) data(response, food = false)
            else response.optJSONObject("data") ?: schema("all-orders response data")
        var issue: ProviderReadException? = null
        val offset = try {
            integer(body, "next_offset").also { if (it !in -1L..Int.MAX_VALUE.toLong()) schema("all-orders cursor") }
        } catch (error: ProviderReadException) { issue = error; null }
        val source = body.optJSONArray("order_or_checkout_data")
            ?: if (offset == -1L && (!body.has("order_or_checkout_data") || body.isNull("order_or_checkout_data"))) JSONArray()
            else schema("all-orders list")
        val orders = JSONArray()
        for (index in 0 until source.length()) {
            try {
                val info = source.optJSONObject(index)?.optJSONObject("order_list_detail")?.optJSONObject("order_info")
                    ?: schema("all-orders entry")
                val id = integer(info, "order_id").toString().also(::validId)
                // Discovery only. Quotes, checkout IDs and creation times cannot create payments.
                orders.put(JSONObject().put("info_card", JSONObject().put("order_id", id.toLong())))
            } catch (error: ProviderReadException) { if (issue == null) issue = error }
        }
        return MarketplacePage(orders, if (issue == null) offset?.toInt()?.takeIf { it >= 0 } else null, issue)
    }

    fun marketplacePage(response: JSONObject): MarketplacePage {
        val data = data(response, food = false)
        val offset = try {
            integer(data, "next_offset").also {
                if (it !in -1L..Int.MAX_VALUE.toLong()) schema("marketplace cursor")
            }
        } catch (error: ProviderReadException) {
            // A broken cursor prevents traversal, not detail verification of this page's orders.
            val orders = data.optJSONArray("details_list") ?: throw error
            return MarketplacePage(orders, null, error)
        }
        // The live endpoint omits details_list entirely on its terminal page.
        val orders = data.optJSONArray("details_list")
            ?: if (offset == -1L && (!data.has("details_list") || data.isNull("details_list"))) JSONArray()
            else schema("marketplace order list")
        return MarketplacePage(orders, offset.toInt().takeIf { it >= 0 })
    }

    fun foodPage(response: JSONObject, search: Boolean): FoodPage {
        val data = data(response, food = true)
        val orders = data.optJSONArray("orders") ?: schema("food order list")
        return try {
            val more = data.opt("has_more") as? Boolean ?: schema("food pagination")
            // Search uses next_id in live responses; the app's /orders list service reads lastId.
            val next = if (more) string(data, if (search) "next_id" else "last_id")
                .takeIf { it.isNotBlank() } ?: schema("food cursor") else null
            FoodPage(orders, next)
        } catch (error: ProviderReadException) {
            FoodPage(orders, null, error)
        }
    }

    fun data(response: JSONObject, food: Boolean): JSONObject {
        val code = integer(response, if (food) "code" else "error")
        // Marketplace control: error=19. App ApiCode: AuthFailed=3004, AuthTokenExpired=3011.
        // Food code=1 from the cookie-less control is generic and cannot prove expiry.
        if ((!food && code == 19L) || (food && code in listOf(3004L, 3011L))) throw ProviderReadException(
            AccountStatus.NEEDS_LOGIN, "Shopee session expired. Open Shopee and log in again.",
        )
        if (code != 0L) throw ProviderReadException(
            AccountStatus.ERROR, "Shopee ${if (food) "food" else "marketplace"} API rejected history (code $code).",
        )
        return response.optJSONObject("data") ?: schema("response data")
    }

    fun marketplace(orderId: String, listing: JSONObject, detail: JSONObject): Payment? {
        validId(orderId)
        val info = detail.optJSONObject("info_card") ?: schema("marketplace payment")
        if (string(info, "currency") != "IDR") schema("marketplace currency")
        val paid = integer(info, "amount_paid")
        val times = detail.optJSONObject("processing_info")?.optJSONArray("info_rows")
            ?: schema("marketplace processing times")
        val paymentTime = labelTime(times, "label_odp_payment_time")
        if (paid == 0L && paymentTime == null) return null // Explicitly unpaid, not malformed.
        if (paid <= 0L || paymentTime == null) schema("marketplace paid amount/time")
        val cards = info.optJSONArray("parcel_cards")
            ?: listing.optJSONObject("info_card")?.optJSONArray("order_list_cards") ?: JSONArray()
        val shop = cards.optJSONObject(0)?.optJSONObject("shop_info")?.let { string(it, "shop_name") }.orEmpty()
        val items = marketplaceItems(cards)
        val cancelled = labelTime(times, "label_odp_cancelled_time") != null
        val returned = integer(detail, "list_type") == 7L
        val raw = receipt("shopee-order-api", "Shopee", shop, items)
            .put("orderId", orderId)
            .put("sourceListType", detail.get("list_type"))
            .put("timestampKind", "payment")
            .put("sourcePaymentTime", paymentTime)
            .put("sourceAmountPaid", paid)
            .put("grossPaidAmountMinor", money(paid).amountMinor)
            .put("paymentDetailVerified", true)
            .put("paymentEvidence", "settled-order-payment").put("paymentStatus", "COMPLETED")
            .put("paymentDirection", "DEBIT").put("paymentAmountMinor", money(paid).amountMinor)
            .put("paymentCurrency", "IDR").put("paymentTimestamp", seconds(paymentTime).toString())
            .put("cancelled", cancelled)
            .put("refundState", if (cancelled || returned) "unresolved" else "not_observed")
        detail.optJSONObject("status")?.optJSONObject("status_label")?.let {
            string(it, "text").takeIf(String::isNotBlank)?.let { label -> raw.put("sourceStatusLabel", label) }
        }
        detail.optJSONObject("payment_method")?.optJSONObject("payment_channel_name")?.let {
            string(it, "text").takeIf(String::isNotBlank)?.let { method -> raw.put("sourcePaymentMethod", method) }
        }
        return Payment(UnifiedTransaction(
            provider = Provider.SHOPEE, providerTransactionId = "shopee:$orderId",
            direction = Direction.DEBIT, money = money(paid), occurredAt = seconds(paymentTime),
            description = summary(shop, items, "Shopee Order"), rawPayload = raw.toString(),
            // Actual paid amount and payment event are financial evidence; grouping is internal.
            isConfirmed = true,
        ), cancelled || returned)
    }

    fun food(order: JSONObject, detail: JSONObject? = null): Payment? {
        val id = string(order, "id").also(::validId)
        val source = detail?.optJSONObject("order") ?: order
        if (string(source, "id") != id) schema("food detail identity")
        val orderTime = integer(source, "payment_time")
        val payments = detail?.optJSONArray("payment")
        var cardLast4: String? = null
        var paidPayment: JSONObject? = null
        val paid: Long
        val paymentTime: Long
        if (detail != null) {
            if (payments == null) schema("food payments")
            val successful = (0 until payments.length()).map { index ->
                payments.optJSONObject(index) ?: schema("food payment row")
            }.filter { integer(it, "status") == 2L } // App PaymentStatus.Paid=2.
            if (successful.isEmpty() && orderTime == 0L) return null
            if (successful.size != 1) schema("food successful payment count")
            val payment = successful.single()
            paidPayment = payment
            if (string(payment, "order_id") != id || string(payment, "currency") != "IDR") schema("food payment identity/currency")
            paid = integer(payment, "total_amount")
            paymentTime = integer(payment, "payment_time")
            if (paymentTime != orderTime) schema("food payment timestamp disagreement")
            cardLast4 = paymentCardLast4(payment)
        } else {
            // Keep an evidenced order payment if detail enrichment is temporarily blocked.
            if (orderTime == 0L) return null
            paid = source.optJSONObject("amount")?.let { integer(it, "total_amount") }
                ?: schema("food amount")
            paymentTime = orderTime
        }
        val store = order.optJSONObject("store")
        val shop = store?.let { string(it, "name") }.orEmpty()
        val items = foodItems(source.optJSONArray("items") ?: order.optJSONArray("items") ?: JSONArray())
        val cancelled = integer(source, "cancel_time") > 0L
        val refunded = source.optJSONObject("flag")?.let { integer(it, "is_refunded") > 0L } ?: false
        val returned = source.optJSONObject("flag")?.let { flags ->
            flags.has("is_returned") && integer(flags, "is_returned") > 0L
        } ?: false
        val afterSales = source.has("has_after_sales") && integer(source, "has_after_sales") > 0L
        val refundAmount = integer(source, "refund_amount")
        val refundExpected = cancelled || refunded || returned || afterSales || refundAmount > 0L ||
            (!source.isNull("return_order_id") && source.has("return_order_id"))
        val raw = receipt("shopeefood-order-api", "ShopeeFood", shop, items)
            .put("restaurantName", shop).put("orderId", id)
            .put("sourceStatus", integer(source, "status"))
            .put("sourcePaymentTime", paymentTime).put("timestampKind", "payment")
            .put("sourceAmountPaid", paid).put("paymentDetailVerified", detail != null)
            .put("cancelled", cancelled).put("sourceRefunded", refunded)
            .put("cardLast4", cardLast4 ?: JSONObject.NULL)
            .put("sourceReturned", returned).put("sourceAfterSales", afterSales)
            .put("sourceRefundAmount", refundAmount)
            .put("refundState", if (refundExpected) "unresolved" else "not_observed")
        if (detail != null) {
            raw.put("paymentEvidence", "settled-order-payment").put("paymentStatus", "COMPLETED")
                .put("paymentDirection", "DEBIT").put("paymentAmountMinor", money(paid).amountMinor)
                .put("paymentCurrency", "IDR").put("paymentTimestamp", milliseconds(paymentTime).toString())
            paidPayment?.let { payment ->
                runCatching { JSONObject(string(payment, "channel_option")) }.getOrNull()?.let { option ->
                    string(option, "channel_name").takeIf(String::isNotBlank)?.let { raw.put("sourcePaymentMethod", it) }
                }
            }
        }
        store?.optJSONObject("location")?.let { string(it, "address") }?.takeIf(String::isNotBlank)?.let { raw.put("pickupLocation", it) }
        order.optJSONObject("delivery_address")?.optJSONObject("location")?.let { string(it, "address") }
            ?.takeIf(String::isNotBlank)?.let { raw.put("dropoffLocation", it) }
        return Payment(UnifiedTransaction(
            provider = Provider.SHOPEE, providerTransactionId = "shopee:$id", direction = Direction.DEBIT,
            money = money(paid), occurredAt = milliseconds(paymentTime),
            description = summary(shop, items, "ShopeeFood Order"), rawPayload = raw.toString(),
            cardLast4 = cardLast4, isConfirmed = detail != null,
        ), refundExpected)
    }

    fun foodRefund(charge: UnifiedTransaction, refund: JSONObject): UnifiedTransaction? {
        val status = integer(refund, "refund_status")
        // Current app RefundStatus: Created=1, Refunding=4, Completed=5, Failed=6.
        if (status in listOf(1L, 4L, 6L)) return null
        if (status != 5L) schema("food refund status")
        val chargePayload = JSONObject(charge.rawPayload)
        if (!chargePayload.optBoolean("paymentDetailVerified") || charge.money.currencyCode != "IDR") schema("refund payment currency")
        val orderId = string(refund, "order_id").also(::validId)
        if (charge.providerTransactionId != "shopee:$orderId") schema("refund order identity")
        val refundId = string(refund, "refund_id").also(::validId)
        val amount = integer(refund, "refund_amount")
        val time = integer(refund, "refund_time")
        val raw = receipt("shopee-refund-api", "ShopeeFood", string(chargePayload, "shopName"), JSONArray())
            .put("orderId", orderId).put("refundId", refundId)
            .put("originalChargeId", charge.providerTransactionId).put("verifiedRefund", true)
            .put("sourceRefundStatus", status).put("sourceRefundMethod", integer(refund, "refund_method"))
            .put("sourceRefundAmount", amount).put("sourceRefundTime", time)
            .put("sourceCurrency", "IDR").put("timestampKind", "refund")
            .put("grossPaidAmountMinor", charge.money.amountMinor)
            .put("paymentEvidence", "settled-provider-refund").put("paymentStatus", "COMPLETED")
            .put("paymentDirection", "CREDIT").put("paymentAmountMinor", money(amount).amountMinor)
            .put("paymentCurrency", "IDR").put("paymentTimestamp", milliseconds(time).toString())
        return UnifiedTransaction(
            provider = Provider.SHOPEE, providerTransactionId = "shopee:refund:$refundId",
            direction = Direction.CREDIT, money = money(amount), occurredAt = milliseconds(time),
            description = "ShopeeFood Refund", rawPayload = raw.toString(),
            isReversal = true, isConfirmed = true,
        )
    }

    private fun marketplaceItems(cards: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until cards.length()) {
            val groups = cards.optJSONObject(i)?.optJSONObject("product_info")?.optJSONArray("item_groups") ?: continue
            for (g in 0 until groups.length()) {
                val items = groups.optJSONObject(g)?.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val product = items.optJSONObject(j) ?: continue
                    val name = string(product, "name").takeIf(String::isNotBlank) ?: continue
                    put(item(name, product.opt("amount"), product.opt("item_price")).apply {
                        string(product, "model_name").takeIf(String::isNotBlank)?.let { put("model", it) }
                    })
                }
            }
        }
    }

    private fun foodItems(source: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until source.length()) {
            val row = source.optJSONObject(i) ?: continue
            val cart = row.optJSONObject("cart_item") ?: continue
            val dish = cart.optJSONObject("detail")?.optJSONObject("dish") ?: continue
            val name = string(dish, "name").takeIf(String::isNotBlank) ?: continue
            put(item(name, cart.opt("quantity"), row.opt("unit_price")))
        }
    }

    private fun item(name: String, quantity: Any?, price: Any?): JSONObject = JSONObject().put("name", name).apply {
        long(quantity)?.takeIf { it > 0L }?.let { put("qty", it) }
        long(price)?.takeIf { it >= 0L && it % 1000L == 0L }?.let {
            put("unitAmountMinor", it / 1000L)
            put("price", "Rp${BigDecimal.valueOf(it, 5).stripTrailingZeros().toPlainString()}")
        }
    }

    private fun receipt(origin: String, method: String, shop: String, items: JSONArray): JSONObject = JSONObject()
        .put("origin", origin).put("provider", Provider.SHOPEE.name).put("paymentMethod", method)
        .put("shopName", shop).put("counterpart", shop.ifBlank { method }).put("items", items)

    private fun summary(shop: String, items: JSONArray, fallback: String): String {
        val first = items.optJSONObject(0)?.let { string(it, "name") }.orEmpty()
        val product = if (items.length() > 1) "$first (+${items.length() - 1} more)" else first
        return listOf(shop, product).filter(String::isNotBlank).joinToString(" - ").ifBlank { fallback }
    }

    private fun paymentCardLast4(payment: JSONObject): String? {
        // tip_info describes a separate tip payment and cannot identify the order's card.
        val option = runCatching { JSONObject(string(payment, "channel_option")) }.getOrNull() ?: return null
        val card = option.optJSONObject("credit_card_data") ?: return null
        val number = string(card, "card_number")
        return number.takeLast(4).takeIf { it.length == 4 && it.all(Char::isDigit) && number.any { c -> c == '*' || c == 'X' || c == 'x' } }
    }

    private fun labelTime(rows: JSONArray, label: String): Long? {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: schema("processing time row")
            if (row.optJSONObject("info_label")?.let { string(it, "text") } == label) {
                val value = row.optJSONObject("info_value")?.let { integer(it, "value") } ?: schema("processing time value")
                if (value < 0L) schema("processing time value")
                return value.takeIf { it > 0L }
            }
        }
        return null
    }

    private fun seconds(value: Long): Instant {
        if (value !in 946684800L..4102444800L) schema("seconds timestamp")
        return Instant.ofEpochSecond(value)
    }
    private fun milliseconds(value: Long): Instant {
        if (value !in 946684800000L..4102444800000L) schema("milliseconds timestamp")
        return Instant.ofEpochMilli(value)
    }
    private fun money(raw: Long): Money {
        // Both source contracts use rupiah * 100,000; ledger IDR minor units are rupiah * 100.
        if (raw <= 0L || raw % 1000L != 0L) schema("exact IDR amount")
        return Money(raw / 1000L, "IDR")
    }
    internal fun orderId(row: JSONObject, food: Boolean): String = if (food) string(row, "id").also(::validId)
        else (row.optJSONObject("info_card")?.let { integer(it, "order_id") } ?: schema("order identity"))
            .toString().also(::validId)
    private fun validId(id: String) { if (!id.matches(Regex("[0-9]+")) || id.all { it == '0' }) schema("order identity") }
    private fun long(value: Any?): Long? = when (value) {
        is String -> value.toLongOrNull()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        else -> null
    }
    private fun integer(row: JSONObject, key: String): Long = long(row.opt(key)) ?: schema("$key integer")
    private fun string(row: JSONObject, key: String): String = (row.opt(key) as? String)?.trim().orEmpty()
    private fun schema(field: String): Nothing = throw ProviderReadException(AccountStatus.ERROR, "Unrecognized Shopee $field schema; valid rows retained.")
}
