package net.wastu.artha.data.sync

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.ingestion.GojekScreenParser
import net.wastu.artha.data.ingestion.GojekScreenParser.ListRow
import net.wastu.artha.data.ingestion.ProviderReadException

/**
 * Walks GoPay's history list newest first and opens only rows the ledger cannot account for.
 *
 * The list shows no transaction ID, so a row counts as known when the ledger already holds as
 * many GoPay rows with the same local date, direction and amount as the walk has passed so far.
 * Misjudging a row only costs one detail screen: the detail carries the real ID, and the ledger
 * ignores keys it already has.
 */
class GojekScreenWalk(
    private val screen: Screen,
    known: List<UnifiedTransaction>,
    private val skipped: Set<String>,
    private val backfill: Boolean,
    private val zone: ZoneId,
    private val today: LocalDate,
    private val shouldAbort: () -> Boolean,
    private val onPayment: (UnifiedTransaction) -> Unit,
    private val onSkip: (String) -> Unit,
    private val limits: Limits = Limits(),
    /** Receives one line per opened payment (date and verdict) and a closing line counting rows per date; never amounts or names. */
    private val onTrace: (String) -> Unit = {},
    /** Receives the raw screen dump when a tapped row opened no detail screen; it holds payment text, so keep it private. */
    private val onDiagnostic: (String) -> Unit = {},
) {
    interface Screen {
        /** Accessibility dump of the walk's display, or null when it cannot be read. */
        suspend fun dump(): String?
        suspend fun tap(x: Int, y: Int)
        suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, millis: Int)
        suspend fun back()
    }

    data class Limits(
        val maxDetails: Int = 40,
        val maxPages: Int = 60,
        val knownStreakToStop: Int = 8,
        val waitMillis: Long = 8_000,
        val pollMillis: Long = 400,
        val swipeSettleMillis: Long = 900,
    )

    enum class Stop { CAUGHT_UP, END_OF_LIST, DETAIL_BUDGET, PAGE_BUDGET, ABORTED, LIST_UNREADABLE }
    data class Outcome(val stop: Stop, val stored: Int, val opened: Int, val pages: Int)

    private data class Key(val date: LocalDate, val direction: Direction, val amountMinor: Long)

    private val knownCounts: Map<Key, Int> = known.groupingBy { Key(it.occurredAt.atZone(zone).toLocalDate(), it.direction, it.money.amountMinor) }.eachCount()
    private val passedCounts = mutableMapOf<Key, Int>()
    private val seen = mutableListOf<Pair<String, LocalDate>>()
    private val rowsPerDate = java.util.TreeMap<LocalDate, Int>(compareByDescending { it })
    /** Why the last opened detail was refused, so the trace can say it. */
    private var rejection: String? = null

    suspend fun run(): Outcome {
        var stored = 0
        var opened = 0
        var pages = 0
        var knownStreak = 0
        var failures = 0
        fun outcome(stop: Stop): Outcome {
            onTrace("rows seen per date: " + rowsPerDate.entries.take(TRACE_DATES).joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "none" })
            return Outcome(stop, stored, opened, pages)
        }
        // No list came back: either the walk was told to stop (screen locked, Gojek opened on the phone), or the screen is unreadable.
        fun noList() = if (shouldAbort()) Stop.ABORTED else Stop.LIST_UNREADABLE
        var rows = awaitList() ?: return outcome(noList())
        while (true) {
            if (shouldAbort()) return outcome(Stop.ABORTED)
            var target: ListRow? = null
            for (row in fresh(rows)) {
                val signature = signature(row)
                seen += signature to requireNotNull(row.date)
                rowsPerDate.merge(row.date, 1, Int::plus)
                val key = Key(row.date, row.direction, row.amountMinor)
                val passed = passedCounts.merge(key, 1, Int::plus)!!
                if (passed <= (knownCounts[key] ?: 0) || "${row.date}|$signature" in skipped) {
                    knownStreak++
                    if (knownStreak >= limits.knownStreakToStop && !backfill) return outcome(Stop.CAUGHT_UP)
                    continue
                }
                knownStreak = 0
                target = row
                break
            }
            if (target != null) {
                if (opened >= limits.maxDetails) return outcome(Stop.DETAIL_BUDGET)
                opened++
                rejection = null
                when (val result = openDetail(target)) {
                    is UnifiedTransaction -> { onPayment(result); stored++; onTrace("opened ${target.date}: read") }
                    false -> {
                        onTrace("opened ${target.date}: no detail screen appeared")
                        if (++failures >= MAX_FAILURES) return outcome(Stop.LIST_UNREADABLE)
                    }
                    else -> onTrace("opened ${target.date}: " + (rejection?.let { "refused, $it" } ?: "not settled yet"))
                }
                rows = awaitList() ?: return outcome(noList())
                continue
            }
            if (pages >= limits.maxPages) return outcome(Stop.PAGE_BUDGET)
            screen.swipe(540, SWIPE_FROM_Y, 540, SWIPE_TO_Y, 450)
            pages++
            delay(limits.swipeSettleMillis)
            rows = awaitList() ?: return outcome(noList())
            if (fresh(rows).isEmpty()) {
                // The list may still be loading its next page.
                delay(limits.waitMillis / 4)
                rows = awaitList() ?: return outcome(noList())
                if (fresh(rows).isEmpty()) return outcome(Stop.END_OF_LIST)
            }
        }
    }

    /**
     * Rows below the longest run that matches the end of what the walk already passed. Rows above
     * the first date header inherit their date from that run, or from the last passed row.
     */
    private fun fresh(rows: List<ListRow>): List<ListRow> {
        val signatures = rows.map(::signature)
        var bestEnd = -1
        var bestLength = 0
        for (end in signatures.indices) {
            var length = 0
            while (length <= end && length < seen.size && signatures[end - length] == seen[seen.size - 1 - length].first) length++
            // On ties the earliest match wins: re-reading a row costs a detail screen, skipping one loses it.
            if (length > bestLength) { bestLength = length; bestEnd = end }
        }
        var carried = seen.lastOrNull()?.second
        return rows.drop(bestEnd + 1).mapNotNull { row ->
            val date = row.date ?: carried ?: return@mapNotNull null
            carried = date
            row.copy(date = date)
        }
    }

    /** Returns the payment, null for a row that is not a settled payment, or false when the screen misbehaved. */
    private suspend fun openDetail(row: ListRow): Any? {
        screen.tap(row.tapX, row.tapY)
        val nodes = awaitNodes { nodes -> nodes.any { it.text in DETAIL_LABELS } }
            // A detail screen that never shows an ID is remembered instead of retried on every walk.
            ?: screen.dump()?.let { runCatching { GojekScreenParser.nodes(it) }.getOrNull() }?.takeIf { nodes -> nodes.any { it.text == "Status" } }
        if (nodes == null) {
            onTrace("opened ${row.date}: tapped ${row.tapX},${row.tapY}, " +
                if (shouldAbort()) "and the walk was told to stop (screen off or Gojek in use)" else "and the screen showed no detail labels")
            val after = screen.dump()
            onDiagnostic("BEFORE TAP (the list the row came from)\n${listDump.orEmpty()}\nAFTER TAP\n${after.orEmpty()}")
            screen.back()
            return false
        }
        val result: Any? = try {
            GojekScreenParser.detail(nodes)?.let { GojekScreenParser.transaction(row, it, zone) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (refused: ProviderReadException) {
            rejection = refused.message
            onSkip("${row.date}|${signature(row)}")
            null
        }
        val back = nodes.firstOrNull { it.id == "navigation_back" }
        if (back != null) screen.tap(back.centerX, back.centerY) else screen.back()
        return result
    }

    /**
     * The list once two dumps in a row place every row identically. Right after a back press or a swipe the
     * window is still sliding or flinging: its rows sit at shifted coordinates and nothing is clipped to the
     * display yet, so a row below the edge looks tappable and a tap lands in the wrong place.
     */
    private suspend fun awaitList(): List<ListRow>? {
        var previous: List<ListRow>? = null
        var waited = 0L
        while (true) {
            if (shouldAbort()) return null
            val raw = screen.dump()
            lastDump = raw
            val rows = raw?.let { runCatching { GojekScreenParser.nodes(it) }.getOrNull() }
                ?.takeIf { nodes -> nodes.any { it.id == "tvAmount" } }
                ?.let { GojekScreenParser.listRows(it, today) }
            if (rows != null && rows == previous) { listDump = raw; return rows }
            // The budget is spent, but the dump just taken still gets compared with the one before it.
            if (waited > limits.waitMillis) {
                onDiagnostic("LIST NEVER SETTLED\n${raw.orEmpty()}")
                return null
            }
            previous = rows
            delay(limits.pollMillis)
            waited += limits.pollMillis
        }
    }

    /** The newest raw dump, and the one the current list rows were read from; only used to explain a failed tap. */
    private var lastDump: String? = null
    private var listDump: String? = null

    private suspend fun awaitNodes(ready: (List<GojekScreenParser.Node>) -> Boolean): List<GojekScreenParser.Node>? {
        var waited = 0L
        while (waited <= limits.waitMillis) {
            if (shouldAbort()) return null
            val raw = screen.dump()
            lastDump = raw
            val nodes = raw?.let { runCatching { GojekScreenParser.nodes(it) }.getOrNull() }
            if (nodes != null && ready(nodes)) return nodes
            delay(limits.pollMillis)
            waited += limits.pollMillis
        }
        return null
    }

    private fun signature(row: ListRow) = listOf(row.description, row.notes.orEmpty(), row.direction.name, row.amountMinor.toString()).joinToString("|")

    private companion object {
        const val SWIPE_FROM_Y = 1900
        const val SWIPE_TO_Y = 900
        const val MAX_FAILURES = 3
        const val TRACE_DATES = 40
        val DETAIL_LABELS = setOf("ID transaksi", "Transaction ID")
    }
}
