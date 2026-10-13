package net.wastu.artha.data.ingestion

import java.time.Instant
import net.wastu.artha.core.model.*
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject

/** Live charge/ride contracts and explicitly bounded Grab 5.430.0 native schema variants. */
internal object GrabProviderParser {
    data class WalletPage(val items: JSONArray, val cursor: Long?, val partial: Boolean)

    fun walletPage(raw: JSONObject): WalletPage {
        val items = raw.optJSONArray("transactions") ?: fail("charge history schema")
        // The installed native TxnHistoryResponse declares lastElementID nullable and
        // has no completeness field. Absence is partial coverage, never proof of completeness.
        val cursor = if (raw.isNull("lastElementID")) null else number(raw, "lastElementID")
        val partial = if (raw.isNull("isPartialFailure")) true
            else raw.opt("isPartialFailure") as? Boolean ?: fail("history completeness")
        return WalletPage(items, cursor?.takeIf { items.length() > 0 && it > 0 }, partial)
    }

    fun chargeDirection(item: JSONObject): Direction? = when (item.optString("txType") to item.optString("coreTxType")) {
        "BOOKING_CHARGE" to "BookingCharge", "TIPPING_CHARGE" to "TippingCharge" -> Direction.DEBIT
        else -> null // Positive amount is a magnitude, not direction evidence.
    }

    fun wallet(item: JSONObject): UnifiedTransaction? {
        val status = text(item, "status")
        if (status in setOf("PENDING", "PROCESSING", "FAILED", "UNSUCCESSFUL", "CANCELLED", "CANCELED")) return null
        if (status != "COMPLETED") fail("settlement status")
        val direction = chargeDirection(item) ?: fail("transaction type; no direction guessed")
        if (text(item, "currency") != "IDR") fail("currency")
        val id = text(item, "txID"); val group = optionalText(item, "partnerGroupTxID")
        val tip = text(item, "txType") == "TIPPING_CHARGE"
        val amount = Math.multiplyExact(wholeRupiah(item), 100L).also { if (it <= 0) fail("positive payment magnitude") }
        val date = seconds(number(item, "timestamp"))
        val payload = JSONObject().put("origin", "grab-wallet-api").put("provider", Provider.GRAB.name)
            .put("orderNumber", if (!tip && group.isNotBlank()) group else id)
            .put("paymentMethod", optionalText(item, "payMethod")).put("serviceTier", optionalText(item, "partnerID"))
            .put("sourcePaymentMethod", optionalText(item, "payMethod"))
            .put("sourceChargeSource", optionalText(item, "source"))
            .put("sourceGroupTxID", optionalText(item, "groupTxID"))
            .put("sourcePartnerGroupTxID", group)
            .put("paymentEvidence", "settled-charge-history").put("paymentStatus", status)
            .put("paymentDirection", direction.name).put("paymentAmountMinor", amount).put("paymentCurrency", "IDR")
            .put("paymentTimestamp", date.toString()).put("sourceTxType", text(item, "txType"))
            .put("sourceCoreTxType", text(item, "coreTxType")).put("isTip", tip)
        return UnifiedTransaction(Provider.GRAB, if (tip) "grab:tip:$id" else "grab:$id", direction,
            Money(amount), date, optionalText(item, "title").ifBlank { "Grab Payment" }, payload.toString())
    }

    fun enrichCharge(charge: UnifiedTransaction, ride: UnifiedTransaction): UnifiedTransaction {
        val payload = JSONObject(charge.rawPayload)
        val detail = JSONObject(ride.rawPayload)
        val booking = optionalText(payload, "sourcePartnerGroupTxID").ifBlank { optionalText(payload, "orderNumber") }
        if (payload.optBoolean("isTip") || booking.isBlank() || booking != optionalText(detail, "orderNumber")) return charge
        for (key in listOf("pickupLocation", "dropoffLocation", "paymentMethod", "serviceTier")) {
            optionalText(detail, key).takeIf(String::isNotBlank)?.let { payload.put(key, it) }
        }
        detail.optJSONArray("receiptPaymentMethods")?.let { payload.put("receiptPaymentMethods", it) }
        payload.put("bookingNumber", booking).put("orderMatchEvidence", "exact-booking-reference")
        return charge.copy(description = ride.description, rawPayload = payload.toString())
    }

    fun ride(ride: JSONObject, receiptRoot: JSONObject?): UnifiedTransaction? {
        val state = text(ride, "state")
        if (state in setOf("CANCELLED", "CANCELED", "FAILED")) return null
        if (state != "COMPLETED") fail("ride completion status")
        val id = text(ride, "bookingCode")
        val receipt = receiptRoot?.optJSONObject("response")
        if (receiptRoot != null && receipt == null) fail("ride receipt schema")
        val quote = if (receipt == null) ride.optJSONObject("quote") ?: fail("ride quote") else null
        val currency = if (receipt != null) receipt.optJSONObject("currency") ?: fail("ride receipt currency")
            else quote?.optJSONObject("currency") ?: fail("ride quote currency")
        if (text(currency, "code") != "IDR" || number(currency, "exponent") != 2L) fail("ride currency units")
        if (receipt != null && (text(receipt, "code") != id || text(receipt, "status") != "COMPLETED")) fail("ride receipt identity/status")
        val fare = if (receipt != null) receipt.optJSONObject("total") ?: fail("ride receipt amount")
            else quote?.optJSONObject("fare") ?: fail("ride quote amount")
        val amount = number(fare, "lowerBound").also { if (it <= 0 || it != number(fare, "upperBound")) fail("final ride amount range") }
        val date = if (receipt != null) Instant.parse(text(receipt, "completedAt"))
            else milliseconds(number(ride.optJSONObject("timeline") ?: fail("ride timestamp"), "completeTime"))
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
            val sourceMethods = JSONArray()
            for (i in 0 until payments.length()) {
                val payment = payments.optJSONObject(i) ?: fail("ride payment row")
                val detail = payment.optJSONObject("currencyDetail") ?: fail("ride payment currency")
                if (text(payment, "currency") != "IDR" || text(detail, "code") != "IDR" || number(detail, "exponent") != 2L) fail("ride payment units")
                val paid = number(payment, "amount").also { if (it < 0) fail("ride payment magnitude") }
                total = Math.addExact(total, paid)
                val method = payment.optJSONObject("method")
                val name = method?.let { optionalText(it, "name") }.orEmpty()
                methods += name
                sourceMethods.put(JSONObject().put("amountMinor", paid).put("currency", "IDR")
                    .put("name", name).put("type", method?.let { optionalText(it, "card_type") }.orEmpty()))
            }
            if (payments.length() == 0 || total != amount) fail("ride payment total")
            payload.put("paymentMethod", methods.filter(String::isNotBlank).distinct().joinToString(" + "))
            payload.put("receiptPaymentMethods", sourceMethods)
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
    private fun optionalText(obj: JSONObject, key: String): String = if (obj.isNull(key)) ""
        else obj.opt(key) as? String ?: fail(key)
    // Native TxnHistoryItem.amount is a double. Accept an exactly integral value,
    // while keeping fractions/overflow unsupported rather than rounding user money.
    private fun wholeRupiah(obj: JSONObject): Long = when (val value = obj.opt("amount")) {
        is Number, is String -> {
            // At the floating safe-integer boundary an odd source integer can already
            // have rounded into a different whole rupiah. Exact integer/string input
            // keeps its full range; it still passes the ISO multiplication overflow check.
            if (value is Double && kotlin.math.abs(value) >= 9_007_199_254_740_992.0 ||
                value is Float && kotlin.math.abs(value) >= 16_777_216f) fail("amount precision")
            try { value.toString().toBigDecimal().longValueExact() }
            catch (_: ArithmeticException) { fail("whole-rupiah amount") }
            catch (_: NumberFormatException) { fail("whole-rupiah amount") }
        }
        else -> fail("amount")
    }
    private fun number(obj: JSONObject, key: String): Long = when (val value = obj.opt(key)) {
        is Int, is Long -> (value as Number).toLong()
        is String -> value.toLongOrNull() ?: fail(key)
        else -> fail(key)
    }
    private fun fail(field: String): Nothing = throw ProviderReadException(AccountStatus.ERROR, "Grab $field is unsupported; saved history is kept")
}
