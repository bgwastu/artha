package net.wastu.artha.data.ingestion

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import net.wastu.artha.core.model.*
import org.json.JSONObject

internal object ProviderRowParser {
    private val zone = ZoneId.of("Asia/Jakarta")
    fun date(value: String): Instant = try { OffsetDateTime.parse(value).toInstant() }
        catch (_: java.time.format.DateTimeParseException) { LocalDateTime.parse(value).atZone(zone).toInstant() }

    fun tokopedia(order: JSONObject): UnifiedTransaction? {
        val id = order.optString("order_id").ifBlank { order.optString("main_order_id") }
        val amount = order.optLong("amount_minor")
        val currency = order.optString("currency")
        val timestamp = order.optString("payment_time")
        if (id.isBlank() || amount <= 0 || currency != "IDR" || timestamp.isBlank()) return null
        return UnifiedTransaction(Provider.TOKOPEDIA, "tokopedia:$id", Direction.DEBIT, Money(amount, currency),
            Instant.parse(timestamp), order.optString("product_name").ifBlank { "Tokopedia Order" }, order.toString())
    }

    fun goPay(item: JSONObject): UnifiedTransaction {
        val id = item.getString("order_id").also { require(it.isNotBlank()) }
        val direction = Direction.valueOf(item.getString("payment_type").uppercase(Locale.ROOT))
        val amount = Math.multiplyExact(item.getJSONObject("amount").getLong("value"), 100L)
        val service = item.optString("service_type")
        val description = item.optString("description").ifBlank { "GoPay Payment" }
        val qris = service.equals("COLLECT", true) || description.contains("QRIS", true) || item.optString("payment_transfer_type").contains("QRIS", true)
        val payload = JSONObject().put("origin", "gopay-order-history").put("provider", Provider.GOJEK.name)
            .put("orderNumber", id).put("serviceTier", service).put("isQris", qris)
            .put("paymentMethod", item.optJSONArray("payment_method_breakup")?.optJSONObject(0)?.optString("payment_method_name") ?: "GoPay")
            .put("item", item)
        return UnifiedTransaction(Provider.GOJEK, "gopay:$id", direction, Money(amount), date(item.getString("order_timestamp")), description, payload.toString(), isQris = qris)
    }
}
