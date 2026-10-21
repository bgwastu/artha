package net.wastu.artha.data.ledger

import java.time.Instant
import net.wastu.artha.core.model.*
import net.wastu.artha.data.analysis.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderObservationsTest {
    private val old = UnifiedTransaction(Provider.SHOPEE, "shopee:example", Direction.DEBIT, Money(9000),
        Instant.parse("2026-10-10T00:00:00Z"), "Saved order", "{}")
    private val corrected = old.copy(money = Money(10000), occurredAt = Instant.parse("2026-10-02T00:00:00Z"),
        rawPayload = """{"origin":"shopee-order-api","paymentDetailVerified":true,"items":[]}""")
    private fun failed(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun correctionPreservesOriginalAndRepeatedImportsAddNothing() {
        val revision = ProviderObservations.capture(listOf(old), listOf(corrected), 1000).single()
        val stored = listOf(old, revision)
        assertEquals(old, stored.first())
        assertEquals(listOf(corrected), ProviderObservations.resolve(stored))
        assertTrue(ProviderObservations.capture(stored, listOf(corrected), 2000).isEmpty())
        assertTrue(ProviderObservations.capture(emptyList(), listOf(corrected), 2000).isEmpty())
        ProviderObservations.validate(stored)
    }

    @Test fun changedBackValueAndClockRollbackStillAdvanceObservationOrder() {
        val a = corrected
        val b = corrected.copy(money = Money(12000))
        val first = ProviderObservations.capture(listOf(old), listOf(a), 1000)
        val second = ProviderObservations.capture(listOf(old) + first, listOf(b), 900)
        val third = ProviderObservations.capture(listOf(old) + first + second, listOf(a), 800)
        val stored = listOf(old) + first + second + third
        assertEquals(3, stored.count(ProviderObservations::isObservation))
        assertEquals(listOf(a), ProviderObservations.resolve(stored.reversed()))
    }

    @Test fun bankFactsAndUnsupportedPresentationCardsCannotBeRevised() {
        val bank = old.copy(provider = Provider.JAGO, providerTransactionId = "jago:example")
        assertTrue(ProviderObservations.capture(listOf(bank), listOf(bank.copy(rawPayload = corrected.rawPayload)), 1000).isEmpty())
        assertTrue(ProviderObservations.capture(listOf(old), listOf(old.copy(money = Money(10000))), 1000).isEmpty())
    }

    @Test fun malformedRevisionOrMissingOriginalIsRejectedBeforeRestore() {
        val revision = ProviderObservations.capture(listOf(old), listOf(corrected), 1000).single()
        failed { ProviderObservations.validate(listOf(revision)) }
        failed { ProviderObservations.validate(listOf(old, revision.copy(money = Money(20000)))) }
        val raw = JSONObject(revision.rawPayload)
        raw.getJSONObject("transaction").put("description", "Tampered")
        failed { ProviderObservations.validate(listOf(old, revision.copy(rawPayload = raw.toString()))) }
        failed { ProviderObservations.validate(listOf(old, revision.copy(provider = Provider.JAGO))) }
    }

    @Test fun revisedProjectionPreservesManualEditsAndHidesPhysicalRevisionCount() {
        val revisions = ProviderObservations.capture(listOf(old), listOf(corrected), 1000)
        val result = SpendingProjector().project(listOf(old) + revisions, AnalysisSnapshot(
            userNotes = mapOf(old.key to "Keep"), categories = mapOf(old.key to "Groceries"), manualCategories = setOf(old.key)))
        val row = result.transactions.single()
        assertEquals(corrected.money, row.money)
        assertEquals(corrected.occurredAt, row.occurredAt)
        assertEquals("Keep", row.userNote)
        assertEquals("Groceries", row.category)
        assertTrue(row.classificationIsManual)
        assertFalse(row.isConfirmed)
        assertEquals(mapOf(Provider.SHOPEE to 1), result.providerCounts)
    }

    @Test fun observationRevisionsResolveToCorrectedLedgerView() {
        val records = listOf(old) + ProviderObservations.capture(listOf(old), listOf(corrected), 1000)
        assertEquals(listOf(corrected), ProviderObservations.resolve(records))
        assertEquals(2, records.size)
        assertTrue(AnalysisSyncMerger.findMissingTransactionsToAppend(records, records).isEmpty())
    }
}
