package net.wastu.artha.data.reconciliation

import java.time.Instant
import java.time.YearMonth
import net.wastu.artha.core.model.*
import net.wastu.artha.data.analysis.BudgetAnalyzer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettledPaymentProjectionTest {
    private val date = Instant.parse("2026-10-02T05:00:00Z")
    private fun receipt(id: String = "shopee:order", origin: String = "shopee-order-api") = UnifiedTransaction(
        Provider.SHOPEE, id, Direction.DEBIT, Money(7_500_000), date, "Books", JSONObject()
            .put("origin", origin).put("paymentDetailVerified", true).put("sourceAmountPaid", 7_500_000_000L)
            .put("sourcePaymentTime", if (origin == "shopee-order-api") date.epochSecond else date.toEpochMilli())
            .put("orderId", "order").toString(), isConfirmed = false)
    private fun bank(id: String = "jago:payment", title: String = "Shopee", time: Instant = date.minusSeconds(60)) =
        UnifiedTransaction(Provider.JAGO, id, Direction.DEBIT, Money(7_500_000), time, title,
            JSONObject().put("code", "PSD02").put("counterpart", title).toString())
    private fun wallet(id: String = "gopay:payment") = UnifiedTransaction(Provider.GOJEK, id, Direction.DEBIT,
        Money(7_500_000), date.minusSeconds(30), "Shopee payment", JSONObject().put("origin", "gopay-order-history")
            .put("paymentEvidence", "settled-wallet-history").put("paymentStatus", "COMPLETED")
            .put("paymentDirection", "DEBIT").put("paymentAmountMinor", 7_500_000)
            .put("paymentCurrency", "IDR").put("paymentTimestamp", date.minusSeconds(30).toString()).toString())
    private fun total(rows: List<UnifiedTransaction>) = BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 10), rows).totalMinor

    @Test fun verifiedMarketplaceAndFoodPaymentsCountWithoutBankEvidence() {
        for (origin in listOf("shopee-order-api", "shopeefood-order-api")) {
            val saved = receipt(origin = origin)
            val projected = LedgerReconciler.reconcileLedger(listOf(saved)).single()
            assertTrue(projected.isConfirmed)
            assertEquals(7_500_000L, total(listOf(projected)))
            assertFalse(saved.isConfirmed) // Saved legacy evidence remains immutable.
        }
    }

    @Test fun quotedOrContradictoryReceiptsNeverBecomePayments() {
        val paid = receipt()
        val invalid = listOf(paid.copy(rawPayload = """{"origin":"shopee-order-api"}"""),
            paid.copy(money = Money(7_400_000)), paid.copy(occurredAt = date.plusSeconds(1)),
            paid.copy(rawPayload = JSONObject(paid.rawPayload).put("paymentDetailVerified", false).toString()))
        val rows = LedgerReconciler.reconcileLedger(invalid.mapIndexed { i, t -> t.copy(providerTransactionId = "shopee:$i") })
        assertEquals(4, rows.size)
        assertTrue(rows.none { it.isConfirmed })
        assertEquals(0L, total(rows))
    }

    @Test fun bankAndProviderPaidReceiptCountOnceUsingBankDateAndMetadata() {
        val payment = bank().copy(userNote = "Work books", category = "Work", classificationIsManual = true)
        val row = LedgerReconciler.reconcileLedger(listOf(receipt(), payment)).single()
        assertEquals(payment.key, row.key)
        assertEquals(payment.occurredAt, row.occurredAt)
        assertEquals("Work", row.category)
        assertEquals("Work books", row.userNote)
        assertEquals(7_500_000L, total(listOf(row)))
    }

    @Test fun receiptWalletAndBankChargeCountOnceWithAllSourceAliases() {
        val payment = bank(title = "GoPay card payment")
        val facts = listOf(receipt(), wallet(), payment)
        val rows = LedgerReconciler.reconcileLedger(facts)
        val row = rows.single()
        assertEquals(payment.key, row.key)
        assertEquals(payment.occurredAt, row.occurredAt)
        assertEquals(3, JSONObject(row.rawPayload).getJSONArray("groupedTransactions").length())
        assertEquals(7_500_000L, total(rows))
        assertEquals(rows, LedgerReconciler.reconcileLedger(facts.reversed()))
    }

    @Test fun ambiguousWalletReceiptOverlapDoesNotAddAnotherExpense() {
        val rows = LedgerReconciler.reconcileLedger(listOf(receipt(), wallet(), wallet("gopay:another")))
        assertEquals(3, rows.size)
        assertEquals(2, rows.count { it.isConfirmed })
        assertEquals(15_000_000L, total(rows))
    }

    @Test fun directCardReceiptCannotBeSwallowedByAnUnidentifiedWalletCharge() {
        val paidByCard = receipt().copy(cardLast4 = "1234")
        val rows = LedgerReconciler.reconcileLedger(listOf(paidByCard, wallet()))
        assertEquals(2, rows.size)
        assertEquals(15_000_000L, total(rows))
    }

    @Test fun bankReversalStillFindsItsChargeAfterReceiptEnrichment() {
        val payment = bank()
        val refund = payment.copy(providerTransactionId = "jago:refund", direction = Direction.CREDIT,
            occurredAt = date.plusSeconds(86400), rawPayload = """{"code":"PSD02-R","counterpart":"Shopee"}""")
        val facts = listOf(receipt(), payment, refund)
        val row = LedgerReconciler.reconcileLedger(facts).single()
        assertEquals(payment.key, row.key)
        assertTrue(row.isReversal)
        assertEquals(3, JSONObject(row.rawPayload).getJSONArray("groupedTransactions").length())
        assertEquals(0L, total(listOf(row)))
        assertEquals(listOf(row), LedgerReconciler.reconcileLedger(facts.reversed()))
        assertEquals(2, LedgerReconciler.reconcileLedger(facts.dropLast(1) + refund.copy(description = "Other",
            rawPayload = """{"code":"PSD02-R","counterpart":"Other"}""")).size)
    }

    @Test fun settledSourceRefundAdjustsStandalonePaidReceiptUsingExactReference() {
        val refund = UnifiedTransaction(Provider.SHOPEE, "shopee:refund:one", Direction.CREDIT, Money(2_500_000),
            date.plusSeconds(86400), "Refund", JSONObject().put("origin", "shopee-refund-api")
                .put("verifiedRefund", true).put("sourceRefundStatus", 5).put("originalChargeId", "shopee:order")
                .put("paymentEvidence", "settled-provider-refund").put("paymentStatus", "COMPLETED")
                .put("paymentDirection", "CREDIT").put("paymentAmountMinor", 2_500_000).put("paymentCurrency", "IDR")
                .put("paymentTimestamp", date.plusSeconds(86400).toString()).toString())
        val row = LedgerReconciler.reconcileLedger(listOf(receipt(), refund)).single()
        assertEquals(5_000_000L, total(listOf(row)))
        assertEquals(date, row.occurredAt)
        assertEquals(2, JSONObject(row.rawPayload).getJSONArray("groupedTransactions").length())
    }
}
