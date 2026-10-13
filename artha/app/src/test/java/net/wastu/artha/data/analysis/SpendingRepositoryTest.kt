package net.wastu.artha.data.analysis

import java.time.Instant
import net.wastu.artha.core.model.*
import net.wastu.artha.data.reconciliation.LedgerReconciler
import org.junit.Assert.*
import org.junit.Test

class SpendingRepositoryTest {
    private val payment = UnifiedTransaction(Provider.JAGO, "jago:12", Direction.DEBIT, Money(100), Instant.parse("2026-10-02T00:00:00Z"), "Shopee", "{}")
    private val order = payment.copy(provider = Provider.SHOPEE, providerTransactionId = "shopee:42", description = "Order", rawPayload = """{"origin":"shopee-order-api"}""")

    @Test fun metadataAndPresentationChangesDoNotRerunMatching() {
        var calls = 0
        val projector = SpendingProjector { calls++; LedgerReconciler.reconcileLedger(it) }
        val rows = listOf(order, payment)
        projector.project(rows, AnalysisSnapshot())
        val updated = projector.project(rows, AnalysisSnapshot(userNotes = mapOf(payment.key to "Note"), categories = mapOf(payment.key to "Groceries"), manualCategories = setOf(payment.key)))
        projector.project(rows, AnalysisSnapshot(aiConfig = AiConfig("changed", "", ""), categoryIcons = mapOf("Groceries" to "icon")))
        assertEquals(1, calls)
        assertEquals("Note", updated.transactions.single().userNote)
        assertEquals("Groceries", updated.transactions.single().category)
        assertEquals(payment.key, updated.transactions.single().key)
    }

    @Test fun legacyOrderNoteRemainsVisibleAfterPaymentGroupingAndExplicitClearWins() {
        val projector = SpendingProjector()
        val old = AnalysisSnapshot(userNotes = mapOf("42" to "Keep this legacy note"))
        assertEquals("Keep this legacy note", projector.project(listOf(order, payment), old).transactions.single().userNote)
        val cleared = old.copy(updatedAt = mapOf("note:${payment.key}" to 10))
        assertNull(projector.project(listOf(order, payment), cleared).transactions.single().userNote)
    }

    @Test fun customPickerDaysHaveTheSameLocalBoundariesAsPresets() {
        val picker = Instant.parse("2026-10-02T00:00:00Z").toEpochMilli()
        val start = AccountingTime.pickerDay(picker)
        assertEquals(Instant.parse("2026-10-01T17:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-10-02T17:00:00Z").toEpochMilli(), AccountingTime.dayAfter(start))
    }
}
