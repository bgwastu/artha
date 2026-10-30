package net.wastu.artha.data.ingestion

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fictional dumps laid out like Gojek 5.81.2's history list and transaction detail screens. */
class GojekScreenParserTest {
    private val today = LocalDate.of(2026, 1, 20)
    private val jakarta = ZoneId.of("Asia/Jakarta")

    @Test fun listRowsPairEachRowWithItsDateHeaderSignAndMethod() {
        val rows = GojekScreenParser.listRows(GojekScreenParser.nodes(LIST), today)
        assertEquals(4, rows.size)
        val interest = rows[0]
        assertEquals(LocalDate.of(2026, 1, 18), interest.date)
        assertEquals("Fictional Interest", interest.description)
        assertEquals("For January", interest.notes)
        assertEquals(200L, interest.amountMinor)
        assertEquals(Direction.CREDIT, interest.direction)
        assertEquals("Fictional Savings", interest.method)
        val market = rows[1]
        assertEquals(LocalDate.of(2026, 1, 17), market.date)
        assertEquals(1_900_000L, market.amountMinor)
        assertEquals(Direction.DEBIT, market.direction)
        assertEquals("Fictional Bank", market.method)
        assertNull(market.notes)
        assertEquals(LocalDate.of(2026, 1, 16), rows[2].date)
        assertEquals(LocalDate.of(2026, 1, 16), rows[3].date)
        assertEquals(9_610_000L, rows[2].amountMinor)
        assertEquals(511, rows[2].tapX)
        assertEquals(1516, rows[2].tapY)
    }

    @Test fun rowsCutOffAtTheEdgeOrAboveTheFirstHeaderAreReportedHonestly() {
        val rows = GojekScreenParser.listRows(GojekScreenParser.nodes(SCROLLED), today)
        assertEquals(1, rows.size)
        assertNull(rows.single().date)
        assertEquals("Fictional Cafe", rows.single().description)
    }

    @Test fun relativeHeadersResolveAgainstToday() {
        val xml = screen(node("2131385000", "Hari ini", 44, 652, 1036, 713) + row("Fictional Shop", "-Rp5.000", 768))
        assertEquals(today, GojekScreenParser.listRows(GojekScreenParser.nodes(xml), today).single().date)
    }

    @Test fun detailReadsSideBySideLabelsAndKeepsTheLedgerIdentity() {
        val detail = requireNotNull(GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL)))
        assertEquals("0920260116112106fictionalID", detail.transactionId)
        assertEquals(LocalDate.of(2026, 1, 16), detail.date)
        assertEquals(9_610_000L, detail.amountMinor)
        assertEquals("Fictional Bank", detail.method)
        assertEquals("Fictional Mart", detail.merchant)

        val row = GojekScreenParser.listRows(GojekScreenParser.nodes(LIST), today)[2]
        val tx = GojekScreenParser.transaction(row, detail, jakarta)
        assertEquals(Provider.GOJEK, tx.provider)
        assertEquals("gopay:0920260116112106fictionalID", tx.providerTransactionId)
        assertEquals(Direction.DEBIT, tx.direction)
        assertEquals(9_610_000L, tx.money.amountMinor)
        assertEquals(Instant.parse("2026-01-16T11:21:00Z"), tx.occurredAt)
        assertEquals("Fictional Mart", tx.description)
        assertFalse(tx.isQris)
        assertFalse(tx.isReversal)
        val payload = JSONObject(tx.rawPayload)
        assertEquals("gopay-order-history", payload.getString("origin"))
        assertEquals("gopay-screen", payload.getString("paymentEvidence"))
        assertEquals("minute", payload.getString("timePrecision"))
        assertEquals("Fictional Bank", payload.getString("paymentMethod"))
        assertFalse(payload.has("Order ID"))
    }

    @Test fun unsettledDetailsAreSkippedAndUnknownStatusesFail() {
        assertNull(GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("\"Selesai\"", "\"Diproses\""))))
        assertNull(GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("\"Selesai\"", "\"Gagal\""))))
        failure { GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("\"Selesai\"", "\"Ditinjau\""))) }
    }

    @Test fun detailWithoutIdentityTimeOrAmountIsRejected() {
        failure { GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("ID transaksi", "Kode"))) }
        failure { GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("\"18:21\"", "\"sore\""))) }
        failure { GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("\"16 Jan 2026\"", "\"16/01/2026\""))) }
        failure { GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("Rp96.100", "Rp?"))) }
        failure { GojekScreenParser.nodes("<hierarchy><node") }
    }

    @Test fun detailMustMatchTheTappedRow() {
        val detail = requireNotNull(GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL)))
        val rows = GojekScreenParser.listRows(GojekScreenParser.nodes(LIST), today)
        failure { GojekScreenParser.transaction(rows[1], detail, jakarta) }
        failure { GojekScreenParser.transaction(rows[2].copy(date = LocalDate.of(2026, 1, 15)), detail, jakarta) }
        assertNotNull(GojekScreenParser.transaction(rows[2].copy(date = null), detail, jakarta))
    }

    @Test fun creditRefundsAreReversalsAndQrisNeedsExplicitText() {
        val detail = requireNotNull(GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL)))
        val row = GojekScreenParser.listRows(GojekScreenParser.nodes(LIST), today)[2]
        val refund = GojekScreenParser.transaction(row.copy(direction = Direction.CREDIT, description = "Refund Fictional Mart"), detail, jakarta)
        assertTrue(refund.isReversal)
        val qris = GojekScreenParser.transaction(row.copy(notes = "Pembayaran QRIS"), detail, jakarta)
        assertTrue(qris.isQris)
        assertFalse(GojekScreenParser.transaction(row.copy(description = "Refund Fictional Mart"), detail, jakarta).isReversal)
        val scanned = requireNotNull(GojekScreenParser.detail(GojekScreenParser.nodes(DETAIL.replace("</hierarchy>", pair("Merchant PAN", "936000000000000002", 1480) + "</hierarchy>"))))
        assertTrue(scanned.qris)
        assertTrue(GojekScreenParser.transaction(row, scanned, jakarta).isQris)
    }

    @Test fun amountsAndDatesParseIndonesianAndEnglishForms() {
        assertEquals(1_900_000L to Direction.DEBIT, GojekScreenParser.amount("-Rp19.000"))
        assertEquals(200L to Direction.CREDIT, GojekScreenParser.amount("Rp2"))
        assertEquals(125_050L to Direction.CREDIT, GojekScreenParser.amount("+Rp1.250,50"))
        assertEquals(500_000L to Direction.DEBIT, GojekScreenParser.amount("−Rp 5.000"))
        assertNull(GojekScreenParser.amount("Rp0"))
        assertNull(GojekScreenParser.amount("Rp19.00"))
        assertNull(GojekScreenParser.amount("19.000"))
        assertEquals(LocalDate.of(2026, 10, 23), GojekScreenParser.date("23 Okt 2026"))
        assertEquals(LocalDate.of(2026, 8, 1), GojekScreenParser.date("Sabtu, 1 Agu 2026"))
        assertEquals(LocalDate.of(2026, 5, 4), GojekScreenParser.date("4 May 2026"))
        assertEquals(LocalDate.of(2026, 12, 31), GojekScreenParser.date("31 Desember 2026"))
        assertNull(GojekScreenParser.date("31 Feb 2026"))
        assertNull(GojekScreenParser.date("23 Foo 2026"))
    }

    private fun failure(block: () -> Unit) {
        try { block(); fail("Expected ProviderReadException") } catch (_: ProviderReadException) {}
    }

    private companion object {
        fun node(id: String, text: String, l: Int, t: Int, r: Int, b: Int, children: String = "") =
            """<node index="0" text="$text" resource-id="${if (id.isEmpty()) "" else "com.gojek.app:id/$id"}" class="android.widget.TextView" package="com.gojek.app" bounds="[$l,$t][$r,$b]">$children</node>"""

        fun header(text: String, top: Int) = node("2131385000", text, 44, top, 1036, top + 61)

        fun row(description: String, amount: String, top: Int, method: String? = null, notes: String? = null, amountFirst: Boolean = false): String {
            val amountNode = node("tvAmount", amount, 858, top, 1036, top + 53)
            val descNode = node("tvDescription", description, 187, top + 32, 836, top + 85)
            val notesNode = notes?.let { node("tvNotes", it, 187, top + 64, 635, top + 116) }.orEmpty()
            val methodNode = method?.let {
                node("rvPaymentMethods", "", 879, top + 75, 1036, top + 138, node("2131367178", it, 953, top + 75, 1036, top + 127))
            }.orEmpty()
            return node("2131361928", "", 44, top + 6, 149, top + 111) +
                (if (amountFirst) amountNode + descNode else descNode + amountNode) + notesNode + methodNode
        }

        fun screen(body: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">""" +
            node("navigation_back", "", 44, 44, 110, 110) + node("2131375656", "Transaction history", 132, 43, 1025, 111) +
            node("2131385773", "Tanggal", 88, 199, 222, 252) +
            node("2131384041", "Dapetin laporan pengeluaran, Download, yuk!", 341, 452, 882, 540) +
            body + "</hierarchy>"

        val LIST = screen(
            header("Minggu, 18 Jan 2026", 652) + row("Fictional Interest", "Rp2", 768, "Fictional Savings", "For January") +
                header("Sabtu, 17 Jan 2026", 994) + row("Fictional Shop", "-Rp19.000", 1110, "Fictional Bank", amountFirst = true) +
                header("Jumat, 16 Jan 2026", 1336) + row("Fictional Mart", "-Rp96.100", 1458, "Fictional Bank") +
                row("Fictional Shop", "-Rp22.000", 1665, "Fictional Bank") +
                node("tvAmount", "-Rp7.000", 850, 2300, 1036, 2340),
        )

        val SCROLLED = screen(row("Fictional Cafe", "-Rp35.000", 320, "Fictional Bank") + node("tvAmount", "-Rp1.000", 850, 2310, 1036, 2340))

        fun pair(label: String, value: String, top: Int) =
            node("2131386353", label, 44, top, 540, top + 52) + node("2131386352", value, 540, top, 1036, top + 52)

        val DETAIL = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">""" +
            node("2131386385", "Rp96.100", 0, 308, 1080, 388) + node("", "Fictional Mart ", 0, 400, 1080, 450) +
            node("", "Rincian Transaksi", 44, 600, 1036, 650) +
            pair("Status", "Selesai", 734) + pair("Metode pembayaran", "Fictional Bank", 810) +
            pair("Waktu", "18:21", 885) + pair("Tanggal", "16 Jan 2026", 959) +
            pair("ID transaksi", "0920260116112106fictionalID", 1033) + pair("Order ID", "11112222", 1107) +
            pair("Merchant Name", "Fictional Mart", 1181) + pair("Customer PAN", "9360000000000000001", 1606) +
            pair("Jumlah", "Rp96.100", 1735) + pair("Total", "Rp96.100", 1864) + "</hierarchy>"
    }
}
