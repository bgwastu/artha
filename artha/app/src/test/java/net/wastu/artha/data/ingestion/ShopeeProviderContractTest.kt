package net.wastu.artha.data.ingestion

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fictional values with structures verified on the installed app; never copied user receipts. */
class ShopeeProviderContractTest {
    @Test fun marketplaceUsesPaidAmountAndPaymentTimeInsteadOfShippingOrOrderTime() {
        val detail = marketplaceDetail()
        detail.getJSONObject("info_card").put("final_total", 9_900_000_000L)
        val payment = ShopeeProviderParser.marketplace("101", marketplaceListing(), detail)!!
        assertEquals("shopee:101", payment.transaction.providerTransactionId)
        assertEquals(1_250_000L, payment.transaction.money.amountMinor)
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), payment.transaction.occurredAt)
        assertEquals(Direction.DEBIT, payment.transaction.direction)
        assertTrue(payment.transaction.isConfirmed)
        assertFalse(payment.refundExpected)
        val item = JSONObject(payment.transaction.rawPayload).getJSONArray("items").getJSONObject(0)
        assertEquals(625_000L, item.getLong("unitAmountMinor"))
        assertEquals("Rp6250", item.getString("price"))
        assertEquals(2, item.getInt("qty"))
    }

    @Test fun paidCancellationRetainsGrossChargeAndReportsUnresolvedRefund() {
        val detail = marketplaceDetail().put("list_type", 4)
        detail.getJSONObject("processing_info").getJSONArray("info_rows").put(time("label_odp_cancelled_time", 1_700_100_000L))
        val result = ShopeeProviderParser.marketplace("101", marketplaceListing(), detail)!!
        assertTrue(result.refundExpected)
        assertEquals(Direction.DEBIT, result.transaction.direction)
        val raw = JSONObject(result.transaction.rawPayload)
        assertEquals(1_250_000L, raw.getLong("grossPaidAmountMinor"))
        assertEquals("unresolved", raw.getString("refundState"))
        assertFalse(result.transaction.isReversal)
    }

    @Test fun explicitUnpaidCancellationIsSkippedWithoutInventingPaymentTime() {
        val detail = marketplaceDetail().put("list_type", 4)
        detail.getJSONObject("info_card").put("amount_paid", 0L)
        detail.getJSONObject("processing_info").put("info_rows", JSONArray().put(time("label_odp_order_time", 1_699_000_000L)))
        assertNull(ShopeeProviderParser.marketplace("101", marketplaceListing(), detail))
    }

    @Test fun positiveMarketplaceAmountCannotFallBackToShippingTimestamp() {
        val detail = marketplaceDetail()
        detail.getJSONObject("processing_info").put("info_rows", JSONArray().put(time("label_odp_order_time", 1_699_000_000L)))
        expectSchema { ShopeeProviderParser.marketplace("101", marketplaceListing(), detail) }
    }

    @Test fun scaleAndTimestampUnitMistakesAreRejected() {
        val detail = marketplaceDetail()
        detail.getJSONObject("info_card").put("amount_paid", "1250000001")
        expectSchema { ShopeeProviderParser.marketplace("101", marketplaceListing(), detail) }
        detail.getJSONObject("info_card").put("amount_paid", "1250000000")
        detail.getJSONObject("processing_info").put("info_rows", JSONArray().put(time("label_odp_payment_time", 1_700_000_000_000L)))
        expectSchema { ShopeeProviderParser.marketplace("101", marketplaceListing(), detail) }
        val food = foodOrder().put("payment_time", "1700000000")
        expectSchema { ShopeeProviderParser.food(food) }
    }

    @Test fun foodUsesActualPaidPaymentAndIgnoresSeparateTipCard() {
        val order = foodOrder()
        order.put("tip_info", JSONObject().put("tip", JSONObject().put("payment_channel_option",
            """{"credit_card_data":{"card_number":"****9999"}}""")))
        val detail = foodDetail(order)
        detail.getJSONArray("payment").getJSONObject(0).put("channel_option",
            """{"credit_card_data":{"card_number":"****1234"}}""")
        val result = ShopeeProviderParser.food(order, detail)!!
        assertEquals("shopee:202", result.transaction.providerTransactionId)
        assertEquals(2_350_000L, result.transaction.money.amountMinor)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), result.transaction.occurredAt)
        assertEquals("1234", result.transaction.cardLast4)
        assertTrue(result.transaction.isConfirmed)
        assertTrue(JSONObject(result.transaction.rawPayload).getBoolean("paymentDetailVerified"))
        val items = JSONObject(result.transaction.rawPayload).getJSONArray("items")
        assertEquals("Rp10000", items.getJSONObject(0).getString("price"))
        assertEquals(2, items.getJSONObject(0).getInt("qty"))
        assertNull(ShopeeProviderParser.food(order)!!.transaction.cardLast4)
    }

    @Test fun canceledFoodWithEvidencedPaymentIsNotDiscarded() {
        val order = foodOrder().put("cancel_time", "1700100000000").put("status", 900)
            .put("flag", JSONObject().put("is_refunded", 1))
        val result = ShopeeProviderParser.food(order, foodDetail(order))!!
        assertTrue(result.refundExpected)
        assertEquals(Direction.DEBIT, result.transaction.direction)
        assertEquals(2_350_000L, result.transaction.money.amountMinor)
        assertFalse(result.transaction.isReversal)
    }

    @Test fun failedFirstAttemptCannotSupplyPaidMethodOrCardMetadata() {
        val order = foodOrder()
        val detail = foodDetail(order)
        val paid = detail.getJSONArray("payment").getJSONObject(0).put("channel_option",
            """{"channel_name":"Example paid channel","credit_card_data":{"card_number":"****1234"}}""")
        val failed = JSONObject(paid.toString()).put("status", 3).put("payment_time", "0")
            .put("total_amount", "9900000000").put("channel_option",
                """{"channel_name":"Example failed channel","credit_card_data":{"card_number":"****9999"}}""")
        detail.put("payment", JSONArray().put(failed).put(paid))
        val transaction = ShopeeProviderParser.food(order, detail)!!.transaction
        val raw = JSONObject(transaction.rawPayload)
        assertEquals("Example paid channel", raw.getString("sourcePaymentMethod"))
        assertEquals("1234", transaction.cardLast4)
        assertEquals(2_350_000L, transaction.money.amountMinor)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), transaction.occurredAt)
        assertEquals("settled-order-payment", raw.getString("paymentEvidence"))
        assertEquals(transaction.money.amountMinor, raw.getLong("paymentAmountMinor"))
        assertTrue(transaction.isConfirmed)
    }

    @Test fun afterSalesTriggersRefundInvestigationWithoutClaimingCompletedRefund() {
        val order = foodOrder().put("has_after_sales", 1)
        val result = ShopeeProviderParser.food(order, foodDetail(order))!!
        assertTrue(result.refundExpected)
        val raw = JSONObject(result.transaction.rawPayload)
        assertTrue(raw.getBoolean("sourceAfterSales"))
        assertFalse(raw.getBoolean("sourceRefunded"))
        assertEquals("unresolved", raw.getString("refundState"))
        assertEquals(Direction.DEBIT, result.transaction.direction)
    }

    @Test fun failedOrMultipleFoodPaymentsDoNotCreateSuccessfulPayment() {
        val order = foodOrder()
        val detail = foodDetail(order)
        detail.getJSONArray("payment").getJSONObject(0).put("status", 3)
        expectSchema { ShopeeProviderParser.food(order, detail) }
        detail.getJSONArray("payment").getJSONObject(0).put("status", 2)
        detail.getJSONArray("payment").put(JSONObject(detail.getJSONArray("payment").getJSONObject(0).toString()))
        expectSchema { ShopeeProviderParser.food(order, detail) }
        detail.put("payment", JSONArray())
        order.put("payment_time", "0")
        assertNull(ShopeeProviderParser.food(order, detail))
    }

    @Test fun foodPaymentIdentityCurrencyAndTimeMustMatchSource() {
        for (field in listOf("order_id", "currency", "payment_time")) {
            val order = foodOrder()
            val detail = foodDetail(order)
            val wrong = when (field) { "order_id" -> "203"; "currency" -> "USD"; else -> "1700000000001" }
            detail.getJSONArray("payment").getJSONObject(0).put(field, wrong)
            expectSchema { ShopeeProviderParser.food(order, detail) }
        }
    }

    @Test fun missingItemAmountsOrQuantitiesDoNotInventZeroPricesOrOneItem() {
        val order = foodOrder()
        val item = order.getJSONArray("items").getJSONObject(0)
        item.remove("unit_price")
        item.getJSONObject("cart_item").remove("quantity")
        val receipt = JSONObject(ShopeeProviderParser.food(order)!!.transaction.rawPayload)
            .getJSONArray("items").getJSONObject(0)
        assertFalse(receipt.has("qty"))
        assertFalse(receipt.has("price"))
        assertFalse(receipt.has("unitAmountMinor"))
    }

    @Test fun successfulRefundHasItsOwnStableIdentityAndActualCreditTimestamp() {
        val order = foodOrder()
        val charge = ShopeeProviderParser.food(order, foodDetail(order))!!.transaction
        val refund = ShopeeProviderParser.foodRefund(charge, refund())!!
        assertEquals("shopee:refund:303", refund.providerTransactionId)
        assertEquals(Direction.CREDIT, refund.direction)
        assertEquals(500_000L, refund.money.amountMinor)
        assertEquals(Instant.ofEpochMilli(1_700_200_000_000L), refund.occurredAt)
        assertTrue(refund.isReversal)
        assertTrue(refund.isConfirmed)
        val raw = JSONObject(refund.rawPayload)
        assertEquals("shopee:202", raw.getString("originalChargeId"))
        assertEquals("shopee-refund-api", raw.getString("origin"))
        assertTrue(raw.getBoolean("verifiedRefund"))
        assertEquals(2_350_000L, raw.getLong("grossPaidAmountMinor"))
        assertEquals(2_350_000L, charge.money.amountMinor)
    }

    @Test fun refundRequiresVerifiedCurrencyCompletedStatusActualAmountAndActualTime() {
        val order = foodOrder()
        val charge = ShopeeProviderParser.food(order, foodDetail(order))!!.transaction
        for (status in listOf(1, 4, 6)) assertNull(ShopeeProviderParser.foodRefund(charge, refund().put("refund_status", status)))
        expectSchema { ShopeeProviderParser.foodRefund(charge, refund().put("refund_status", 77)) }
        expectSchema { ShopeeProviderParser.foodRefund(charge, refund().put("refund_time", "0")) }
        expectSchema { ShopeeProviderParser.foodRefund(charge, refund().put("refund_amount", "0")) }
        expectSchema { ShopeeProviderParser.foodRefund(charge, refund().put("order_id", "999")) }
        expectSchema { ShopeeProviderParser.foodRefund(ShopeeProviderParser.food(order)!!.transaction, refund()) }
    }

    @Test fun marketplaceUsesCursorEvenOnShortPageAndAcceptsObservedTerminalEnvelope() {
        val page = ShopeeProviderParser.marketplacePage(JSONObject().put("error", 0).put("data",
            JSONObject().put("details_list", JSONArray().put(marketplaceListing())).put("next_offset", 40)))
        assertEquals(1, page.orders.length())
        assertEquals(40, page.nextOffset)
        assertNull(page.paginationIssue)
        val terminal = ShopeeProviderParser.marketplacePage(JSONObject("""{"error":0,"data":{"next_offset":-1}}"""))
        assertEquals(0, terminal.orders.length())
        assertNull(terminal.nextOffset)
        assertNull(terminal.paginationIssue)
        expectSchema { ShopeeProviderParser.marketplacePage(JSONObject("""{"error":0,"data":{}}""")) }
    }

    @Test fun brokenMarketplacePaginationRetainsOrdersForVerifiedPartialImport() {
        for (cursor in listOf(null, JSONObject.NULL, "invalid", -2L, Int.MAX_VALUE.toLong() + 1L)) {
            val data = JSONObject().put("details_list", JSONArray().put(marketplaceListing()))
            if (cursor != null) data.put("next_offset", cursor)
            val page = ShopeeProviderParser.marketplacePage(JSONObject().put("error", 0).put("data", data))
            assertNull(page.nextOffset)
            val issue = requireNotNull(page.paginationIssue)
            assertEquals(AccountStatus.ERROR, issue.status)
            val listing = page.orders.getJSONObject(0)
            val payment = ShopeeProviderParser.marketplace(
                ShopeeProviderParser.orderId(listing, food = false), listing, marketplaceDetail(),
            )!!.transaction
            val rows = ImportRows()
            rows.transactions += payment
            rows.issue(issue)
            try { rows.finish(); fail("Expected partial history") }
            catch (partial: PartialImportException) {
                assertEquals(listOf(payment), partial.transactions)
                assertEquals("shopee:101", partial.transactions.single().providerTransactionId)
                assertEquals(1_250_000L, partial.transactions.single().money.amountMinor)
                assertTrue(partial.transactions.single().isConfirmed)
            }
        }
    }

    @Test fun foodEmptySearchIsOnlyTerminalForThatSearchAndBrokenCursorRemainsFailure() {
        val response = JSONObject("""{"code":0,"data":{"orders":[],"has_more":false,"next_id":""}}""")
        assertNull(ShopeeProviderParser.foodPage(response, search = true).nextId)
        val data = response.getJSONObject("data").put("has_more", true).put("next_id", "404")
        assertEquals("404", ShopeeProviderParser.foodPage(response, search = true).nextId)
        data.remove("next_id")
        assertEquals(AccountStatus.ERROR, ShopeeProviderParser.foodPage(response, search = true).paginationIssue!!.status)
        data.put("last_id", "405")
        assertEquals("405", ShopeeProviderParser.foodPage(response, search = false).nextId)
        data.put("has_more", "false")
        assertEquals(AccountStatus.ERROR, ShopeeProviderParser.foodPage(response, search = false).paginationIssue!!.status)
    }

    @Test fun brokenFoodListAndSearchPaginationRetainsOrdersWithoutClaimingCompletion() {
        for (search in listOf(false, true)) {
            for (more in listOf(true, "false", JSONObject.NULL)) {
                val order = foodOrder()
                val response = JSONObject().put("code", 0).put("data", JSONObject()
                    .put("orders", JSONArray().put(order)).put("has_more", more))
                val page = ShopeeProviderParser.foodPage(response, search)
                assertNull(page.nextId)
                val issue = requireNotNull(page.paginationIssue)
                assertEquals(AccountStatus.ERROR, issue.status)
                val payment = ShopeeProviderParser.food(page.orders.getJSONObject(0), foodDetail(order))!!.transaction
                val rows = ImportRows()
                rows.transactions += payment
                rows.issue(issue)
                try { rows.finish(); fail("Expected partial history") }
                catch (partial: PartialImportException) {
                    assertEquals(listOf(payment), partial.transactions)
                    assertEquals("shopee:202", partial.transactions.single().providerTransactionId)
                    assertEquals(2_350_000L, partial.transactions.single().money.amountMinor)
                    assertTrue(partial.transactions.single().isConfirmed)
                }
            }
        }
    }

    @Test fun sourceAuthAndSchemaFailuresRemainTypedAndValidRowsSurvive() {
        try {
            ShopeeProviderParser.marketplacePage(JSONObject("""{"error":19,"data":null}"""))
            fail("Expected login error")
        } catch (error: ProviderReadException) { assertEquals(AccountStatus.NEEDS_LOGIN, error.status) }
        for (code in listOf(3004, 3011)) {
            try {
                ShopeeProviderParser.data(JSONObject().put("code", code), food = true)
                fail("Expected source-defined food auth error")
            } catch (error: ProviderReadException) { assertEquals(AccountStatus.NEEDS_LOGIN, error.status) }
        }
        expectSchema { ShopeeProviderParser.data(JSONObject().put("code", 1), food = true) }
        val rows = ImportRows()
        rows.transactions += ShopeeProviderParser.food(foodOrder())!!.transaction
        rows.issue(ProviderReadException(AccountStatus.ERROR, "Unrecognized source row"))
        try { rows.finish(); fail("Expected partial import") }
        catch (partial: PartialImportException) {
            assertEquals(1, partial.transactions.size)
            assertEquals("shopee:202", partial.transactions.single().providerTransactionId)
        }
    }

    private fun marketplaceListing() = JSONObject("""{
        "info_card":{"order_id":101,"final_total":1250000000},
        "shipping":{"tracking_info":{"ctime":1700100000}}
    }""")
    private fun marketplaceDetail() = JSONObject("""{
        "list_type":3,"info_card":{"currency":"IDR","amount_paid":1250000000,"final_total":1250000000,
            "parcel_cards":[{"shop_info":{"shop_name":"Fictional Shop"},"product_info":{"item_groups":[{
                "items":[{"name":"Example Product","amount":2,"item_price":625000000,"model_name":"Sample"}]
            }]}}]},
        "processing_info":{"info_rows":[
            {"info_label":{"text":"label_odp_order_time"},"info_value":{"value":"1699000000"}},
            {"info_label":{"text":"label_odp_payment_time"},"info_value":{"value":"1700000000"}}
        ]}
    }""")
    private fun time(label: String, value: Long) = JSONObject().put("info_label", JSONObject().put("text", label))
        .put("info_value", JSONObject().put("value", value.toString()))
    private fun foodOrder() = JSONObject("""{
        "id":"202","amount":{"total_amount":"2350000000"},"payment_time":"1700000000000",
        "place_time":"1699999000000","cancel_time":"0","refund_amount":"0","status":800,
        "flag":{"is_refunded":0},"store":{"name":"Fictional Kitchen"},"items":[{
            "unit_price":"1000000000","cart_item":{"quantity":2,"detail":{"dish":{"name":"Example Meal"}}}
        }]
    }""")
    private fun foodDetail(order: JSONObject) = JSONObject().put("order", order).put("payment", JSONArray().put(
        JSONObject("""{"order_id":"202","currency":"IDR","total_amount":"2350000000","status":2,
            "payment_time":"1700000000000","channel_option":"{}"}"""),
    ))
    private fun refund() = JSONObject("""{"refund_id":"303","order_id":"202","refund_status":5,
        "refund_amount":"500000000","refund_time":"1700200000000","refund_method":1}""")
    private fun expectSchema(block: () -> Any?) {
        try { block(); fail("Expected typed schema failure") }
        catch (error: ProviderReadException) { assertEquals(AccountStatus.ERROR, error.status) }
    }
}
