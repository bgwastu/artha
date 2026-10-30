package net.wastu.artha.data.ingestion

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element

/**
 * Reads GoPay's own transaction screens (history list and transaction detail) from an
 * accessibility dump. Contracts verified on Gojek 5.81.2: the list exposes named view ids,
 * the detail screen only exposes label/value pairs laid out side by side.
 */
internal object GojekScreenParser {
    data class Node(val id: String, val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    /** One visible history row. [date] is null while the row's date header is scrolled off. */
    data class ListRow(
        val date: LocalDate?,
        val description: String,
        val notes: String?,
        val amountMinor: Long,
        val direction: Direction,
        val method: String?,
        val tapX: Int,
        val tapY: Int,
    )

    data class Detail(
        val transactionId: String,
        val date: LocalDate,
        val time: LocalTime,
        val amountMinor: Long,
        val method: String?,
        val merchant: String?,
        /** A national QRIS merchant PAN (9360…) is only shown for scanned merchant QR codes. */
        val qris: Boolean = false,
    )

    private const val ID_PREFIX = "com.gojek.app:id/"

    /** Parses `uiautomator dump` hierarchy XML into a flat node list in document order. */
    fun nodes(xml: String): List<Node> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            // Android's parser rejects this feature but never loads external entities anyway.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val document = try { factory.newDocumentBuilder().parse(xml.byteInputStream()) }
        catch (_: Exception) { throw ProviderReadException(AccountStatus.ERROR, "GoPay screen dump is unreadable") }
        val elements = document.getElementsByTagName("node")
        return (0 until elements.length).mapNotNull { i ->
            val element = elements.item(i) as Element
            val bounds = BOUNDS.matchEntire(element.getAttribute("bounds"))?.groupValues?.drop(1)?.map { it.toInt() } ?: return@mapNotNull null
            Node(element.getAttribute("resource-id").removePrefix(ID_PREFIX), element.getAttribute("text").trim(), bounds[0], bounds[1], bounds[2], bounds[3])
        }
    }

    fun listRows(nodes: List<Node>, today: LocalDate): List<ListRow> {
        val headers = nodes.mapNotNull { node -> headerDate(node.text, today)?.let { node to it } }.sortedBy { it.first.top }
        val amounts = nodes.filter { it.id == "tvAmount" }.sortedBy { it.top }
        return amounts.mapIndexedNotNull { index, amount ->
            val rowTop = amount.top - ROW_SLACK
            val rowBottom = listOfNotNull(
                amounts.getOrNull(index + 1)?.top,
                headers.firstOrNull { it.first.top > amount.bottom }?.first?.top,
            ).minOrNull()?.minus(ROW_SLACK) ?: Int.MAX_VALUE
            fun inRow(node: Node) = node.top >= rowTop && node.top < rowBottom
            // A row cut off at the screen edge has no description yet; the walk sees it after scrolling.
            val description = nodes.firstOrNull { it.id == "tvDescription" && it.text.isNotEmpty() && inRow(it) } ?: return@mapIndexedNotNull null
            val signed = amount(amount.text) ?: return@mapIndexedNotNull null
            val methods = nodes.filter { it.id == "rvPaymentMethods" && inRow(it) }
            val method = nodes.firstOrNull { node -> node.text.isNotEmpty() && methods.any { it.contains(node) } }?.text
            ListRow(
                date = headers.lastOrNull { it.first.bottom <= amount.top }?.second,
                description = description.text,
                notes = nodes.firstOrNull { it.id == "tvNotes" && it.text.isNotEmpty() && inRow(it) }?.text,
                amountMinor = signed.first,
                direction = signed.second,
                method = method,
                tapX = description.centerX,
                tapY = description.centerY,
            )
        }
    }

    /** Returns null for transactions that have not settled, mirroring the API path's pending/failed skip. */
    fun detail(nodes: List<Node>): Detail? {
        fun value(vararg labels: String): String? {
            val label = nodes.firstOrNull { node -> labels.any { it.equals(node.text, ignoreCase = true) } } ?: return null
            return nodes.firstOrNull { it !== label && it.text.isNotEmpty() && it.left >= label.right - ROW_SLACK && it.centerY in label.top..label.bottom }?.text
        }
        val id = value("ID transaksi", "Transaction ID")
        schema(id != null && TRANSACTION_ID.matches(id), "GoPay detail screen has no transaction ID")
        val status = value("Status")?.lowercase(Locale.ROOT)
        schema(status != null, "GoPay detail screen has no status")
        if (status !in SETTLED) {
            schema(status in UNSETTLED, "GoPay detail status is unrecognized")
            return null
        }
        val date = value("Tanggal", "Date")?.let { date(it) }
        schema(date != null, "GoPay detail date is missing or unrecognized")
        val time = value("Waktu", "Time")?.let { TIME.matchEntire(it) }?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        schema(time != null, "GoPay detail time is missing or unrecognized")
        val amount = (value("Jumlah", "Amount") ?: value("Total"))?.let { amount(it) }
        schema(amount != null, "GoPay detail amount is missing or unrecognized")
        return Detail(requireNotNull(id), requireNotNull(date), requireNotNull(time), requireNotNull(amount).first,
            value("Metode pembayaran", "Payment method"), value("Merchant Name"),
            qris = value("Merchant PAN")?.let { QRIS_MERCHANT_PAN.matches(it) } == true)
    }

    /** Combines the detail screen (identity, time) with the list row it was opened from (direction, description). */
    fun transaction(row: ListRow, detail: Detail, zone: ZoneId): UnifiedTransaction {
        schema(row.amountMinor == detail.amountMinor, "GoPay detail amount does not match the tapped row")
        schema(row.date == null || row.date == detail.date, "GoPay detail date does not match the tapped row")
        val occurredAt = detail.date.atTime(detail.time).atZone(zone).toInstant()
        val method = detail.method ?: row.method
        val texts = listOfNotNull(row.description, row.notes, detail.merchant)
        val qris = detail.qris || texts.any { it.contains("QRIS", ignoreCase = true) }
        val reversal = row.direction == Direction.CREDIT && texts.any { text -> REFUND_WORDS.any { text.contains(it, ignoreCase = true) } }
        val payload = JSONObject().put("origin", "gopay-order-history").put("provider", Provider.GOJEK.name)
            .put("orderNumber", detail.transactionId).put("isQris", qris)
            .put("paymentMethod", method ?: "GoPay")
            .put("paymentMethods", JSONArray().apply { if (method != null) put(JSONObject().put("name", method)) })
            .put("paymentStatus", "COMPLETED").put("paymentDirection", row.direction.name)
            .put("paymentAmountMinor", detail.amountMinor).put("paymentCurrency", "IDR")
            .put("paymentTimestamp", occurredAt.toString()).put("timePrecision", "minute")
            .put("paymentEvidence", "gopay-screen")
        detail.merchant?.let { payload.put("merchantName", it) }
        row.notes?.let { payload.put("notes", it) }
        return UnifiedTransaction(Provider.GOJEK, "gopay:${detail.transactionId}", row.direction, Money(detail.amountMinor), occurredAt,
            row.description, payload.toString(), isQris = qris, isReversal = reversal)
    }

    /** `-Rp19.000`, `Rp2`, `+Rp1.250,50` → positive minor units plus direction (unsigned means credit on the list). */
    fun amount(text: String): Pair<Long, Direction>? {
        val match = AMOUNT.matchEntire(text.replace('−', '-').replace(" ", "").replace(" ", "")) ?: return null
        val rupiah = match.groupValues[2].replace(".", "").toLongOrNull() ?: return null
        val cents = match.groupValues[3].ifEmpty { "0" }.padEnd(2, '0').toLong()
        if (rupiah > Long.MAX_VALUE / 100 - 1) return null
        val minor = rupiah * 100 + cents
        if (minor <= 0) return null
        return minor to if (match.groupValues[1] == "-") Direction.DEBIT else Direction.CREDIT
    }

    /** `23 Okt 2026`, `Sabtu, 24 Okt 2026`, `24 October 2026`. */
    fun date(text: String): LocalDate? {
        val match = DATE.matchEntire(text.substringAfter(',').trim()) ?: return null
        val month = MONTHS[match.groupValues[2].take(3).lowercase(Locale.ROOT)] ?: return null
        return runCatching { LocalDate.of(match.groupValues[3].toInt(), month, match.groupValues[1].toInt()) }.getOrNull()
    }

    private fun headerDate(text: String, today: LocalDate): LocalDate? = when (text.lowercase(Locale.ROOT)) {
        "hari ini", "today" -> today
        "kemarin", "yesterday" -> today.minusDays(1)
        else -> if (text.contains(',')) date(text) else null
    }

    private fun Node.contains(other: Node) = other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    private fun schema(valid: Boolean, message: String) { if (!valid) throw ProviderReadException(AccountStatus.ERROR, message) }

    private const val ROW_SLACK = 40
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private val AMOUNT = Regex("""([+-]?)Rp(\d{1,3}(?:\.\d{3})*|\d+)(?:,(\d{1,2}))?""")
    private val DATE = Regex("""(\d{1,2}) ([A-Za-z]{3,9}) (\d{4})""")
    private val TIME = Regex("""(\d{1,2})[:.](\d{2})""")
    private val QRIS_MERCHANT_PAN = Regex("""9360\d{12,15}""")
    private val TRANSACTION_ID = Regex("""[A-Za-z0-9-]{8,64}""")
    private val SETTLED = setOf("selesai", "berhasil", "completed", "success", "successful")
    private val UNSETTLED = setOf("diproses", "menunggu", "pending", "processing", "gagal", "dibatalkan", "failed", "cancelled", "canceled")
    private val REFUND_WORDS = listOf("refund", "pengembalian dana")
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mei" to 5, "may" to 5, "jun" to 6, "jul" to 7,
        "agu" to 8, "agt" to 8, "aug" to 8, "sep" to 9, "okt" to 10, "oct" to 10, "nov" to 11, "des" to 12, "dec" to 12,
    )
}
