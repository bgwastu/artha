package net.wastu.artha.data.ingestion

import java.time.Instant
import net.wastu.artha.core.model.*
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject

/** Grab 5.430.0 contracts verified from current authenticated history and receipts. */
internal object GrabProviderParser {
    data class WalletPage(val items: JSONArray, val cursor: Long?, val partial: Boolean)

    fun walletPage(raw: JSONObject): WalletPage {
        val items = raw.optJSONArray("transactions") ?: fail("charge history schema")
        val cursor = number(raw, "lastElementID")
        return WalletPage(items, if (items.length() == 0 || cursor <= 0) null else cursor,
            raw.opt("isPartialFailure") as? Boolean ?: fail("history completeness"))
    }

    fun chargeDirection(item: JSONObject): Direction? = when (item.optString("txType") to item.optString("coreTxType")) {
        "BOOKING_CHARGE" to "BookingCharge", "TIPPING_CHARGE" to "TippingCharge" -> Direction.DEBIT
        else -> null // Positive amount is a magnitude, not direction evidence.
    }

    fun wallet(item: JSONObject): UnifiedTransaction? {
        val status = text(item, "status")
        if (status in setOf("PENDING", "PROCESSING", "FAILED", "CANCELLED", "CANCELED")) return null
        if (status != "COMPLETED") fail("settlement status")
        val direction = chargeDirection(item) ?: fail("transaction type; no direction guessed")
        if (text(item, "currency") != "IDR") fail("currency")
        val id = text(item, "txID"); val group = item.optString("partnerGroupTxID")
        val tip = text(item, "txType") == "TIPPING_CHARGE"
        val amount = Math.multiplyExact(number(item, "amount"), 100L).also { if (it <= 0) fail("positive payment magnitude") }
        val date = seconds(number(item, "timestamp"))
        val payload = JSONObject().put("origin", "grab-wallet-api").put("provider", Provider.GRAB.name)
            .put("orderNumber", if (!tip && group.isNotBlank()) group else id)
            .put("paymentMethod", item.optString("payMethod")).put("serviceTier", item.optString("partnerID"))
            .put("paymentEvidence", "settled-charge-history").put("paymentStatus", status)
            .put("paymentDirection", direction.name).put("paymentAmountMinor", amount).put("paymentCurrency", "IDR")
            .put("paymentTimestamp", date.toString()).put("sourceTxType", text(item, "txType"))
            .put("sourceCoreTxType", text(item, "coreTxType")).put("isTip", tip)
        return UnifiedTransaction(Provider.GRAB, if (tip) "grab:tip:$id" else "grab:$id", direction,
            Money(amount), date, item.optString("title").ifBlank { "Grab Payment" }, payload.toString())
    }

    fun ride(ride: JSONObject, receiptRoot: JSONObject?): UnifiedTransaction? {
        val state = text(ride, "state")
        if (state in setOf("CANCELLED", "CANCELED", "FAILED")) return null
        if (state != "COMPLETED") fail("ride completion status")
        val id = text(ride, "bookingCode")
        val receipt = receiptRoot?.optJSONObject("response")
        if (receiptRoot != null && receipt == null) fail("ride receipt schema")
        val quote = ride.optJSONObject("quote") ?: fail("ride quote")
        val currency = receipt?.optJSONObject("currency") ?: quote.optJSONObject("currency") ?: fail("ride currency")
        if (text(currency, "code") != "IDR" || number(currency, "exponent") != 2L) fail("ride currency units")
        if (receipt != null && (text(receipt, "code") != id || text(receipt, "status") != "COMPLETED")) fail("ride receipt identity/status")
        val fare = receipt?.optJSONObject("total") ?: quote.optJSONObject("fare") ?: fail("ride amount")
        val amount = number(fare, "lowerBound").also { if (it <= 0 || it != number(fare, "upperBound")) fail("final ride amount range") }
        val timeline = ride.optJSONObject("timeline") ?: fail("ride timestamp")
        val date = if (receipt != null) Instant.parse(text(receipt, "completedAt")) else milliseconds(number(timeline, "completeTime"))
        val service = ride.optJSONObject("serviceInfo")?.optString("name").orEmpty().ifBlank { "GrabRide" }
        val route = receipt?.optJSONArray("itinerary") ?: ride.optJSONArray("itinerary")
        fun location(index: Int): String = route?.optJSONObject(index)?.let { node ->
            val details = node.optJSONObject("details") ?: node
            details.optString("keywords").ifBlank { details.optString("keyword") }.ifBlank { details.optString("address") }
        }.orEmpty()
        val pickup = location(0); val dropoff = location((route?.length() ?: 1) - 1)
        val payload = JSONObject().put("origin", "grab-rides-api").put("provider", Provider.GRAB.name)
            .put("orderNumber", id).put("serviceTier", service).put("pickupLocation", pickup).put("dropoffLocation", dropoff)
            .put("sourceStatus", state).put("paymentDetailVerified", receipt != null).put("grossPaidAmountMinor", amount)
            .put("timestampKind", if (receipt != null) "completed_receipt" else "completed_ride")
        if (receipt != null) {
            val payments = receipt.optJSONArray("paymentDetails") ?: fail("ride payment details")
            var total = 0L
            val methods = mutableListOf<String>()
            for (i in 0 until payments.length()) {
                val payment = payments.optJSONObject(i) ?: fail("ride payment row")
                val detail = payment.optJSONObject("currencyDetail") ?: fail("ride payment currency")
                if (text(payment, "currency") != "IDR" || text(detail, "code") != "IDR" || number(detail, "exponent") != 2L) fail("ride payment units")
                total = Math.addExact(total, number(payment, "amount"))
                methods += payment.optJSONObject("method")?.optString("name").orEmpty()
            }
            if (payments.length() == 0 || total != amount) fail("ride payment total")
            payload.put("paymentMethod", methods.filter(String::isNotBlank).distinct().joinToString(" + "))
            payload.put("hasPaymentArrears", !receipt.isNull("incurredArrearInfo"))
        }
        // Completion/quote/receipt is enrichment; confirmed charge history or a bank match governs money/date.
        return UnifiedTransaction(Provider.GRAB, "grab:$id", Direction.DEBIT, Money(amount), date,
            if (pickup.isNotBlank() && dropoff.isNotBlank()) "$service: $pickup to $dropoff" else service,
            payload.toString(), isConfirmed = false)
    }

    fun foodPage(raw: JSONObject): Pair<JSONArray, Int?> {
        val orders = raw.optJSONArray("orders") ?: fail("food history schema")
        val more = raw.opt("hasMore") as? Boolean ?: fail("food history completeness")
        return orders to if (more) number(raw, "nextOffset").also { if (it !in 1..Int.MAX_VALUE.toLong()) fail("food cursor") }.toInt() else null
    }

    private fun seconds(value: Long) = Instant.ofEpochSecond(value.also { if (it !in 946684800L..4102444800L) fail("seconds timestamp") })
    private fun milliseconds(value: Long) = Instant.ofEpochMilli(value.also { if (it !in 946684800000L..4102444800000L) fail("milliseconds timestamp") })
    private fun text(obj: JSONObject, key: String): String = (obj.opt(key) as? String)?.takeIf(String::isNotBlank) ?: fail(key)
    private fun number(obj: JSONObject, key: String): Long = when (val value = obj.opt(key)) {
        is Int, is Long -> (value as Number).toLong()
        is String -> value.toLongOrNull() ?: fail(key)
        else -> fail(key)
    }
    private fun fail(field: String): Nothing = throw ProviderReadException(AccountStatus.ERROR, "Grab $field is unsupported; saved history is kept")
}
