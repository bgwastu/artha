package net.wastu.artha.data.export

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerJsonlImportTest {
    @Test
    fun roundTripPreservesTransactionFields() {
        val original = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:import-1",
            direction = Direction.DEBIT,
            money = Money(50_000_00L),
            occurredAt = Instant.parse("2026-03-01T08:00:00Z"),
            description = "Coffee",
            rawPayload = """{"code":"GPD05","fields":{"f6":"order-abc"}}""",
            isQris = true,
            category = "Dining Out",
            userNote = "team lunch",
            classificationIsManual = true,
        )
        val snapshot = AnalysisSnapshot(
            knowledge = "I work in Jakarta",
            customCategories = listOf("Dining Out", "Uncategorized"),
            categories = mapOf(original.key to "Dining Out"),
            userNotes = mapOf(original.key to "team lunch"),
            manualCategories = setOf(original.key),
        )
        val exported = LedgerJsonlExport.build(listOf(original), snapshot, Instant.parse("2026-03-02T00:00:00Z"))
        val parsed = LedgerJsonlImport.parse(exported)
        assertEquals(1, parsed.transactions.size)
        val tx = parsed.transactions.first()
        assertEquals(original.provider, tx.provider)
        assertEquals(original.providerTransactionId, tx.providerTransactionId)
        assertEquals(original.money.amountMinor, tx.money.amountMinor)
        assertEquals(original.description, tx.description)
        assertEquals(original.isQris, tx.isQris)
        assertEquals("Dining Out", parsed.labels.first().category)
        assertEquals("team lunch", parsed.labels.first().userNote)
        assertEquals(true, parsed.labels.first().classificationIsManual)
        assertEquals("I work in Jakarta", parsed.meta?.getJSONObject("analysis")?.getString("knowledge"))
    }
}
