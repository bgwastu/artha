package net.wastu.artha.ui

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.reconciliation.LedgerReconciler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionGroupTest {
    private val charge = tx("charge", Direction.DEBIT, 5_000_000, "2026-10-22T03:07:00Z", """{"isQris":true,"merchantName":"Circle K"}""")
        .copy(isQris = true, cardLast4 = "8384")
    private val refund = tx("refund", Direction.CREDIT, 5_000_000, "2026-10-22T03:20:00Z", """{"merchantName":"Circle K"}""")

    @Test fun reversalRecordsKeepEachSideAndNetToZero() {
        val records = groupRecords(grouped(charge to "Original Charge", refund to "Refund"))
        assertEquals(listOf("Original charge", "Refund"), records.map { it.role })
        assertEquals(listOf(Direction.DEBIT, Direction.CREDIT), records.map { it.transaction.direction })
        assertEquals("Circle K", records.first().title)
        assertEquals(listOf(TransactionTagKind.QRIS, TransactionTagKind.CARD), records.first().tags.map { it.kind })
        assertEquals(0L, groupNetMinor(records))
    }

    @Test fun partialRefundNetIsWhatRemainsPaid() {
        val partial = refund.copy(money = Money(2_000_000))
        assertEquals(3_000_000L, groupNetMinor(groupRecords(grouped(charge to "Original Charge", partial to "Partial Refund"))))
    }

    @Test fun sameDirectionSourcesAreOnePaymentAndHaveNoNet() {
        val order = tx("order", Direction.DEBIT, 5_000_000, "2026-10-22T03:06:00Z", """{"shopName":"Tafaeri.ID"}""").copy(provider = Provider.SHOPEE)
        val merged = LedgerReconciler.mergeGroupedTransaction(order, charge)
        val records = groupRecords(merged)
        assertEquals(listOf("Order details", "Card payment"), records.map { it.role })
        assertNull(groupNetMinor(records))
    }

    @Test fun singleOrMalformedEntriesShowNoRecords() {
        assertEquals(emptyList<GroupRecord>(), groupRecords(grouped(charge to "Original Charge")))
        val broken = charge.copy(rawPayload = """{"groupedTransactions":[{"provider":"NOPE"},{"role":"Refund"}]}""")
        assertEquals(emptyList<GroupRecord>(), groupRecords(broken))
    }

    @Test fun transferRolesKeepTheirPocketNames() {
        assertEquals("From Main Pocket", linkedSourceRole(JSONObject("""{"role":"From Main Pocket"}""")))
        assertEquals("Source record", linkedSourceRole(JSONObject("""{"provider":"GRAB","role":"Trip / Ride"}""")))
    }

    @Test fun itemPricesUseTheAppMoneyFormat() {
        assertEquals(formatMinorAmount(java.math.BigInteger.valueOf(1_332_000)), net.wastu.artha.ui.screens.itemPriceText("Rp13320"))
        assertEquals(formatMinorAmount(java.math.BigInteger.valueOf(1_332_000)), net.wastu.artha.ui.screens.itemPriceText("13320"))
        assertEquals("13.320,50", net.wastu.artha.ui.screens.itemPriceText("13.320,50"))
    }

    private fun grouped(vararg entries: Pair<UnifiedTransaction, String>) = charge.copy(rawPayload = JSONObject()
        .put("groupedTransactions", JSONArray().apply { entries.forEach { (t, role) -> put(LedgerReconciler.buildGroupEntry(t, role)) } })
        .toString())

    private fun tx(id: String, direction: Direction, minor: Long, at: String, payload: String) =
        UnifiedTransaction(Provider.JAGO, id, direction, Money(minor), Instant.parse(at), "Fixture $id", payload)
}
