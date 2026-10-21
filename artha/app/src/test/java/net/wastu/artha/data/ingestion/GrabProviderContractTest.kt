package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.*
import net.wastu.artha.data.reconciliation.LedgerReconciler
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    @Test fun repeatedChargeProofDeduplicatesWithoutChangingIdentityOrFunding() {
        val original = requireNotNull(GrabProviderParser.wallet(charge().put("payMethod", "GrabPay Credits")))
        val labeledAgain = original.copy(description = "Grab updated label")
        val tip = requireNotNull(GrabProviderParser.wallet(charge().put("txID", "test-tip")
            .put("txType", "TIPPING_CHARGE").put("coreTxType", "TippingCharge")))
        val unique = GrabProviderParser.uniqueCharges(listOf(original, labeledAgain, tip, original))
        assertEquals(0, unique.conflictCount)
        assertEquals(listOf(original, tip), unique.transactions)
        assertEquals(2, LedgerReconciler.reconcileLedger(unique.transactions).size)
    }

    @Test fun conflictingSettledIdentityCannotSilentlyPickMoneyDateOrBooking() {
        val safe = requireNotNull(GrabProviderParser.wallet(charge().put("txID", "safe-charge")))
        fun row(id: String, field: String? = null, value: Any? = null) = requireNotNull(
            GrabProviderParser.wallet(charge().put("txID", id).also { if (field != null) it.put(field, value) }))
        val unique = GrabProviderParser.uniqueCharges(listOf(safe,
            row("amount-conflict"), row("amount-conflict", "amount", 40000),
            row("time-conflict"), row("time-conflict", "timestamp", 1790917201L),
            row("booking-conflict"), row("booking-conflict", "partnerGroupTxID", "other-booking")))
        assertEquals(3, unique.conflictCount)
        assertEquals(listOf(safe), unique.transactions)
        assertEquals(safe.key, LedgerReconciler.reconcileLedger(unique.transactions).single().key)
    }

    @Test fun receiptBudgetCountsDistinctCompletedBookingsAfterCanceledEntries() {
        val rides = org.json.JSONArray()
        repeat(20) { rides.put(JSONObject().put("state", if (it % 2 == 0) "CANCELLED" else "FAILED")
            .put("bookingCode", "canceled-$it")) }
        for (id in listOf("booking-a", "booking-a", "booking-b", "../unsafe", "booking-c")) {
            rides.put(JSONObject().put("state", "COMPLETED").put("bookingCode", id))
        }
        assertEquals(setOf(20, 22), GrabProviderParser.receiptIndices(rides, limit = 2))
        assertEquals(setOf(20, 22, 24), GrabProviderParser.receiptIndices(rides))
        assertTrue(GrabProviderParser.receiptIndices(rides, limit = 0).isEmpty())
    }

    @Test fun malformedRideRowsDoNotConsumeCompletedReceiptSlots() {
        val rides = org.json.JSONArray("""[null, "unknown", {"state":"COMPLETED","bookingCode":null},
            {"state":"COMPLETED","bookingCode":42}, {"state":"PENDING","bookingCode":"ongoing"},
            {"state":"COMPLETED","bookingCode":"booking-good"}]""")
        assertEquals(setOf(5), GrabProviderParser.receiptIndices(rides, limit = 1))
        failed { GrabProviderParser.receiptIndices(rides, limit = -1) }
    }

    @Test fun rateLimitRetryDelayHonorsRetryAfterHeader() {
        assertEquals(60L, GrabOrderReader.rateLimitRetryDelaySeconds("120"))
        assertEquals(5L, GrabOrderReader.rateLimitRetryDelaySeconds(null))
        assertEquals(1L, GrabOrderReader.rateLimitRetryDelaySeconds("0"))
    }

    @Test fun http429MapsToRateLimitedWithOptionalRetryAfter() {
        val withoutRetry = assertThrows(ProviderReadException::class.java) { requireHttpSuccess(429, "Grab") }
        assertEquals(net.wastu.artha.data.providers.AccountStatus.RATE_LIMITED, withoutRetry.status)
        assertTrue(withoutRetry.message!!.contains("429"))
        val withRetry = assertThrows(ProviderReadException::class.java) { requireHttpSuccess(429, "Grab", "120") }
        assertEquals(net.wastu.artha.data.providers.AccountStatus.RATE_LIMITED, withRetry.status)
        assertTrue(withRetry.message!!.contains("120"))
    }

    @Test fun tricklingResponseCannotResetTheTotalRequestDeadline() = runBlocking {
        var now = 0L
        var reads = 0
        val timeouts = mutableListOf<Int>()
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                reads++
                now += 7_000_000_000L
                bytes[off] = 1
                return 1
            }
        }
        try {
            GrabOrderReader.readResponse(input, 20_000_000_000L, nanoTime = { now }, prepareRead = { timeouts += it })
            fail("A continuously trickling response must still expire")
        } catch (error: ProviderReadException) {
            assertEquals(net.wastu.artha.data.providers.AccountStatus.ERROR, error.status)
            assertEquals(3, reads)
            assertEquals(listOf(10000, 10000, 6000), timeouts)
        }
    }

    @Test fun responseReadCancellationStopsBeforeAnotherChunk() = runBlocking {
        var reads = 0
        var closed = false
        val child = launch(start = CoroutineStart.UNDISPATCHED) {
            val job = requireNotNull(kotlin.coroutines.coroutineContext[Job])
            val input = object : java.io.InputStream() {
                override fun read(): Int = error("Bulk reads expected")
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    reads++
                    bytes[off] = 1
                    job.cancel()
                    return 1
                }
                override fun close() { closed = true }
            }
            input.use { GrabOrderReader.readResponse(it, Long.MAX_VALUE, nanoTime = { 0L }) }
        }
        child.join()
        assertTrue(child.isCancelled)
        assertEquals(1, reads)
        assertTrue(closed)
    }

    @Test fun boundedResponsePreservesBytesAndRejectsOversizeBodies() = runBlocking {
        val body = "{\"transactions\":[]}".toByteArray()
        val copied = body.inputStream().use { GrabOrderReader.readResponse(it, 1L, nanoTime = { 0L }) }
        assertArrayEquals(body, copied)
        val oversize = ByteArray(4 * 1024 * 1024 + 1).inputStream()
        try {
            oversize.use { GrabOrderReader.readResponse(it, 1L, nanoTime = { 0L }) }
            fail("Oversize history cannot be imported")
        } catch (_: IllegalArgumentException) { }
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
