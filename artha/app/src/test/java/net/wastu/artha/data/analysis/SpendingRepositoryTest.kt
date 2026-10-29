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

    @Test fun unchangedRowsAreReusedWhenOneAnnotationChanges() {
        val other = payment.copy(providerTransactionId = "jago:other", description = "Kopi Kenangan")
        val facts = listOf(payment, other)
        val projector = SpendingProjector()
        val original = projector.project(facts, AnalysisSnapshot())
        assertSame(original, projector.project(facts, AnalysisSnapshot()))
        val changed = projector.project(facts, AnalysisSnapshot(userNotes = mapOf(payment.key to "New context")))
        assertEquals("New context", changed.transactions.first { it.key == payment.key }.userNote)
        assertSame(original.transactions.first { it.key == other.key }, changed.transactions.first { it.key == other.key })
        val newFacts = projector.project(listOf(payment, other.copy(description = "Netflix")), AnalysisSnapshot())
        assertNull(newFacts.transactions.first { it.key == other.key }.category)
    }

    @Test fun customPickerDaysHaveTheSameLocalBoundariesAsPresets() {
        val picker = Instant.parse("2026-10-02T00:00:00Z").toEpochMilli()
        val start = AccountingTime.pickerDay(picker)
        assertEquals(Instant.parse("2026-10-01T17:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-10-02T17:00:00Z").toEpochMilli(), AccountingTime.dayAfter(start))
    }
}
