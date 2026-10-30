package net.wastu.artha.data.ingestion

import java.io.File
import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GojekScreenStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun row(id: String) = UnifiedTransaction(Provider.GOJEK, "gopay:$id", Direction.DEBIT, Money(1_900_000),
        Instant.parse("2026-01-17T11:21:00Z"), "Fictional Shop", """{"origin":"gopay-order-history"}""", isQris = true)

    @Test fun paymentsRoundTripOncePerIdentity() {
        val store = GojekScreenStore(folder.root)
        store.append(row("a"))
        store.append(row("b"))
        store.append(row("a").copy(description = "Later copy"))
        val rows = GojekScreenStore(folder.root).transactions()
        assertEquals(listOf(row("a"), row("b")), rows)
    }

    @Test fun skipsAndBackfillStateSurviveAndTornLinesAreIgnored() {
        val store = GojekScreenStore(folder.root)
        store.skip("2026-01-17|Fictional Shop||DEBIT|1900000")
        store.append(row("a"))
        File(folder.root, "gojek_screen.jsonl").appendText("""{"id":"gopay:torn","direc""")
        File(folder.root, "gojek_screen.jsonl").appendText("\n" + """{"id":"foreign:x","direction":"DEBIT","amountMinor":1,"occurredAt":"2026-01-01T00:00:00Z","description":"x","raw":"{}"}""" + "\n")
        assertEquals(setOf("2026-01-17|Fictional Shop||DEBIT|1900000"), store.skipped())
        assertEquals(listOf(row("a")), store.transactions())
        assertFalse(store.backfillPending)
        store.backfillPending = true
        assertTrue(GojekScreenStore(folder.root).backfillPending)
    }
}
