package net.wastu.artha.data.reconciliation

import java.time.Instant
import net.wastu.artha.core.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderRefundIntegrationTest {
    private fun row(provider: Provider, id: String, amount: Long = 10000, direction: Direction = Direction.DEBIT,
        date: String = "2026-09-01T00:00:00Z", payload: String = "{}", title: String = "Shopee") =
        UnifiedTransaction(provider, id, direction, Money(amount), Instant.parse(date), title, payload)
    private val order = row(Provider.SHOPEE, "shopee:test", payload = """{"origin":"shopeefood-order-api","orderId":"test"}""")
    private val bank = row(Provider.JAGO, "jago:charge", date = "2026-08-31T23:59:00Z")
    private val refund = row(Provider.SHOPEE, "shopee:refund:test", amount = 3000, direction = Direction.CREDIT,
        date = "2026-10-10T00:00:00Z", payload = """{"originalChargeId":"shopee:test","verifiedRefund":true}""", title = "Shopee refund")

    @Test fun grossChargeMatchesBankBeforeLongDelayedExactPartialRefund() {
        val result = LedgerReconciler.reconcileLedger(listOf(order, bank, refund))
        val row = result.single()
        assertEquals(bank.key, row.key)
        assertEquals(bank.occurredAt, row.occurredAt)
        assertEquals(7000L, row.money.amountMinor)
        assertTrue(row.isConfirmed)
        assertEquals(3, JSONObject(row.rawPayload).getJSONArray("groupedTransactions").length())
        assertEquals(10000L, JSONObject(row.rawPayload).getLong("grossPaidAmountMinor"))
        assertEquals(result, LedgerReconciler.reconcileLedger(listOf(refund, bank, order)))
    }

    @Test fun fullyRefundedBankMatchedOrderIsCountedOnceAsReversed() {
        val row = LedgerReconciler.reconcileLedger(listOf(order, bank, refund.copy(money = bank.money))).single()
        assertEquals(bank.key, row.key)
        assertTrue(row.isReversal)
        assertTrue(row.isConfirmed)
    }

    @Test fun unverifiedCreditCannotRemoveConfirmedExpenseAndOverRefundIsNotApplied() {
        assertEquals(2, LedgerReconciler.reconcileLedger(listOf(order, bank, refund.copy(rawPayload = """{"originalChargeId":"shopee:test"}"""))).size)
        val excessive = LedgerReconciler.reconcileLedger(listOf(order, bank, refund.copy(money = Money(11000))))
        assertEquals(2, excessive.size)
        assertEquals(10000L, excessive.first { it.direction == Direction.DEBIT }.money.amountMinor)
    }

    @Test fun exactRefundCanUseGroupedWalletIdentityAfterNativeBookingEnrichment() {
        val trip = row(Provider.GOJEK, "gojek:booking", payload = """{"orderNumber":"booking"}""", title = "GoRide")
        val payment = row(Provider.GOJEK, "gopay:wallet", payload = """{"origin":"gopay-order-history","orderNumber":"wallet","bookingNumber":"booking","paymentEvidence":"settled-wallet-history","paymentStatus":"COMPLETED","paymentDirection":"DEBIT","paymentAmountMinor":10000,"paymentCurrency":"IDR","paymentTimestamp":"2026-09-01T00:00:00Z"}""")
        val credit = row(Provider.GOJEK, "gopay:refund", amount = 3000, direction = Direction.CREDIT, date = "2026-10-10T00:00:00Z",
            payload = """{"originalChargeId":"gopay:wallet","verifiedRefund":true}""", title = "Refund")
        val row = LedgerReconciler.reconcileLedger(listOf(trip, payment, credit)).single()
        assertEquals(payment.key, row.key)
        assertEquals(7000L, row.money.amountMinor)
        assertEquals(3, JSONObject(row.rawPayload).getJSONArray("groupedTransactions").length())
    }

    @Test fun pendingWalletOriginAndAmbiguousBankOverlapCannotIncreaseConfirmedTotal() {
        val raw = """{"origin":"gopay-order-history","paymentEvidence":"settled-wallet-history","paymentStatus":"PENDING","paymentDirection":"DEBIT","paymentAmountMinor":10000,"paymentCurrency":"IDR","paymentTimestamp":"2026-09-01T00:00:00Z"}"""
        val pending = row(Provider.GOJEK, "gopay:pending", payload = raw)
        assertFalse(LedgerReconciler.reconcileLedger(listOf(pending)).single().isConfirmed)
        val settled = pending.copy(rawPayload = raw.replace("PENDING", "COMPLETED"))
        val a = bank.copy(description = "GoPay")
        val b = a.copy(providerTransactionId = "jago:other")
        val rows = LedgerReconciler.reconcileLedger(listOf(settled, a, b))
        assertEquals(2, rows.size)
        assertEquals(2, rows.count { it.isConfirmed })
    }
}
