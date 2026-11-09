package net.wastu.artha.data.analysis

import java.time.Instant
import net.wastu.artha.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TransactionMetadataTest {
    private fun tx(provider: Provider, id: String) = UnifiedTransaction(provider, id, Direction.DEBIT, Money(100), Instant.EPOCH, "Payment", "{}")

    @Test fun exactKeysNeverApplySuffixOrOtherProviderEdits() {
        val a = tx(Provider.JAGO, "12")
        val b = tx(Provider.JAGO, "112")
        val c = tx(Provider.SHOPEE, "12")
        val snapshot = AnalysisSnapshot(userNotes = mapOf(a.key to "Only this one"))
        val notes = SpendingProjector().project(listOf(a, b, c), snapshot).transactions.associate { it.key to it.userNote }
        assertEquals("Only this one", notes[a.key])
        assertNull(notes[b.key])
        assertNull(notes[c.key])
    }
}
