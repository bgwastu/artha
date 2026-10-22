package net.wastu.artha.ui

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionTagsTest {
    private val twoRecords = """"groupedTransactions":[{"role":"Original Charge"},{"role":"Refund"}]"""

    @Test fun reversalReplacesGroupedAndKeepsPaymentFacts() {
        val tx = transaction("""{$twoRecords,"isQris":true}""").copy(isReversal = true, cardLast4 = "4821")
        assertEquals(listOf("Reversed", "QRIS", "4821"), transactionTags(tx).map { it.label })
        assertEquals("Card ending 4821", transactionTags(tx).last().description)
    }

    @Test fun incomingReversalReadsAsRefund() {
        val tx = transaction("{}").copy(direction = Direction.CREDIT, isReversal = true)
        assertEquals(listOf(TransactionTagKind.REFUND), transactionTags(tx).map { it.kind })
    }

    @Test fun mergedSourcesAreGrouped() {
        assertEquals(listOf(TransactionTagKind.GROUPED), transactionTags(transaction("{$twoRecords}")).map { it.kind })
        assertEquals(emptyList<TransactionTagKind>(), transactionTags(transaction("""{"groupedTransactions":[{}]}""")).map { it.kind })
    }

    @Test fun pocketTransferReplacesGrouped() {
        val tx = transaction("""{$twoRecords,"isPocketTransfer":true}""")
        assertEquals(listOf(TransactionTagKind.TRANSFER), transactionTags(tx).map { it.kind })
    }

    @Test fun onlyReferenceMatchedRefundsArePartial() {
        val partial = transaction("""{$twoRecords,"grossPaidAmountMinor":30000,"refundedAmountMinor":20000}""")
        assertEquals(listOf(TransactionTagKind.PARTIAL_REFUND), transactionTags(partial).map { it.kind })
        val fullyRefunded = partial.copy(isReversal = true)
        assertEquals(listOf(TransactionTagKind.REVERSED), transactionTags(fullyRefunded).map { it.kind })
    }

    @Test fun maskedOrMissingCardDigitsAreNotTagged() {
        assertEquals(emptyList<TransactionTag>(), transactionTags(transaction("""{"cardLast4":"null"}""")))
        assertEquals(emptyList<TransactionTag>(), transactionTags(transaction("{}").copy(cardLast4 = "••21")))
        assertEquals(listOf("1234"), transactionTags(transaction("""{"cardLast4":"1234"}""")).map { it.label })
    }

    private fun transaction(payload: String) = UnifiedTransaction(Provider.JAGO, "fixture", Direction.DEBIT, Money(10000),
        Instant.parse("2026-01-12T02:00:00Z"), "Fixture", payload)
}
