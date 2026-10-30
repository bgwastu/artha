package net.wastu.artha.data.sync

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.*
import org.junit.Test

/** Drives the walk against a fictional GoPay history that scrolls, hides headers, and opens details. */
class GojekScreenWalkTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private val today = LocalDate.of(2026, 1, 20)
    private val fast = GojekScreenWalk.Limits(waitMillis = 0, pollMillis = 1, swipeSettleMillis = 0)

    private val history = (0 until 30).map { i ->
        Item("fictional-id-%04d".format(i), today.minusDays((i / 3).toLong()), "Fictional Shop $i", 1_000L * (i + 1), LocalTime.of(20 - i % 3, 15))
    }

    @Test fun opensOnlyRowsTheLedgerLacksAndStopsOnceCaughtUp() = runBlocking {
        val gopay = FakeGoPay(history)
        val stored = mutableListOf<UnifiedTransaction>()
        val known = history.drop(3).map { it.ledgerRow() }
        val outcome = walk(gopay, known, stored).run()
        assertEquals(GojekScreenWalk.Stop.CAUGHT_UP, outcome.stop)
        assertEquals(listOf("gopay:fictional-id-0000", "gopay:fictional-id-0001", "gopay:fictional-id-0002"), stored.map { it.providerTransactionId })
        assertEquals(3, gopay.opened)
        val first = stored.first()
        assertEquals(Direction.DEBIT, first.direction)
        assertEquals(1_000L, first.money.amountMinor)
        assertEquals(today.atTime(20, 15).atZone(zone).toInstant(), first.occurredAt)
    }

    @Test fun headersScrolledOffKeepTheirDatesAcrossPages() = runBlocking {
        val gopay = FakeGoPay(history)
        val stored = mutableListOf<UnifiedTransaction>()
        val outcome = walk(gopay, emptyList(), stored, limits = fast.copy(maxDetails = 100)).run()
        assertEquals(GojekScreenWalk.Stop.END_OF_LIST, outcome.stop)
        assertEquals(history.map { "gopay:${it.id}" }, stored.map { it.providerTransactionId })
        assertTrue(gopay.headerlessTopSeen)
    }

    @Test fun sameDaySameAmountRowsAreCountedNotCollapsed() = runBlocking {
        val twin = history.take(3).mapIndexed { i, item -> item.copy(id = "twin-id-000$i", amountMinor = 5_000L, description = "Fictional Mart") }
        val gopay = FakeGoPay(twin + history.drop(3))
        val stored = mutableListOf<UnifiedTransaction>()
        walk(gopay, listOf(twin[0].ledgerRow()) + history.drop(3).map { it.ledgerRow() }, stored).run()
        assertEquals(2, stored.size)
    }

    @Test fun detailBudgetLeavesBackfillForTheNextWalk() = runBlocking {
        val first = mutableListOf<UnifiedTransaction>()
        val outcome = walk(FakeGoPay(history), emptyList(), first, limits = fast.copy(maxDetails = 10)).run()
        assertEquals(GojekScreenWalk.Stop.DETAIL_BUDGET, outcome.stop)
        assertEquals(10, first.size)

        val second = mutableListOf<UnifiedTransaction>()
        val gopay = FakeGoPay(history)
        val next = walk(gopay, first, second, backfill = true, limits = fast.copy(maxDetails = 100)).run()
        assertEquals(GojekScreenWalk.Stop.END_OF_LIST, next.stop)
        assertEquals(history.drop(10).map { "gopay:${it.id}" }, second.map { it.providerTransactionId })
    }

    @Test fun unreadableDetailsAreRememberedAndNotReopened() = runBlocking {
        val gopay = FakeGoPay(history, unreadable = setOf("fictional-id-0001"))
        val skips = mutableListOf<String>()
        val stored = mutableListOf<UnifiedTransaction>()
        walk(gopay, history.drop(3).map { it.ledgerRow() }, stored, onSkip = { skips += it }).run()
        assertEquals(1, skips.size)
        assertEquals(2, stored.size)

        val again = FakeGoPay(history, unreadable = setOf("fictional-id-0001"))
        walk(again, history.drop(3).map { it.ledgerRow() } + stored, mutableListOf(), skipped = skips.toSet()).run()
        assertEquals(0, again.opened)
    }

    @Test fun abortStopsBeforeOpeningAnything() = runBlocking {
        val gopay = FakeGoPay(history)
        val outcome = walk(gopay, emptyList(), mutableListOf(), abort = { true }).run()
        assertEquals(GojekScreenWalk.Stop.LIST_UNREADABLE, outcome.stop)
        assertEquals(0, gopay.opened)
    }

    private fun walk(
        screen: GojekScreenWalk.Screen,
        known: List<UnifiedTransaction>,
        stored: MutableList<UnifiedTransaction>,
        backfill: Boolean = false,
        skipped: Set<String> = emptySet(),
        onSkip: (String) -> Unit = {},
        abort: () -> Boolean = { false },
        limits: GojekScreenWalk.Limits = fast,
    ) = GojekScreenWalk(screen, known, skipped, backfill, zone, today, abort, { stored += it }, onSkip, limits)

    private data class Item(val id: String, val date: LocalDate, val description: String, val amountMinor: Long, val time: LocalTime)

    private fun Item.ledgerRow() = UnifiedTransaction(Provider.GOJEK, "gopay:$id", Direction.DEBIT, Money(amountMinor),
        date.atTime(time).atZone(zone).toInstant().plusMillis(123), description, "{}")

    /** A 1080x2340 GoPay list: date headers, 230px rows, and a swipe that moves three rows. */
    private class FakeGoPay(private val items: List<Item>, private val unreadable: Set<String> = emptySet()) : GojekScreenWalk.Screen {
        var offset = 0
        var detail: Item? = null
        var opened = 0
        var headerlessTopSeen = false
        private val taps = mutableMapOf<Int, Item>()

        override suspend fun dump(): String = detail?.let(::detailXml) ?: listXml()

        override suspend fun tap(x: Int, y: Int) {
            if (detail != null) { if (y < 120) detail = null; return }
            detail = taps[y]?.also { opened++ }
        }

        override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, millis: Int) {
            offset = minOf(offset + 3, maxOf(0, items.size - 5))
        }

        override suspend fun back() { detail = null }

        private fun listXml(): String {
            taps.clear()
            val out = StringBuilder()
            var y = 330
            for (i in offset until items.size) {
                if (y + 300 > 2340) break
                val item = items[i]
                if (i == 0 || items[i - 1].date != item.date) {
                    out.append(node("2131385000", header(item.date), y, y + 61)); y += 120
                } else if (i == offset) headerlessTopSeen = true
                out.append(node("tvAmount", "-" + rupiah(item.amountMinor), y, y + 53))
                out.append(node("tvDescription", item.description, y + 32, y + 85))
                taps[y + 58] = item
                y += 230
            }
            return "<hierarchy>" + node("navigation_back", "", 44, 110) + out + "</hierarchy>"
        }

        private fun detailXml(item: Item): String {
            fun pair(label: String, value: String, top: Int) = node("", label, top, top + 52, 44, 540) + node("", value, top, top + 52, 540, 1036)
            return "<hierarchy>" + node("navigation_back", "", 44, 110) +
                pair("Status", "Selesai", 700) + pair("Waktu", "%02d:%02d".format(item.time.hour, item.time.minute), 800) +
                pair("Tanggal", "${item.date.dayOfMonth} Jan ${item.date.year}", 900) +
                (if (item.id in unreadable) "" else pair("ID transaksi", item.id, 1000)) +
                pair("Jumlah", rupiah(item.amountMinor), 1100) + "</hierarchy>"
        }

        private fun header(date: LocalDate) = if (date == LocalDate.of(2026, 1, 20)) "Hari ini" else "Senin, ${date.dayOfMonth} Jan ${date.year}"

        private fun rupiah(minor: Long) = "Rp" + "%,d".format(minor / 100).replace(',', '.')

        private fun node(id: String, text: String, top: Int, bottom: Int, left: Int = 44, right: Int = 1036) =
            """<node text="$text" resource-id="${if (id.isEmpty()) "" else "com.gojek.app:id/$id"}" bounds="[$left,$top][$right,$bottom]"/>"""
    }
}
