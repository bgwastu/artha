package net.wastu.artha.data.ingestion

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fictional fixtures shaped like native history/details/cards, containing no phone data. */
class GojekProviderContractTest {
    @Test fun completedPaymentUsesExplicitDirectionCurrencyTimeAndWholeRupiah() {
        val row = requireNotNull(GojekProviderParser.payment(payment()))
        assertEquals("gopay:fictional-wallet-01", row.providerTransactionId)
        assertEquals(Direction.DEBIT, row.direction)
        assertEquals(2_500_000L, row.money.amountMinor)
        assertEquals("IDR", row.money.currencyCode)
        assertEquals(Instant.parse("2026-01-01T03:04:05Z"), row.occurredAt)
        assertTrue(row.isConfirmed)
        assertFalse(row.isReversal)
        val payload = JSONObject(row.rawPayload)
        assertEquals("settled-wallet-history", payload.getString("paymentEvidence"))
        assertEquals("COMPLETED", payload.getString("paymentStatus"))
        assertFalse(payload.has("tracking_id"))
        assertFalse(payload.has("item"))
    }

    @Test fun refundRequiresAnActualCompletedCreditAndPreservesItsOwnIdentity() {
        val refund = payment().put("order_id", "fictional-refund-02").put("service_type", "TOKOPEDIA_REFUND").put("payment_type", "CREDIT")
        val row = requireNotNull(GojekProviderParser.payment(refund))
        assertEquals("gopay:fictional-refund-02", row.providerTransactionId)
        assertEquals(Direction.CREDIT, row.direction)
        assertTrue(row.isReversal)
        assertEquals(AccountStatus.ERROR, failure { GojekProviderParser.payment(refund.put("payment_type", "DEBIT")) }.status)
    }

    @Test fun cancelledFailedAndPendingRowsNeverInventSettlement() {
        for (status in listOf("CANCELLED", "CANCELED", "FAILED", "PENDING", "PROCESSING", "IN_PROGRESS")) {
            assertNull(GojekProviderParser.payment(JSONObject().put("status", status)))
        }
        failure { GojekProviderParser.payment(payment().put("status", "REFUNDED")) }
        failure { GojekProviderParser.payment(payment().apply { remove("status") }) }
    }

    @Test fun rejectsUnknownMoneyDirectionCurrencyAndTimeInsteadOfCoercingThem() {
        failure { GojekProviderParser.payment(payment().put("payment_type", "UNKNOWN")) }
        failure { GojekProviderParser.payment(payment().put("order_timestamp", "2026-01-01T03:04")) }
        failure { GojekProviderParser.payment(payment().put("amount", JSONObject().put("value", 25000))) }
        failure { GojekProviderParser.payment(payment().put("amount", JSONObject().put("value", 25000).put("currency", "USD"))) }
        for (value in listOf(-1L, 0L, Long.MAX_VALUE, BigDecimal("12.345"), "25000")) {
            failure { GojekProviderParser.payment(payment().put("amount", JSONObject().put("value", value).put("currency", "IDR"))) }
        }
    }

    @Test fun nativeJakartaWallTimesPreserveObservedFractionalSecondPrecision() {
        for (fraction in listOf("123", "12345", "123456")) {
            val row = requireNotNull(GojekProviderParser.payment(payment().put("order_timestamp", "2026-01-01T10:04:05.$fraction")))
            val source = Instant.parse("2026-01-01T03:04:05.${fraction}Z")
            assertEquals(Instant.ofEpochMilli(source.toEpochMilli()), row.occurredAt)
            assertEquals(source.toString(), JSONObject(row.rawPayload).getString("sourcePaymentTimestamp"))
            assertEquals("gopay:fictional-wallet-01", row.providerTransactionId)
        }
        assertEquals(Instant.parse("2026-01-01T03:04:05Z"), GojekProviderParser.paymentTimestamp("2026-01-01T10:04:05"))
    }

    @Test fun subMillisecondSourceTimeSurvivesRoomWithoutLosingConfirmationOrCreatingAnotherRevision() {
        val row = requireNotNull(GojekProviderParser.payment(payment().put("order_timestamp", "2026-01-01T10:04:05.123456")))
        val stored = net.wastu.artha.data.ledger.TransactionEntity.fromDomain(row, Instant.now()).toDomain()
        assertEquals(row.occurredAt, stored.occurredAt)
        assertTrue(net.wastu.artha.data.reconciliation.LedgerReconciler.reconcileLedger(listOf(stored)).single().isConfirmed)
        assertTrue(net.wastu.artha.data.ledger.ProviderObservations.capture(listOf(stored), listOf(row), 1000).isEmpty())
    }

    @Test fun verifiedNativeZoneIsIndependentOfJvmDefaultAndExplicitOffsetsRetainAuthority() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Pacific/Honolulu", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(Instant.parse("2026-01-01T03:04:05.123456Z"), GojekProviderParser.paymentTimestamp("2026-01-01T10:04:05.123456"))
                assertEquals(Instant.parse("2026-01-01T01:04:05.123456Z"), GojekProviderParser.paymentTimestamp("2026-01-01T10:04:05.123456+09:00"))
                assertEquals(Instant.parse("2026-01-01T10:04:05.123456Z"), GojekProviderParser.paymentTimestamp("2026-01-01T10:04:05.123456Z"))
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun nativeTimestampParsingRejectsMissingComponentsAndInvalidCalendarValues() {
        for (value in listOf("", "2026-01-01", "10:04:05", "2026-01-01T10:04", "2026-02-30T10:04:05.123456",
            "2026-01-01T25:04:05.123", "2026-01-01T10:04:05.1234567890", "2026-01-01T10:04:05.not-a-fraction",
            "2026-01-01T10:04:05+99:00")) {
            assertEquals(AccountStatus.ERROR, failure { GojekProviderParser.paymentTimestamp(value) }.status)
        }
    }

    @Test fun collectServiceAloneDoesNotClaimQris() {
        val row = requireNotNull(GojekProviderParser.payment(payment().put("service_type", "COLLECT")))
        assertFalse(row.isQris)
        val detail = payment().put("detail_view_items", JSONArray().put(JSONObject().put("value", "QRIS")))
        assertTrue(GojekProviderParser.enrich(row, detail, emptyList()).isQris)
    }

    @Test fun qrisDetailEnrichmentAddsMerchantPaymentDetailsAndTitle() {
        val row = requireNotNull(GojekProviderParser.payment(payment().put("service_type", "COLLECT").put("description", "GoPay Payment")))
        assertTrue(GojekProviderParser.shouldFetchPaymentDetail(row))
        val detail = payment().put("detail_view_items", JSONArray()
            .put(JSONObject().put("title", "Merchant name").put("value", "Kopi Kenangan"))
            .put(JSONObject().put("title", "Payment method").put("value", "QRIS")))
        val enriched = GojekProviderParser.enrich(row, detail, emptyList())
        assertEquals("QRIS Kopi Kenangan", enriched.description)
        assertTrue(enriched.isQris)
        val payload = JSONObject(enriched.rawPayload)
        assertEquals("Kopi Kenangan", payload.getString("merchantName"))
        assertEquals("QRIS", payload.getString("paymentMethod"))
        assertEquals(2, payload.getJSONArray("detailViewItems").length())
    }

    @Test fun groupedPaginationPreservesValidRowsBesideMalformedGroupsAndEntries() {
        val data = JSONObject().put("in_progress", JSONArray().put(payment().put("status", "PENDING")))
            .put("completed", JSONArray().put(JSONObject().put("items", JSONArray().put(payment()).put("broken")))
                .put(JSONObject().put("items", JSONArray().put(payment().put("order_id", "fictional-wallet-02"))))
                .put(JSONObject().put("group_key", "fictional-group")))
            .put("next", "https://customer.gopayapi.com/v2/users/order-history?page=2&offset_for_oh=20&limit=20")
        val page = GojekProviderParser.paymentPage(JSONObject().put("success", true).put("data", data))
        assertEquals(2, page.items.size)
        assertEquals(2, page.malformedRows)
        assertTrue(requireNotNull(page.next).contains("offset_for_oh=20"))
        assertEquals(2, page.items.mapNotNull(GojekProviderParser::payment).size)
    }

    @Test fun paginationNeverForwardsCredentialsToAnotherDestination() {
        val next = "/v2/users/order-history?page=2&offset_for_oh=20"
        assertEquals("https://customer.gopayapi.com$next", GojekProviderParser.historyCursor(next))
        for (url in listOf("https://fictional.invalid/v2/users/order-history", "http://customer.gopayapi.com/v2/users/order-history",
            "https://customer.gopayapi.com/other", "https://user@customer.gopayapi.com/v2/users/order-history",
            "https://customer.gopayapi.com:444/v2/users/order-history", "https://customer.gopayapi.com/v2/users/order-history#fragment")) {
            failure { GojekProviderParser.historyCursor(url) }
        }
        val page = GojekProviderParser.paymentPage(page(payment()).apply { getJSONObject("data").put("next", "https://fictional.invalid/history") })
        assertEquals(1, page.items.size)
        failure { GojekProviderParser.historyCursor(requireNotNull(page.next)) }
    }

    @Test fun invalidPaginationTypeStillPreservesTheCurrentPage() {
        val page = GojekProviderParser.paymentPage(page(payment()).apply { getJSONObject("data").put("next", JSONObject()) })
        assertEquals(1, page.items.size)
        assertEquals(1, page.malformedRows)
        assertNull(page.next)
    }

    @Test fun cacheMetadataUsesResponseStatusAndEncodingAfterVaryRequestHeaders() {
        val bytes = """
            https://api.gojekapi.com/orderbff/v1/customer/card/history?limit=20
            GET
            1
            Accept-Encoding: br,gzip
            HTTP/1.1 200 OK
            3
            Content-Type: application/json
            content-encoding: br
            OkHttp-Received-Millis: 1767225845000
        """.trimIndent().toByteArray()
        val entry = requireNotNull(GojekProviderParser.cacheEntry(bytes))
        assertEquals(GojekProviderParser.CacheKind.ORDERS, entry.kind)
        assertEquals(200, entry.status)
        assertEquals("br", entry.encoding)
        assertEquals(1767225845000L, entry.receivedMillis)
        assertEquals(401, GojekProviderParser.cacheEntry(bytes.toString(Charsets.UTF_8).replace("200 OK", "401 Unauthorized").toByteArray())?.status)
        assertNull(GojekProviderParser.cacheEntry(bytes.toString(Charsets.UTF_8).replace("api.gojekapi.com", "fictional.invalid").toByteArray()))
    }

    @Test fun decodesPlainGzipAndNativeBrotliWithoutParsingDisplayedPricesAsPayments() {
        val json = page(payment()).toString().toByteArray()
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json) }
        assertEquals(1, GojekProviderParser.paymentPage(GojekProviderParser.decodeBody(json, "")).items.size)
        assertEquals(1, GojekProviderParser.paymentPage(GojekProviderParser.decodeBody(output.toByteArray(), "gzip")).items.size)
        val compressed = Base64.getDecoder().decode("GxUBYIzTFfMjlHEPIm+pX35GEJSqmv1Y96r9vnT61G2hPsQ8l+6bCILhoPnJJgYRdGMb05YVCNqqOklBx5WDCWdSG6bz9leH3W5XzSMhhcMxidu0hIP5jnTMPEUqFbnG4oEmqFCdwIEaQ3gkYGUzTd08BcGPg5+k2+vCWILTD3gbry/JHZAcAQ==")
        val orders = GojekProviderParser.orderCards(GojekProviderParser.decodeBody(compressed, "br"))
        assertEquals(1, orders.size)
        assertEquals("fictional-booking-01", orders.single().number)
        assertEquals("Fictional destination", orders.single().description)
        failure { GojekProviderParser.decodeBody(json, "unsupported") }
        failure { GojekProviderParser.readBounded(ByteArray(GojekProviderParser.MAX_BODY_BYTES + 1).inputStream()) }
    }

    @Test fun detailEnrichmentRequiresMatchingFinancialFieldsAndExactBookingNumber() {
        val transaction = requireNotNull(GojekProviderParser.payment(payment()))
        val detail = payment().put("detail_view_items", JSONArray().put(JSONObject().put("row_type", "DEFAULT").put("value", "fictional-booking-01")))
        val order = GojekProviderParser.NativeOrder("fictional-booking-01", 5, 1, "Fictional destination")
        val enriched = GojekProviderParser.enrich(transaction, detail, listOf(order))
        assertEquals(transaction.providerTransactionId, enriched.providerTransactionId)
        assertEquals(transaction.money, enriched.money)
        assertEquals(transaction.occurredAt, enriched.occurredAt)
        assertEquals("fictional-booking-01", JSONObject(enriched.rawPayload).getString("bookingNumber"))
        assertFalse(JSONObject(GojekProviderParser.enrich(transaction, detail, listOf(order.copy(number = "unrelated-booking"))).rawPayload).has("bookingNumber"))
        failure { GojekProviderParser.enrich(transaction, JSONObject(detail.toString()).put("amount", JSONObject().put("value", 25001).put("currency", "IDR")), listOf(order)) }
        failure { GojekProviderParser.enrich(transaction, JSONObject(detail.toString()).put("order_timestamp", "2026-01-01T03:04:06Z"), listOf(order)) }
        failure { GojekProviderParser.enrich(transaction, JSONObject(detail.toString()).put("payment_type", "CREDIT"), listOf(order)) }
    }

    @Test fun nativeOrderPagePreservesValidCardsBesideMalformedActionsWithoutCoercingCodes() {
        val cards = JSONArray().put(orderCard("fictional-booking-01"))
            .put(JSONObject().put("payload", JSONObject().put("text", "Fictional display widget")))
        for (code in listOf("5", BigDecimal("5.5"), Long.MAX_VALUE, JSONObject.NULL)) {
            val card = orderCard("fictional-broken-booking")
            card.getJSONObject("payload").getJSONObject("on_click").getJSONObject("action").getJSONObject("data").put("order_status", code)
            cards.put(card)
        }
        cards.put("broken-card").put(orderCard("fictional-booking-02"))
        val root = JSONObject().put("success", true).put("data", JSONObject().put("cards", cards))
        val page = GojekProviderParser.orderPage(root)
        assertEquals(listOf("fictional-booking-01", "fictional-booking-02"), page.orders.map { it.number })
        assertEquals(5, page.malformedRows)
        failure { GojekProviderParser.orderPage(JSONObject().put("success", true).put("data", JSONObject())) }
    }

    @Test fun preferredFreshOrderMetadataPreservesSettledPaymentIdentityAndProof() {
        val transaction = requireNotNull(GojekProviderParser.payment(payment()))
        val detail = payment().put("detail_view_items", JSONArray().put(JSONObject().put("value", "fictional-booking-01")))
        val cached = GojekProviderParser.NativeOrder("fictional-booking-01", 5, 1, "Fictional cached description")
        val fresh = cached.copy(statusCode = 6, description = "Fictional updated description")
        val enriched = GojekProviderParser.enrich(transaction, detail, listOf(fresh, cached))
        val payload = JSONObject(enriched.rawPayload)
        assertEquals(6, payload.getInt("orderStatusCode"))
        assertEquals(fresh.description, payload.getString("orderDisplayDescription"))
        assertEquals(transaction.providerTransactionId, enriched.providerTransactionId)
        assertEquals(transaction.money, enriched.money)
        assertEquals(transaction.occurredAt, enriched.occurredAt)
        assertEquals(JSONObject(transaction.rawPayload).getString("paymentTimestamp"), payload.getString("paymentTimestamp"))
        assertTrue(enriched.isConfirmed)
    }

    @Test fun bodyReadStopsAtMonotonicDeadlineEvenWithContinuallyArrivingBytes() {
        var elapsed = 0L
        var reads = 0
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(32)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                elapsed += 1_000_000
                return super.read(bytes, offset, minOf(length, 1))
            }
            override fun close() { closed = true; super.close() }
        }
        val error = failure { GojekProviderParser.readBounded(input) {
            GojekProviderParser.remainingTimeoutMillis(20_000_000, 15_000, elapsed)
        } }
        assertEquals(AccountStatus.ERROR, error.status)
        assertTrue(reads < 32)
        assertTrue(closed)
        assertEquals(15_000, GojekProviderParser.remainingTimeoutMillis(20_000_000_000, 15_000, 0))
        assertEquals(1, GojekProviderParser.remainingTimeoutMillis(20_000_000, 15_000, 19_500_000))
    }

    @Test fun cancellationDuringBodyConsumptionClosesStreamAndRemainsCancellation() {
        val cancelled = CancellationException("Fictional canceled import")
        var read = false
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                read = true
                return super.read(bytes, offset, length)
            }
            override fun close() { closed = true; super.close() }
        }
        try {
            GojekProviderParser.readBounded(input) { if (read) throw cancelled }
            fail("Expected cancellation")
        } catch (error: CancellationException) { assertSame(cancelled, error) }
        assertTrue(closed)
        var checkpoints = 0
        try {
            GojekProviderParser.decodeBody(page(payment()).toString().toByteArray(), "") {
                if (++checkpoints == 3) throw cancelled
            }
            fail("Expected decoder cancellation")
        } catch (error: CancellationException) { assertSame(cancelled, error) }
    }

    @Test fun corruptCompressionAndJsonRemainTypedSchemaFailures() {
        for (encoding in listOf("gzip", "br", "")) {
            assertEquals(AccountStatus.ERROR, failure { GojekProviderParser.decodeBody(byteArrayOf(1, 2, 3), encoding) }.status)
        }
    }

    @Test fun largeFloatingMoneyCannotClaimMinorUnitPrecisionButExactNumbersKeepTheirRange() {
        for (amount in listOf((1L shl 53).toDouble(), 200_000f)) {
            failure { GojekProviderParser.payment(payment().put("amount", JSONObject().put("value", amount).put("currency", "IDR"))) }
        }
        val exact = 1L shl 53
        val row = requireNotNull(GojekProviderParser.payment(payment().put("amount", JSONObject().put("value", exact).put("currency", "IDR"))))
        assertEquals(exact * 100, row.money.amountMinor)
        val decimal = requireNotNull(GojekProviderParser.payment(payment().put("amount", JSONObject().put("value", BigDecimal("25000.01")).put("currency", "IDR"))))
        assertEquals(2_500_001L, decimal.money.amountMinor)
    }

    @Test fun http400InvalidTokenBodyRequiresLoginWithoutAuthenticateHeader() {
        val error = requireNotNull(requireGojekHttpFailure(400, "", """{"errors":[{"code":"invalid-token"}]}"""))
        assertEquals(AccountStatus.NEEDS_LOGIN, error.status)
        assertEquals(GOJEK_NEEDS_LOGIN_MESSAGE, error.message)
    }

    @Test fun http400WwwAuthenticateInvalidTokenRequiresLogin() {
        val error = requireNotNull(requireGojekHttpFailure(
            400,
            """Bearer realm=gojek, error=invalid-token, error_description="Invalid Token"""",
            """{"success":false,"errors":[{"code":"GoPay-1000"}]}""",
        ))
        assertEquals(AccountStatus.NEEDS_LOGIN, error.status)
    }

    @Test fun http400GoPayGenericBodyWithoutAuthenticateHeaderIsListUnavailable() {
        val body = """{"success":false,"error":{"code":"1000","description":"Internal server error"},
            "errors":[{"code":"GoPay-1000","message":"Don't worry, we're fixing this."}]}"""
        val error = requireNotNull(requireGojekHttpFailure(400, "", body))
        assertEquals(AccountStatus.ERROR, error.status)
        assertEquals(GOJEK_HISTORY_LIST_UNAVAILABLE_MESSAGE, error.message)
    }

    @Test fun http403InvalidTokenBodyRequiresLogin() {
        val error = requireNotNull(requireGojekHttpFailure(403, "", """{"errors":[{"code":"invalid_token"}]}"""))
        assertEquals(AccountStatus.NEEDS_LOGIN, error.status)
        assertEquals(GOJEK_NEEDS_LOGIN_MESSAGE, error.message)
    }

    @Test fun jagoLinkedDebitBuildsGopayOrderForBankFusion() {
        val jago = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:261015-FB7X-ZECLX7",
            direction = Direction.DEBIT,
            money = Money(41_100_00L),
            occurredAt = Instant.parse("2026-10-15T01:32:22Z"),
            description = "GoPay Payment",
            rawPayload = """{"code":"GPD05","counterpart":"Indomaret","fields":{"f6":"0320261015013200cLFRf1Yw0yID-1","f17":"GoPay"}}""",
        )
        val row = GojekProviderParser.paymentFromJagoLinkedDebit(jago, "0320261015013200cLFRf1Yw0yID")
        assertEquals("gopay:0320261015013200cLFRf1Yw0yID", row.providerTransactionId)
        assertEquals("Indomaret", row.description)
        val payload = JSONObject(row.rawPayload)
        assertEquals("jago-linked-order-reference", payload.getString("paymentEvidence"))
        assertEquals("0320261015013200cLFRf1Yw0yID", payload.getString("orderNumber"))
        assertEquals("Indomaret", payload.getString("merchantName"))
    }

    @Test fun jagoLinkedQrisDebitBuildsMerchantTitleAndDetailRows() {
        val jago = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:fixture",
            direction = Direction.DEBIT,
            money = Money(15_000_00L),
            occurredAt = Instant.parse("2026-10-02T05:00:00Z"),
            description = "Kopi Kenangan",
            rawPayload = """{"code":"GPD01","counterpart":"GoPay","notes":"QRIS","localized":"QRIS KOPI KENANGAN","fields":{"f6":"order-fixture-1"}}""",
            isQris = true,
        )
        val row = GojekProviderParser.paymentFromJagoLinkedDebit(jago, "order-fixture")
        assertEquals("QRIS Kopi Kenangan", row.description)
        assertTrue(row.isQris)
        val payload = JSONObject(row.rawPayload)
        assertTrue(payload.getJSONArray("detailViewItems").length() >= 2)
    }

    @Test fun cachedPaymentsFinishPartialWhenLiveSessionIsInvalid() {
        val rows = ImportRows()
        rows.readRow { requireNotNull(GojekProviderParser.payment(payment())) }
        rows.issue(ProviderReadException(AccountStatus.NEEDS_LOGIN, GOJEK_NEEDS_LOGIN_MESSAGE))
        val partial = assertThrows(PartialImportException::class.java) { rows.finish() }
        assertEquals(1, partial.transactions.size)
        assertTrue(partial.message!!.contains(GOJEK_NEEDS_LOGIN_MESSAGE))
    }

    @Test fun readsAndroidPreferencesAndDecryptsOnlyTheNativeSessionStorageContract() {
        val prefs = GojekProviderParser.preferences("<map><string name=\"key_scp_login_access_token\">lnYDhnAXGNo3sbDErSSgmd+pogAdg+kilGAQ5VTN/6cIxC23mt/DLcxbL01pLZ5f</string></map>".toByteArray())
        assertEquals("fictional.header.body.signature.tail", GojekProviderParser.decryptAccessToken(prefs.getValue("key_scp_login_access_token")))
        assertEquals(AccountStatus.NEEDS_LOGIN, failure { GojekProviderParser.decryptAccessToken("not-an-encrypted-session") }.status)
        failure { GojekProviderParser.preferences("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///fictional'>]><map/>".toByteArray()) }
        failure { GojekProviderParser.paymentPage(JSONObject().put("success", false).put("data", JSONObject())) }
    }

    private fun payment(): JSONObject = JSONObject("""{
        "order_id":"fictional-wallet-01", "service_type":"GORIDE", "status":"COMPLETED",
        "payment_type":"DEBIT", "order_timestamp":"2026-01-01T10:04:05+07:00",
        "description":"Fictional ride", "amount":{"value":25000,"currency":"IDR"},
        "payment_transfer_type":"DEBIT_TRANSFER", "tracking_id":"fictional-tracking-id",
        "payment_method_breakup":[{"payment_method":"GOPAY","payment_method_name":"GoPay"}]
    }""")
    private fun page(item: JSONObject): JSONObject = JSONObject().put("success", true).put("data", JSONObject()
        .put("completed", JSONArray().put(JSONObject().put("group_key", "fictional-group").put("items", JSONArray().put(item))))
        .put("next", JSONObject.NULL))
    private fun orderCard(number: String): JSONObject = JSONObject().put("payload", JSONObject()
        .put("on_click", JSONObject().put("action", JSONObject().put("data", JSONObject()
            .put("order_number", number).put("order_status", 5).put("service_type", 1)))))
    private fun failure(block: () -> Unit): ProviderReadException {
        try { block() } catch (e: ProviderReadException) { return e }
        throw AssertionError("Expected a typed provider failure")
    }
}
