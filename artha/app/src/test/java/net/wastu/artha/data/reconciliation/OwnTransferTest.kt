package net.wastu.artha.data.reconciliation

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.BudgetAnalyzer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Own-account transfers are found from source structure only; no names are ever matched. */
class OwnTransferTest {
    private val at = Instant.parse("2026-03-21T17:01:31Z")

    private fun gopay(id: String, tier: String, amount: Long, time: Instant = at, description: String = "Ditransfer ke Someone Else") = UnifiedTransaction(
        Provider.GOJEK, "gopay:$id", Direction.DEBIT, Money(amount), time, description,
        """{"origin":"gopay-order-history","provider":"GOJEK","orderNumber":"$id","serviceTier":"$tier","paymentStatus":"COMPLETED",""" +
            """"paymentDirection":"DEBIT","paymentAmountMinor":$amount,"paymentCurrency":"IDR","paymentTimestamp":"$time","paymentEvidence":"settled-wallet-history"}""",
    )

    private fun jago(id: String, direction: Direction, amount: Long, code: String, counterpart: String, time: Instant = at, description: String = counterpart) =
        UnifiedTransaction(Provider.JAGO, "jago:$id", direction, Money(amount), time, description,
            """{"origin":"jago-local-hive","provider":"JAGO","code":"$code","counterpart":"$counterpart"}""")

    private fun spending(rows: List<UnifiedTransaction>, key: String) =
        BudgetAnalyzer.isSpending(LedgerReconciler.reconcileLedger(rows).first { it.key == key })

    @Test fun gopaySavingsSweepIsNeverSpending() {
        val sweep = gopay("s1", "AUTO_SWEEP_WITHDRAWAL", 201_000_00, description = "Transfer ke GoPay Tabungan")
        assertFalse(spending(listOf(sweep), sweep.key))
    }

    @Test fun gopayBankTransferIsOwnOnlyWhenTheBankShowsTheDeposit() {
        val out = gopay("b1", "PP_DIRECT_DEBIT_BANK_TRANSFER", 55_000_00)
        val deposit = jago("d1", Direction.CREDIT, 55_000_00, "TFC72", "", at.plusSeconds(40), "Deposit From Gopay")
        assertFalse(spending(listOf(out, deposit), out.key))
        // Same transfer with no matching deposit may have gone to someone else: it stays spending.
        assertTrue(spending(listOf(out), out.key))
        // A deposit outside the window or of another amount is not evidence.
        assertTrue(spending(listOf(out, jago("d2", Direction.CREDIT, 55_000_00, "TFC72", "", at.plusSeconds(3600), "Deposit From Gopay")), out.key))
        assertTrue(spending(listOf(out, jago("d3", Direction.CREDIT, 54_000_00, "TFC72", "", at.plusSeconds(40), "Deposit From Gopay")), out.key))
    }

    @Test fun topUpToAConnectedWalletIsATransfer() {
        val topUp = jago("t1", Direction.DEBIT, 100_000_00, "WTD06", "ShopeePay", description = "Top Up ShopeePay")
        val shopeeOrder = UnifiedTransaction(Provider.SHOPEE, "shopee:o1", Direction.DEBIT, Money(35_000_00), at.minusSeconds(86_400), "Order", "{}")
        assertFalse(spending(listOf(topUp, shopeeOrder), topUp.key))
    }

    @Test fun topUpToAWalletArthaCannotReadIsSpending() {
        val dana = jago("t2", Direction.DEBIT, 10_000_00, "WTD-AJ-EWALLET", "DANA", description = "Top Up DANA")
        assertTrue(spending(listOf(dana), dana.key))
        // ShopeePay without any Shopee data in Artha: the purchases are invisible, so the top-up counts.
        val shopeePay = jago("t3", Direction.DEBIT, 50_000_00, "WTD06", "ShopeePay", description = "Top Up ShopeePay")
        assertTrue(spending(listOf(shopeePay), shopeePay.key))
    }

    @Test fun pocketMovesStayTransfers() {
        val pocket = jago("p1", Direction.DEBIT, 20_000_00, "SAD01", "", description = "GoPay Tabungan")
        assertFalse(spending(listOf(pocket), pocket.key))
    }
}
