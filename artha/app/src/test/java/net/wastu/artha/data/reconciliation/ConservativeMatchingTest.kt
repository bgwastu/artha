package net.wastu.artha.data.reconciliation

import java.time.Instant
import java.time.YearMonth
import net.wastu.artha.core.model.*
import net.wastu.artha.data.analysis.BudgetAnalyzer
import org.junit.Assert.*
import org.junit.Test

class ConservativeMatchingTest {
    private fun tx(p: Provider, id: String, title: String, date: String = "2026-10-02T05:00:00Z", amount: Long = 7_500_000, payload: String = "{}", direction: Direction = Direction.DEBIT) =
        UnifiedTransaction(p, id, direction, Money(amount), Instant.parse(date), title, payload)

    @Test fun unrelatedRefundNeverRemovesAnotherProvidersExpense() {
        val debit = tx(Provider.JAGO, "jago:d", "Groceries")
        val refund = tx(Provider.GRAB, "grab:r", "Grab refund", direction = Direction.CREDIT)
        assertEquals(2, LedgerReconciler.reconcileLedger(listOf(debit, refund)).size)
    }

    @Test fun foodToleranceCannotSwallowAnUnrelatedPayment() {
        val order = tx(Provider.SHOPEE, "shopee:o", "Food", payload = """{"origin":"shopeefood-order-api"}""")
        val payment = tx(Provider.JAGO, "jago:p", "Pharmacy", amount = 8_500_000)
        val rows = LedgerReconciler.reconcileLedger(listOf(order, payment))
        val result = BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 10), rows)
        assertEquals(8_500_000, result.totalPersonalSpendingMinor)
        assertFalse(rows.first { it.key == order.key }.isConfirmed)
    }

    @Test fun paymentDateIdentityAndManualMetadataSurviveOrderEnrichment() {
        val order = tx(Provider.SHOPEE, "shopee:o", "Order", payload = """{"origin":"shopee-order-api"}""")
        val payment = tx(Provider.JAGO, "jago:p", "Shopee", date = "2026-09-30T05:00:00Z").copy(category = "Groceries", userNote = "Keep", classificationIsManual = true)
        val row = LedgerReconciler.reconcileLedger(listOf(order, payment)).single()
        assertEquals(payment.key, row.key)
        assertEquals(payment.occurredAt, row.occurredAt)
        assertEquals("Keep", row.userNote)
        assertEquals("Groceries", row.category)
        assertEquals(7_500_000, BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 9), listOf(row)).totalPersonalSpendingMinor)
    }

    @Test fun repeatedAmountsResolveOneToOneAndCurrenciesAreNotGuessed() {
        val order = tx(Provider.SHOPEE, "shopee:o", "Order")
        val a = tx(Provider.JAGO, "jago:a", "Shopee")
        val b = tx(Provider.JAGO, "jago:b", "Shopee")
        val rows = listOf(order, a, b)
        // One order claims exactly one of the identical bank debits; the other stays its own payment.
        assertEquals(2, LedgerReconciler.reconcileLedger(rows).size)
        assertEquals(LedgerReconciler.reconcileLedger(rows), LedgerReconciler.reconcileLedger(rows.reversed()))
        assertEquals(2, LedgerReconciler.reconcileLedger(listOf(order, a.copy(money = Money(a.money.amountMinor, "USD")))).size)
    }

    @Test fun rideAndPaymentUseOneReliableOrderIdentityAndSettledAmount() {
        val ride = tx(Provider.GOJEK, "gojek:o", "GoRide", amount = 4_000_000, payload = """{"origin":"gojek-orders-local","orderNumber":"o"}""")
        val payment = tx(Provider.GOJEK, "gopay:o", "Payment", amount = 4_200_000, payload = """{"origin":"gopay-order-history","orderNumber":"o","paymentEvidence":"settled-wallet-history","paymentStatus":"COMPLETED","paymentDirection":"DEBIT","paymentAmountMinor":4200000,"paymentCurrency":"IDR","paymentTimestamp":"2026-10-02T05:00:00Z"}""")
        val row = LedgerReconciler.reconcileLedger(listOf(ride, payment)).single()
        assertEquals(payment.key, row.key)
        assertEquals(payment.money, row.money)
        assertTrue(row.isConfirmed)
    }

    @Test fun partialRefundRequiresAnExactOriginalReference() {
        val payment = tx(Provider.JAGO, "jago:p", "Shop")
        val refund = tx(Provider.JAGO, "jago:r", "Shop refund", amount = 2_500_000, payload = """{"originalChargeId":"jago:p"}""", direction = Direction.CREDIT)
        val row = LedgerReconciler.reconcileLedger(listOf(payment, refund)).single()
        assertEquals(5_000_000, row.money.amountMinor)
        assertFalse(row.isReversal)
        assertEquals(payment.occurredAt, row.occurredAt)
        assertEquals(2, LedgerReconciler.reconcileLedger(listOf(payment, refund.copy(rawPayload = "{}"))).size)
    }

    @Test fun walletFundingIsExcludedEvenWhenItsDisplayCategoryChanges() {
        val topup = tx(Provider.JAGO, "jago:p", "GoPay", payload = """{"code":"OTD10","counterpart":"GoPay"}""").copy(category = "Dining Out")
        val row = LedgerReconciler.reconcileLedger(listOf(topup)).single()
        assertFalse(BudgetAnalyzer.isSpending(row))
    }

    @Test fun legacyWalletDirectionGuessesCannotInflateConfirmedSpending() {
        val missing = tx(Provider.GRAB, "grab:unknown", "Old wallet row", payload = """{"origin":"grab-wallet-api","item":{}}""")
        val guessedDebit = missing.copy(providerTransactionId = "grab:credit", rawPayload = """{"origin":"grab-wallet-api","item":{"direction":"CREDIT"}}""")
        val rows = LedgerReconciler.reconcileLedger(listOf(missing, guessedDebit))
        assertTrue(rows.none { it.isConfirmed })
        assertEquals(0L, BudgetAnalyzer.analyzeMonth(YearMonth.of(2026, 10), rows).totalPersonalSpendingMinor)
        assertEquals(2, rows.size)
        assertEquals(Direction.DEBIT, guessedDebit.direction) // Raw facts remain immutable.
        val verified = missing.copy(rawPayload = """{"origin":"grab-wallet-api","item":{"status":"COMPLETED","currency":"IDR","txType":"BOOKING_CHARGE","coreTxType":"BookingCharge","amount":75000,"timestamp":1790917200}}""")
        assertTrue(LedgerReconciler.reconcileLedger(listOf(verified)).single().isConfirmed)
    }
}
