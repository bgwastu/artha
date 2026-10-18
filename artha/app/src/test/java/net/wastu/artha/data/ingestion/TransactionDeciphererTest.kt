package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionDeciphererTest {

    @Test
    fun qrisTransactionIsIdentifiedAndCleaned() {
        val res = TransactionDecipherer.decipher(
            code = "QRD01",
            merchantOrCounterpart = "BRI",
            description = "QRIS - KOPI KENANGAN 1320",
            notes = "",
            direction = Direction.DEBIT,
        )
        assertTrue(res.isQris)
        assertEquals("Kopi Kenangan", res.title)
        assertEquals("QRIS", res.paymentMethod)
    }

    @Test
    fun cardPaymentIsCleanedAndExtractsMaskedCardDigits() {
        val res = TransactionDecipherer.decipher(
            code = "VISA-ECOM-EDC-USD-D-IDR",
            merchantOrCounterpart = "•••• •••• •••• 7209",
            description = "STREAMING SERVICE &#43;14152360599 CA",
            notes = "",
            direction = Direction.DEBIT,
        )
        assertFalse(res.isQris)
        assertEquals("Streaming Service", res.title)
        assertEquals("Visa Debit", res.paymentMethod)
        assertEquals("7209", res.cardLast4)
    }

    @Test
    fun bankTransferIsFormattedWithTarget() {
        val res = TransactionDecipherer.decipher(
            code = "OTD10",
            merchantOrCounterpart = "BCA",
            description = "ALICE SMITH",
            notes = "invoice",
            direction = Direction.DEBIT,
        )
        assertEquals("Transfer to Alice Smith", res.title)
        assertEquals("Transfer", res.paymentMethod)
    }

    @Test
    fun interestAndTaxAreCorrectlyLabeled() {
        val interest = TransactionDecipherer.decipher("ISC99", "", "Interest", "", Direction.CREDIT)
        assertEquals("Interest Earned", interest.title)

        val tax = TransactionDecipherer.decipher("TXD99", "", "Tax on Interest", "", Direction.DEBIT)
        assertEquals("Tax on Interest", tax.title)
    }

    @Test
    fun gopayGenericAndRidePaymentsAreIdentified() {
        val genericDebit = TransactionDecipherer.decipher(
            code = "GPD05",
            merchantOrCounterpart = "",
            description = "GoPay",
            notes = "004 - Gopay - Generic Jago payment",
            direction = Direction.DEBIT,
        )
        assertEquals("GoPay Payment", genericDebit.title)
        assertEquals("GoPay", genericDebit.paymentMethod)
        assertFalse(genericDebit.isQris)

        val rideDebit = TransactionDecipherer.decipher(
            code = "GPD01",
            merchantOrCounterpart = "",
            description = "Gopay",
            notes = "004 - GO-RIDE\nNyore Coffee & Space #RB-3239991-87956168",
            direction = Direction.DEBIT,
        )
        assertEquals("GoRide: Nyore Coffee & Space", rideDebit.title)
        assertEquals("GoPay", rideDebit.paymentMethod)
        assertFalse(rideDebit.isQris)

        val refundCredit = TransactionDecipherer.decipher(
            code = "GPC02",
            merchantOrCounterpart = "",
            description = "Transaction Refund",
            notes = "",
            direction = Direction.CREDIT,
        )
        assertEquals("GoPay Refund", refundCredit.title)
        assertEquals("GoPay", refundCredit.paymentMethod)
        assertFalse(refundCredit.isQris)
    }

    @Test
    fun jagoEWalletTopUpsAreProperlyDeciphered() {
        val ovo = TransactionDecipherer.decipher(
            code = "WTD-AJ-EWALLET",
            merchantOrCounterpart = "OVO",
            description = "OVO 082117168239",
            notes = "grab",
            direction = Direction.DEBIT,
        )
        assertEquals("Top Up OVO", ovo.title)
        assertEquals("Top Up", ovo.paymentMethod)
        assertFalse(ovo.isQris)

        val shopee = TransactionDecipherer.decipher(
            code = "WTD02",
            merchantOrCounterpart = "ShopeePay",
            description = "wastu._",
            notes = "",
            direction = Direction.DEBIT,
        )
        assertEquals("Top Up ShopeePay", shopee.title)
        assertEquals("Top Up", shopee.paymentMethod)

        val dana = TransactionDecipherer.decipher(
            code = "WTD06",
            merchantOrCounterpart = "Dnid Tarxxx",
            description = "Dnid Tarxxx",
            notes = "",
            direction = Direction.DEBIT,
        )
        assertEquals("Top Up DANA", dana.title)
        assertEquals("Top Up", dana.paymentMethod)
    }
}
