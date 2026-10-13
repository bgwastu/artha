package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.*
import net.wastu.artha.data.reconciliation.LedgerReconciler
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GrabProviderContractTest {
    private fun charge() = JSONObject("""{"status":"COMPLETED","currency":"IDR","txID":"test-charge","partnerGroupTxID":"booking-test","txType":"BOOKING_CHARGE","coreTxType":"BookingCharge","amount":50000,"timestamp":1790917200,"title":"Grab test"}""")
    private fun ride() = JSONObject("""{"state":"COMPLETED","bookingCode":"booking-test","quote":{"currency":{"code":"IDR","exponent":2},"fare":{"lowerBound":4900000,"upperBound":4900000}},"timeline":{"completeTime":1790917260000},"serviceInfo":{"name":"GrabCar"}}""")
    private fun receipt() = JSONObject("""{"response":{"code":"booking-test","status":"COMPLETED","currency":{"code":"IDR","exponent":2},"total":{"lowerBound":5000000,"upperBound":5000000},"completedAt":"2026-10-02T05:01:00Z","paymentDetails":[{"currency":"IDR","currencyDetail":{"code":"IDR","exponent":2},"amount":5000000,"method":{"name":"Card"}}],"incurredArrearInfo":null}}""")
    private fun failed(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun chargeUsesExplicitCompletedTypeAndExactWholeRupiahMagnitude() {
        val row = requireNotNull(GrabProviderParser.wallet(charge()))
        assertEquals(5000000L, row.money.amountMinor)
        assertEquals("grab:test-charge", row.providerTransactionId)
        assertTrue(LedgerReconciler.reconcileLedger(listOf(row)).single().isConfirmed)
        failed { GrabProviderParser.wallet(charge().put("amount", 50000.5)) }
        failed { GrabProviderParser.wallet(charge().put("amount", Long.MAX_VALUE)) }
        failed { GrabProviderParser.wallet(charge().put("currency", "USD")) }
    }

    @Test fun unknownDirectionsAndStatusesAreNotGuessed() {
        failed { GrabProviderParser.wallet(charge().put("txType", "REFUND").put("coreTxType", "Refund")) }
        failed { GrabProviderParser.wallet(charge().put("status", "UNKNOWN")) }
        assertNull(GrabProviderParser.wallet(charge().put("status", "PENDING")))
        failed { GrabProviderParser.wallet(charge().put("timestamp", 1790917200000L)) }
    }

    @Test fun tipIdentityIsDistinctAndNeverMergedIntoBookingCharge() {
        val tip = requireNotNull(GrabProviderParser.wallet(charge().put("txID", "tip-test").put("txType", "TIPPING_CHARGE").put("coreTxType", "TippingCharge")))
        assertEquals("grab:tip:tip-test", tip.providerTransactionId)
        assertEquals("tip-test", JSONObject(tip.rawPayload).getString("orderNumber"))
        val rows = LedgerReconciler.reconcileLedger(listOf(requireNotNull(GrabProviderParser.wallet(charge())), tip, requireNotNull(GrabProviderParser.ride(ride(), receipt()))))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.isConfirmed })
    }

    @Test fun cursorOnShortPageIsFollowedAndEmptyTerminalStops() {
        val page = GrabProviderParser.walletPage(JSONObject("""{"transactions":[{}],"lastElementID":123,"isPartialFailure":false}"""))
        assertEquals(123L, page.cursor)
        assertNull(GrabProviderParser.walletPage(JSONObject("""{"transactions":[],"lastElementID":123,"isPartialFailure":false}""")).cursor)
        failed { GrabProviderParser.walletPage(JSONObject("""{"transactions":[],"lastElementID":0}""")) }
    }

    @Test fun receiptAmountEnrichesQuoteButConfirmedPaymentGovernsDate() {
        val quoted = requireNotNull(GrabProviderParser.ride(ride(), null))
        val detailed = requireNotNull(GrabProviderParser.ride(ride(), receipt()))
        assertEquals(4900000L, quoted.money.amountMinor)
        assertEquals(5000000L, detailed.money.amountMinor)
        assertFalse(LedgerReconciler.reconcileLedger(listOf(detailed)).single().isConfirmed)
        val payment = requireNotNull(GrabProviderParser.wallet(charge()))
        val grouped = LedgerReconciler.reconcileLedger(listOf(payment, detailed)).single()
        assertEquals(payment.key, grouped.key)
        assertEquals(payment.money, grouped.money)
        assertEquals(payment.occurredAt, grouped.occurredAt)
    }

    @Test fun mismatchedReceiptAndPaymentUnitsAreRejected() {
        val wrongId = receipt().also { it.getJSONObject("response").put("code", "another-booking") }
        failed { GrabProviderParser.ride(ride(), wrongId) }
        val wrongAmount = receipt().also { it.getJSONObject("response").getJSONArray("paymentDetails").getJSONObject(0).put("amount", 50000) }
        failed { GrabProviderParser.ride(ride(), wrongAmount) }
        val wrongUnits = receipt().also { it.getJSONObject("response").getJSONObject("currency").put("exponent", 0) }
        failed { GrabProviderParser.ride(ride(), wrongUnits) }
    }

    @Test fun foodPaginationAndEmptyAccountAreExplicit() {
        assertEquals(0, GrabProviderParser.foodPage(JSONObject("""{"orders":[],"hasMore":false}""")).first.length())
        assertEquals(20, GrabProviderParser.foodPage(JSONObject("""{"orders":[{}],"hasMore":true,"nextOffset":20}""")).second)
        failed { GrabProviderParser.foodPage(JSONObject("""{"orders":[],"hasMore":true,"nextOffset":0}""")) }
    }

    @Test fun preferencesNeverExpandExternalEntities() {
        failed { GrabOrderReader.parsePreferencesXml("""<!DOCTYPE map [<!ENTITY leak SYSTEM "file:///private">]><map><string name="sessionId">&leak;</string></map>""".byteInputStream()) }
    }
}
