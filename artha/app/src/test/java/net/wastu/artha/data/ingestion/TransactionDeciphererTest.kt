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
}
