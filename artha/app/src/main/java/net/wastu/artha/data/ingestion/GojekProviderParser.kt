package net.wastu.artha.data.ingestion

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import org.brotli.dec.BrotliInputStream
import org.json.JSONArray
import org.json.JSONObject

/** Contracts verified against Gojek's native cache and installed Retrofit/session classes. */
internal object GojekProviderParser {
    const val HISTORY_URL = "https://customer.gopayapi.com/v2/users/order-history?country_code=ID"
    private val DETAIL_FETCH_SERVICES = setOf(
        "GORIDE", "GOCAR", "GOFOOD", "GOSEND", "GOBLUEBIRD", "COLLECT", "GOPAY_MERCHANT", "MERCHANT_PAYMENT",
    )
    const val ORDERS_URL = "https://api.gojekapi.com/orderbff/v1/customer/card/history?limit=20"
    const val MAX_BODY_BYTES = 4 * 1024 * 1024

    enum class CacheKind { PAYMENTS, DETAILS, ORDERS }
    data class CacheEntry(val url: String, val kind: CacheKind, val status: Int, val encoding: String, val receivedMillis: Long)
    data class PaymentPage(val items: List<JSONObject>, val next: String?, val malformedRows: Int)
    data class NativeOrder(val number: String, val statusCode: Int, val serviceTypeCode: Int, val description: String?)
    data class OrderPage(val orders: List<NativeOrder>, val malformedRows: Int)

    fun cacheEntry(bytes: ByteArray): CacheEntry? {
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().toList()
        val url = lines.firstOrNull() ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!safeOrigin(uri)) return null
        val kind = when {
            uri.host == "customer.gopayapi.com" && uri.path == "/v2/users/order-history" -> CacheKind.PAYMENTS
            uri.host == "customer.gopayapi.com" && uri.path.matches(Regex("/v2/users/order-history/[^/]+/details")) -> CacheKind.DETAILS
            uri.host == "api.gojekapi.com" && uri.path == "/orderbff/v1/customer/card/history" -> CacheKind.ORDERS
            else -> return null
        }
        schema(lines.getOrNull(1) == "GET", "Unrecognized Gojek cache request method")
        val varyCount = lines.getOrNull(2)?.toIntOrNull()
        schema(varyCount != null && varyCount in 0..100, "Unrecognized Gojek cache metadata")
        val statusIndex = 3 + requireNotNull(varyCount)
        val status = lines.getOrNull(statusIndex)?.let { Regex("HTTP/[^ ]+ ([0-9]{3})(?: .*)?").matchEntire(it)?.groupValues?.get(1)?.toInt() }
        schema(status != null, "Unrecognized Gojek cache response status")
        val count = lines.getOrNull(statusIndex + 1)?.toIntOrNull()
        schema(count != null && count in 0..200, "Unrecognized Gojek cache response headers")
        val headers = lines.drop(statusIndex + 2).take(requireNotNull(count)).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon < 0) null else line.substring(0, colon).lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }.toMap()
        return CacheEntry(url, kind, requireNotNull(status), headers["content-encoding"].orEmpty(), headers["okhttp-received-millis"]?.toLongOrNull() ?: 0)
    }

    fun decodeBody(bytes: ByteArray, encoding: String, checkpoint: () -> Unit = {}): JSONObject {
        checkpoint()
        schema(bytes.size <= MAX_BODY_BYTES, "Gojek response exceeds the import size limit")
        return try {
            val input = when (encoding.trim().lowercase(Locale.ROOT)) {
                "", "identity" -> if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()) else bytes.inputStream()
                "gzip" -> GZIPInputStream(bytes.inputStream())
                "br" -> BrotliInputStream(bytes.inputStream())
                else -> throw ProviderReadException(AccountStatus.ERROR, "Gojek response has an unsupported content encoding")
            }
            val text = readBounded(input, checkpoint).toString(Charsets.UTF_8)
            checkpoint()
            JSONObject(text).also { checkpoint() }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: ProviderReadException) { throw e }
        catch (_: Exception) { throw ProviderReadException(AccountStatus.ERROR, "Gojek response is missing or unrecognized") }
    }

    fun readBounded(input: InputStream, checkpoint: () -> Unit = {}): ByteArray = input.use { stream ->
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkpoint()
            val count = stream.read(buffer)
            checkpoint()
            if (count < 0) break
            schema(result.size() + count <= MAX_BODY_BYTES, "Gojek response exceeds the import size limit")
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    }

    /** A trickling source cannot extend the response budget indefinitely between idle timeouts. */
    fun remainingTimeoutMillis(deadlineNanos: Long, maxMillis: Int, nowNanos: Long = System.nanoTime()): Int {
        val remaining = deadlineNanos - nowNanos
        schema(remaining > 0, "GoPay history request exceeded the time limit; valid payments are retained")
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).coerceAtMost(maxMillis.toLong()).toInt()
    }

    fun paymentPage(root: JSONObject, checkpoint: () -> Unit = {}): PaymentPage {
        checkpoint()
        val data = responseData(root)
        val groups = data.optJSONArray("completed")
        schema(groups != null, "GoPay completed history schema changed")
        val items = mutableListOf<JSONObject>()
        var malformed = 0
        for (i in 0 until requireNotNull(groups).length()) {
            checkpoint()
            val groupItems = groups.optJSONObject(i)?.optJSONArray("items")
            if (groupItems == null) { malformed++; continue }
            for (j in 0 until groupItems.length()) {
                checkpoint()
                if (items.size >= 2000) { malformed++; break }
                val item = groupItems.optJSONObject(j)
                if (item == null) malformed++ else items += item
            }
        }
        val cursor = data.opt("next")
        if (cursor != null && cursor != JSONObject.NULL && cursor !is String) malformed++
        // Validate the destination after importing this page, so a bad cursor cannot discard valid rows.
        return PaymentPage(items, (cursor as? String)?.takeIf { it.isNotBlank() }, malformed)
    }

    /** Never send the native bearer to a redirect, foreign host, or another API path. */
    fun historyCursor(value: String): String {
        val uri = runCatching { URI(HISTORY_URL).resolve(value) }.getOrNull()
        schema(uri != null && safeOrigin(uri) && uri.host == "customer.gopayapi.com" && uri.path == "/v2/users/order-history", "GoPay returned an unrecognized pagination destination")
        return requireNotNull(uri).toASCIIString()
    }

    fun payment(item: JSONObject): UnifiedTransaction? {
        val status = requiredText(item, "status")
        if (status in setOf("PENDING", "PROCESSING", "IN_PROGRESS", "FAILED", "CANCELLED", "CANCELED")) return null
        schema(status == "COMPLETED", "GoPay settlement status is unrecognized")
        val id = requiredText(item, "order_id")
        val direction = when (requiredText(item, "payment_type")) {
            "DEBIT" -> Direction.DEBIT
            "CREDIT" -> Direction.CREDIT
            else -> throw ProviderReadException(AccountStatus.ERROR, "GoPay payment direction is unrecognized")
        }
        val amount = item.optJSONObject("amount")
        schema(amount != null && amount.opt("currency") == "IDR", "GoPay currency is missing or unsupported")
        val value = requireNotNull(amount).opt("value")
        schema(value is Number, "GoPay numeric payment amount is missing")
        val minor = try { BigDecimal(requireNotNull(value).toString()).movePointRight(2).longValueExact() }
        catch (_: Exception) { throw ProviderReadException(AccountStatus.ERROR, "GoPay payment amount is not exact or exceeds the supported range") }
        schema(minor > 0, "GoPay payment magnitude must be positive")
        // Android JSON decimals use floating numbers; reject magnitudes where source cents may already be lost.
        schema(value !is Double || (value.isFinite() && minor < (1L shl 53)), "GoPay floating payment amount exceeds exact minor-unit precision")
        schema(value !is Float || (value.isFinite() && minor < (1L shl 24)), "GoPay floating payment amount exceeds exact minor-unit precision")
        val sourceTime = paymentTimestamp(requiredText(item, "order_timestamp"))
        // Room's event time is millisecond precision. Keep the source precision separately.
        val occurredAt = Instant.ofEpochMilli(sourceTime.toEpochMilli())
        val service = item.optString("service_type")
        val reversal = service == "REFUND" || service.endsWith("_REFUND")
        schema(!reversal || direction == Direction.CREDIT, "GoPay refund has no explicit credit direction")
        val methods = JSONArray()
        val breakup = item.optJSONArray("payment_method_breakup")
        for (i in 0 until (breakup?.length() ?: 0)) {
            val method = breakup?.optJSONObject(i) ?: continue
            methods.put(JSONObject().put("method", method.optString("payment_method")).put("name", method.optString("payment_method_name")))
        }
        // COLLECT includes merchant payments; the service name alone does not establish QRIS.
        val qris = item.optString("payment_transfer_type").contains("QRIS", true) ||
            (0 until methods.length()).any { methods.getJSONObject(it).optString("method").equals("QRIS", true) }
        val payload = JSONObject().put("origin", "gopay-order-history").put("provider", Provider.GOJEK.name)
            .put("orderNumber", id).put("serviceTier", service).put("isQris", qris)
            .put("paymentMethod", methods.optJSONObject(0)?.optString("name").orEmpty().ifBlank { "GoPay" })
            .put("paymentMethods", methods).put("paymentStatus", status).put("paymentDirection", direction.name)
            .put("paymentAmountMinor", minor).put("paymentCurrency", "IDR").put("paymentTimestamp", occurredAt.toString())
            .put("sourcePaymentTimestamp", sourceTime.toString())
            .put("paymentEvidence", "settled-wallet-history")
        return UnifiedTransaction(Provider.GOJEK, "gopay:$id", direction, Money(minor), occurredAt,
            item.optString("description").ifBlank { "GoPay Payment" }, payload.toString(), isQris = qris, isReversal = reversal)
    }

    /** When GoPay history APIs return GoPay-1000, keep bank↔wallet fusion using Jago's order reference. */
    fun paymentFromJagoLinkedDebit(jago: UnifiedTransaction, orderId: String): UnifiedTransaction {
        schema(jago.provider == Provider.JAGO && jago.direction == Direction.DEBIT, "Jago-linked GoPay hints require a settled debit")
        val source = JSONObject(jago.rawPayload)
        val code = source.optString("code")
        schema(code in setOf("GPD01", "GPD05"), "Jago-linked GoPay hints require a GoPay debit code")
        val fields = source.optJSONObject("fields")
        val counterpart = source.optString("counterpart").ifBlank { fields?.optString("f17").orEmpty() }.trim()
        val notes = source.optString("notes")
        val localized = source.optString("localized")
        val qris = source.optBoolean("isQris") ||
            jago.isQris ||
            listOf(counterpart, jago.description, notes, localized).any { it.contains("QRIS", ignoreCase = true) }
        val merchant = merchantFromJagoHint(counterpart, jago.description, localized, notes)
        val description = when {
            merchant != null -> gopayDisplayDescription(merchant, qris)
            jago.description.isNotBlank() && !jagoHintNoise(jago.description) -> jago.description
            localized.isNotBlank() && !jagoHintNoise(localized) -> localized
            else -> "GoPay Payment"
        }
        val payload = JSONObject()
            .put("origin", "gopay-order-history")
            .put("provider", Provider.GOJEK.name)
            .put("orderNumber", orderId)
            .put("isQris", qris)
            .put("paymentEvidence", "jago-linked-order-reference")
            .put("jagoProviderTransactionId", jago.providerTransactionId)
            .put("counterpart", counterpart)
            .put("notes", notes)
            .put("localized", localized)
        source.optString("paymentMethod").takeIf { it.isNotBlank() }?.let { payload.put("paymentMethod", it) }
        merchant?.let { payload.put("merchantName", it) }
        val detailItems = JSONArray()
        if (merchant != null) detailItems.put(JSONObject().put("title", "Merchant").put("value", merchant))
        if (qris) detailItems.put(JSONObject().put("title", "Payment method").put("value", "QRIS"))
        notes.trim().takeIf { it.isNotBlank() }?.let { detailItems.put(JSONObject().put("title", "Notes").put("value", it)) }
        if (detailItems.length() > 0) mergeDetailViewItems(payload, detailItems, includeMerchant = false)
        return UnifiedTransaction(
            provider = Provider.GOJEK,
            providerTransactionId = "gopay:$orderId",
            direction = Direction.DEBIT,
            money = jago.money,
            occurredAt = jago.occurredAt,
            description = description,
            rawPayload = payload.toString(),
            isQris = qris,
        )
    }

    private fun merchantFromJagoHint(counterpart: String, title: String, localized: String, notes: String): String? {
        for (candidate in listOf(title, localized, counterpart, notes)) {
            val trimmed = candidate.trim()
            if (jagoHintNoise(trimmed) || trimmed.isBlank()) continue
            val withoutQris = trimmed.replace(Regex("(?i)^qris\\s*[-:]?\\s*"), "").trim()
            if (withoutQris.isNotBlank() && !withoutQris.equals("QRIS", ignoreCase = true)) return withoutQris
        }
        return null
    }

    /**
     * Jago placeholders that name the wallet rather than the counterparty. GoPay Savings
     * debits carry "004 - Gopay - Generic Jago payment" as their only text; promoting that
     * note to a merchant produced titles that name nothing.
     */
    private fun jagoHintNoise(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return true
        if (trimmed.equals("GoPay Payment", ignoreCase = true) || trimmed.equals("GoPay", ignoreCase = true)) return true
        if (trimmed.equals("QRIS Payment", ignoreCase = true)) return true
        if (trimmed.contains("Generic Jago payment", ignoreCase = true)) return true
        if (trimmed.equals("GoPay Tabungan Transaction", ignoreCase = true) || trimmed.equals("Transaksi GoPay Tabungan", ignoreCase = true)) return true
        if (trimmed.equals("GoPay Savings - Non Tokopedia", ignoreCase = true)) return true
        return false
    }

    fun orderCards(root: JSONObject): List<NativeOrder> = orderPage(root).orders

    /** Non-order display widgets are expected; invalid order actions cannot discard valid neighbors. */
    fun orderPage(root: JSONObject, checkpoint: () -> Unit = {}): OrderPage {
        checkpoint()
        val cards = responseData(root).optJSONArray("cards")
        schema(cards != null, "Gojek order card schema changed")
        val orders = mutableListOf<NativeOrder>()
        var malformed = 0
        for (index in 0 until requireNotNull(cards).length()) {
            checkpoint()
            if (index >= 2000) { malformed++; break }
            val card = cards.optJSONObject(index)
            if (card == null) { malformed++; continue }
            val payload = card.optJSONObject("payload") ?: continue
            val action = payload.optJSONObject("on_click")?.optJSONObject("action")?.optJSONObject("data") ?: continue
            if (!action.has("order_number")) continue
            val number = action.opt("order_number") as? String
            val status = integerCode(action, "order_status")
            val service = integerCode(action, "service_type")
            if (number.isNullOrBlank() || status == null || service == null) { malformed++; continue }
            orders += NativeOrder(number, status, service,
                payload.optJSONObject("estate")?.optJSONArray("middle_column")?.optJSONObject(0)?.optJSONObject("payload")?.optString("text")?.takeIf { it.isNotBlank() })
        }
        return OrderPage(orders, malformed)
    }

    private fun integerCode(value: JSONObject, key: String): Int? {
        val code = value.opt(key) as? Number ?: return null
        return try { BigDecimal(code.toString()).intValueExact() } catch (_: Exception) { null }
    }

    fun detail(root: JSONObject): JSONObject = responseData(root)

    /** ID history returns Jakarta wall times; cached receipt displays corroborate this contract. */
    fun paymentTimestamp(value: String): Instant {
        // Require actual seconds, preserve fractional precision, and never infer a date/time component.
        schema(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})?")),
            "GoPay payment timestamp is missing or unrecognized")
        return try {
            try { OffsetDateTime.parse(value).toInstant() }
            catch (_: DateTimeParseException) { LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Jakarta")).toInstant() }
        } catch (_: DateTimeParseException) {
            throw ProviderReadException(AccountStatus.ERROR, "GoPay payment timestamp is missing or unrecognized")
        }
    }

    fun shouldFetchPaymentDetail(transaction: UnifiedTransaction): Boolean {
        val payload = runCatching { JSONObject(transaction.rawPayload) }.getOrNull()
        val service = payload?.optString("serviceTier").orEmpty()
        if (transaction.isQris || service in DETAIL_FETCH_SERVICES) return true
        val description = transaction.description.trim()
        return description.isBlank() || description.equals("GoPay Payment", ignoreCase = true)
    }

    /** Details may enrich an existing payment only after its financial fields agree exactly. */
    fun enrich(transaction: UnifiedTransaction, detail: JSONObject, orders: List<NativeOrder>): UnifiedTransaction {
        val checked = payment(JSONObject(detail.toString()).put("description", transaction.description))
        schema(checked != null && checked.providerTransactionId == transaction.providerTransactionId && checked.direction == transaction.direction &&
            checked.money == transaction.money && checked.occurredAt == transaction.occurredAt, "GoPay detail does not match its settled payment")
        val values = detail.optJSONArray("detail_view_items") ?: return transaction
        val bookingNumbers = (0 until values.length()).mapNotNull { values.optJSONObject(it)?.opt("value") as? String }.toSet()
        val matches = orders.distinctBy { it.number }.filter { it.number in bookingNumbers }
        val payload = JSONObject(transaction.rawPayload)
        if (matches.size == 1) {
            val order = matches.single()
            payload.put("bookingNumber", order.number).put("orderStatusCode", order.statusCode).put("nativeServiceTypeCode", order.serviceTypeCode)
                .put("orderMatchEvidence", "exact-booking-number-in-payment-detail")
            order.description?.let { payload.put("orderDisplayDescription", it) }
        }
        val qris = transaction.isQris || (0 until values.length()).any { index ->
            val value = values.optJSONObject(index)?.optString("value").orEmpty()
            value.equals("QRIS", true) || value.equals("Pembayaran QRIS", true)
        }
        payload.put("isQris", qris)
        mergeDetailViewItems(payload, values, matches.isEmpty())
        val merchant = payload.optString("merchantName").takeIf { it.isNotBlank() }
        val display = payload.optString("orderDisplayDescription").takeIf { it.isNotBlank() }
        val description = when {
            merchant != null -> gopayDisplayDescription(merchant, qris)
            display != null && transaction.description.equals("GoPay Payment", ignoreCase = true) -> display
            else -> transaction.description
        }
        return transaction.copy(description = description, rawPayload = payload.toString(), isQris = qris)
    }

    private fun gopayDisplayDescription(merchant: String, qris: Boolean): String {
        val trimmed = merchant.trim()
        if (!qris) return trimmed
        return if (trimmed.contains("QRIS", ignoreCase = true)) trimmed else "QRIS $trimmed"
    }

    internal fun mergeDetailViewItems(payload: JSONObject, items: JSONArray, includeMerchant: Boolean) {
        val copied = JSONArray()
        for (index in 0 until items.length()) {
            items.optJSONObject(index)?.let { copied.put(JSONObject(it.toString())) }
        }
        if (copied.length() > 0) payload.put("detailViewItems", copied)
        detailValue(items, "payment method", "metode pembayaran", "payment method")?.let { method ->
            val current = payload.optString("paymentMethod")
            if (current.isBlank() || current.equals("GoPay", ignoreCase = true)) payload.put("paymentMethod", method)
        }
        if (!includeMerchant) return
        merchantFromDetailItems(items)?.let { merchant ->
            payload.put("merchantName", merchant)
            if (payload.optString("counterpart").isBlank()) payload.put("counterpart", merchant)
        }
    }

    private fun merchantFromDetailItems(items: JSONArray): String? {
        for (index in 0 until items.length()) {
            val row = items.optJSONObject(index) ?: continue
            val title = row.optString("title").ifBlank { row.optString("label") }.trim()
            val value = row.optString("value").trim()
            if (value.isBlank() || isDetailNoise(value)) continue
            if (title.contains("merchant", true) || title.contains("toko", true) ||
                title.contains("nama", true) || title.contains("store", true)) {
                return value
            }
        }
        for (index in 0 until items.length()) {
            val value = items.optJSONObject(index)?.optString("value")?.trim().orEmpty()
            if (value.isNotBlank() && !isDetailNoise(value)) return value
        }
        return null
    }

    private fun detailValue(items: JSONArray, vararg titleHints: String): String? {
        for (index in 0 until items.length()) {
            val row = items.optJSONObject(index) ?: continue
            val title = row.optString("title").ifBlank { row.optString("label") }.trim()
            if (titleHints.none { hint -> title.contains(hint, ignoreCase = true) }) continue
            val value = row.optString("value").trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun isDetailNoise(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.equals("QRIS", true) || normalized.equals("Pembayaran QRIS", true)) return true
        if (normalized.equals("GoPay", true) || normalized.equals("GOPAY", true)) return true
        if (normalized.equals("COMPLETED", true) || normalized.equals("SUCCESS", true)) return true
        return false
    }

    fun preferences(bytes: ByteArray): Map<String, String> {
        schema(bytes.size <= MAX_BODY_BYTES, "Gojek preferences exceed the import size limit")
        val text = bytes.toString(Charsets.UTF_8)
        schema(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true), "Gojek preferences contain unsupported XML declarations")
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val document = factory.newDocumentBuilder().parse(bytes.inputStream())
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    /** Installed SCP session store (o.zLd/o.zTd); this decodes local storage, never refreshes it. */
    fun decryptAccessToken(encrypted: String): String = try {
        val key = intArrayOf(49, 53, 101, 90, 43, 49, 75, 45, 101, 71, 64, 38, 118, 68, 106, 102).map { it.toByte() }.toByteArray()
        val iv = intArrayOf(114, 98, 52, 55, 86, 60, 109, 51, 93, 62, 45, 95, 54, 88, 98, 110).map { it.toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val token = cipher.doFinal(Base64.getDecoder().decode(encrypted.filterNot { it.isWhitespace() })).toString(Charsets.UTF_8)
        require(token.isNotBlank() && token.none { it.isWhitespace() })
        token
    } catch (_: Exception) { throw ProviderReadException(AccountStatus.NEEDS_LOGIN, "Gojek login data is missing or unreadable. Open Gojek and log in again.") }

    private fun responseData(root: JSONObject): JSONObject {
        schema(root.opt("success") == true, "Gojek history response reports a failure")
        return root.optJSONObject("data") ?: throw ProviderReadException(AccountStatus.ERROR, "Gojek history response schema changed")
    }
    private fun requiredText(objectValue: JSONObject, key: String): String {
        val value = objectValue.opt(key) as? String
        schema(!value.isNullOrBlank(), "GoPay required payment field is missing: $key")
        return requireNotNull(value)
    }
    private fun safeOrigin(uri: URI): Boolean = uri.scheme == "https" && uri.host in setOf("customer.gopayapi.com", "api.gojekapi.com") &&
        uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)
    private fun schema(valid: Boolean, message: String) { if (!valid) throw ProviderReadException(AccountStatus.ERROR, message) }
}
