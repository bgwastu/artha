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
        assertTrue(GrabProviderParser.walletPage(JSONObject("""{"transactions":[],"lastElementID":0}""")).partial)
        failed { GrabProviderParser.walletPage(JSONObject("""{"transactions":[],"lastElementID":0,"isPartialFailure":"false"}""")) }
    }

    @Test fun nativeNullableCursorAndMissingCompletenessRemainPartial() {
        // Installed c.g.yno1 has a nullable cursor and no completeness field.
        val page = GrabProviderParser.walletPage(JSONObject("""{"transactions":[{}],"lastElementID":null}"""))
        assertNull(page.cursor)
        assertTrue(page.partial)
        assertTrue(GrabProviderParser.walletPage(JSONObject("""{"transactions":[]}""")).partial)
        failed { GrabProviderParser.walletPage(JSONObject("""{"transactions":[],"lastElementID":1.5}""")) }
    }

    @Test fun nativeDoubleAmountAllowsOnlyExactWholeRupiah() {
        val original = requireNotNull(GrabProviderParser.wallet(charge()))
        for (value in listOf(50000.0, "50000.000")) {
            assertEquals(original.money, requireNotNull(GrabProviderParser.wallet(charge().put("amount", value))).money)
        }
        for (value in listOf("50000.001", "9223372036854775808", "NaN", 0, -1)) {
            failed { GrabProviderParser.wallet(charge().put("amount", value)) }
        }
        for (value in listOf(9_007_199_254_740_992.0, 9_007_199_254_740_994.0, 16_777_216f)) {
            failed { GrabProviderParser.wallet(charge().put("amount", value)) }
        }
        val exact = 9_007_199_254_740_993L
        assertEquals(exact * 100, requireNotNull(GrabProviderParser.wallet(charge().put("amount", exact))).money.amountMinor)
        assertEquals(exact * 100, requireNotNull(GrabProviderParser.wallet(charge().put("amount", exact.toString()))).money.amountMinor)
    }

    @Test fun foodWalletChargeCountsDirectlyWithoutFoodReceipt() {
        val row = requireNotNull(GrabProviderParser.wallet(charge().put("partnerID", "GrabFood")
            .put("payMethod", "GrabPay Credits").put("source", "GRABMONEY")))
        val payload = JSONObject(row.rawPayload)
        assertEquals("GrabFood", payload.getString("serviceTier"))
        assertEquals("GrabPay Credits", payload.getString("sourcePaymentMethod"))
        assertEquals("GRABMONEY", payload.getString("sourceChargeSource"))
        val saved = LedgerReconciler.reconcileLedger(listOf(row)).single()
        assertTrue(saved.isConfirmed)
        assertEquals(row.money, saved.money)
    }

    @Test fun exactReceiptPreservesChargeIdentityDateAndSourceFundingLabel() {
        val payment = requireNotNull(GrabProviderParser.wallet(charge().put("payMethod", "GrabPay Credits")))
        val ovo = receipt().also {
            it.getJSONObject("response").getJSONArray("paymentDetails").getJSONObject(0)
                .put("method", JSONObject("""{"name":"OVO","card_type":"GrabPay Credits"}"""))
        }
        val detail = requireNotNull(GrabProviderParser.ride(ride(), ovo))
        val enriched = GrabProviderParser.enrichCharge(payment, detail)
        assertEquals(payment.key, enriched.key)
        assertEquals(payment.money, enriched.money)
        assertEquals(payment.occurredAt, enriched.occurredAt)
        val payload = JSONObject(enriched.rawPayload)
        assertEquals("OVO", payload.getString("paymentMethod"))
        assertEquals("GrabPay Credits", payload.getString("sourcePaymentMethod"))
        assertEquals("GrabPay Credits", payload.getJSONArray("receiptPaymentMethods").getJSONObject(0).getString("type"))
        assertTrue(LedgerReconciler.reconcileLedger(listOf(enriched, detail)).single().isConfirmed)
        assertEquals(payment, GrabProviderParser.enrichCharge(payment, detail.copy(rawPayload =
            JSONObject(detail.rawPayload).put("orderNumber", "other-booking").toString())))
    }

    @Test fun nativeGroupIdDoesNotInventBookingLinkAndNullDoesNotBecomeIdentity() {
        val row = requireNotNull(GrabProviderParser.wallet(charge().put("partnerGroupTxID", JSONObject.NULL)
            .put("groupTxID", "native-group-test").put("payMethod", JSONObject.NULL)))
        val payload = JSONObject(row.rawPayload)
        assertEquals("test-charge", payload.getString("orderNumber"))
        assertEquals("native-group-test", payload.getString("sourceGroupTxID"))
        assertEquals("", payload.getString("sourcePartnerGroupTxID"))
        assertEquals("", payload.getString("paymentMethod"))
        assertEquals(row, GrabProviderParser.enrichCharge(row, requireNotNull(GrabProviderParser.ride(ride(), receipt()))))
        failed { GrabProviderParser.wallet(charge().put("partnerGroupTxID", 123)) }
    }

    @Test fun sourceDeclaredRefundPresentationDoesNotEstablishCreditOrSettlement() {
        assertNull(GrabProviderParser.wallet(charge().put("status", "UNSUCCESSFUL")))
        for (status in listOf("REFUNDED", "PARTIAL_REFUND")) failed {
            GrabProviderParser.wallet(charge().put("status", status))
        }
        for (type in listOf("REFUND", "PARTNER_REFUND", "GRABCARD_REFUND", "TOPUP_SEVEN_ELEVEN")) failed {
            GrabProviderParser.wallet(charge().put("txType", type))
        }
    }

    @Test fun receiptSplitMethodsRemainEnrichmentAndNegativeComponentsAreRejected() {
        val split = receipt().also { it.getJSONObject("response").put("paymentDetails", org.json.JSONArray("""[
            {"currency":"IDR","currencyDetail":{"code":"IDR","exponent":2},"amount":3000000,"method":{"name":"OVO"}},
            {"currency":"IDR","currencyDetail":{"code":"IDR","exponent":2},"amount":2000000,"method":{"name":"Cash"}}
        ]""")) }
        val row = requireNotNull(GrabProviderParser.ride(ride(), split))
        assertEquals("OVO + Cash", JSONObject(row.rawPayload).getString("paymentMethod"))
        assertFalse(LedgerReconciler.reconcileLedger(listOf(row)).single().isConfirmed)
        val components = split.getJSONObject("response").getJSONArray("paymentDetails")
        components.getJSONObject(0).put("amount", 6000000)
        components.getJSONObject(1).put("amount", -1000000)
        failed { GrabProviderParser.ride(ride(), split) }
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

    @Test fun completeReceiptDoesNotDependOnQuotedSummaryMoneyOrTime() {
        val withoutQuote = ride().also { it.remove("quote"); it.remove("timeline") }
        val detail = requireNotNull(GrabProviderParser.ride(withoutQuote, receipt()))
        assertEquals(5000000L, detail.money.amountMinor)
        assertEquals(java.time.Instant.parse("2026-10-02T05:01:00Z"), detail.occurredAt)
        assertFalse(LedgerReconciler.reconcileLedger(listOf(detail)).single().isConfirmed)
        failed { GrabProviderParser.ride(withoutQuote, null) }
    }

    @Test fun receiptNeverBorrowsMissingMoneyUnitsOrTimestampFromQuote() {
        // Make the quote equal the receipt, so fallback could otherwise hide missing receipt evidence.
        val quoted = ride().also { it.getJSONObject("quote").put("fare",
            JSONObject("""{"lowerBound":5000000,"upperBound":5000000}""")) }
        for (field in listOf("total", "currency", "completedAt")) {
            val missing = receipt().also { it.getJSONObject("response").remove(field) }
            failed { GrabProviderParser.ride(quoted, missing) }
            val malformed = receipt().also { it.getJSONObject("response").put(field, "unrecognized") }
            failed { GrabProviderParser.ride(quoted, malformed) }
        }
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
