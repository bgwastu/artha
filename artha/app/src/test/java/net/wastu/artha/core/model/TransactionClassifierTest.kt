package net.wastu.artha.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionClassifierTest {
    private fun jago(direction: Direction, payload: String) =
        UnifiedTransaction(Provider.JAGO, "t", direction, Money(10000), Instant.parse("2026-01-12T02:00:00Z"), "Fixture", payload, isConfirmed = true)

    @Test fun interestIsIncomeAndItsTaxIsAnExpenseDespiteJagoMarkingThemInternal() {
        val internal = """{"fields":{"f22":"INTERNAL"}"""
        assertEquals(TransactionType.INCOME, TransactionClassifier.classify(jago(Direction.CREDIT, """{"code":"ISC99",${internal.drop(1)}}""")))
        assertEquals(TransactionType.EXPENSE, TransactionClassifier.classify(jago(Direction.DEBIT, """{"code":"TXD99",${internal.drop(1)}}""")))
    }

    @Test fun pocketMovesStayTransfers() {
        assertEquals(TransactionType.TRANSFER, TransactionClassifier.classify(jago(Direction.DEBIT, """{"code":"SAD01"}""")))
        assertEquals(TransactionType.TRANSFER, TransactionClassifier.classify(jago(Direction.CREDIT, """{"fields":{"f22":"INTERNAL"}}""")))
    }
}
