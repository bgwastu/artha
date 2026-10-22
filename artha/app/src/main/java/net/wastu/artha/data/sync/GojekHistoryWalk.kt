package net.wastu.artha.data.sync

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.core.root.RootAccess

private const val TAG = "ArthaWalk"

enum class GojekWalkResult { WALKED, ROOT_UNAVAILABLE, GOJEK_MISSING, HISTORY_SCREEN_NOT_FOUND, INTERRUPTED, FAILED }

/**
 * Drives the phone's *visible* Gojek app so its own history screen reloads data that
 * Artha's capture hook then records. The screen stays in the foreground and every
 * network request is Gojek's own; nothing here fabricates or replays API calls.
 */
class GojekHistoryWalk(private val context: Context, private val root: RootAccess) {

    // Screen-relative coordinates: the walk works on any portrait phone size.
    private val metrics = context.resources.displayMetrics
    private val centerX = metrics.widthPixels / 2
    private val height = metrics.heightPixels
    private val SCROLL_DOWN = listOf(centerX, height * 72 / 100, centerX, height * 21 / 100)
    private val SCROLL_UP = listOf(centerX, height * 22 / 100, centerX, height * 75 / 100)

    /**
     * Walks the visible transaction history. [stopAtMillis] is the newest payment
     * already known to Artha (ledger's oldest GoPay row or the previous walk's depth:
     * below that, scrolling only re-reads saved pages). [onProgress] fires per batch
     * with what has been gathered so far.
     */
    suspend fun walk(stopAtMillis: Long, onProgress: (String) -> Unit, onCapturePressure: suspend () -> Unit): GojekWalkResult = withContext(Dispatchers.IO) {
        if (!root.verify()) return@withContext GojekWalkResult.ROOT_UNAVAILABLE
        val shell = Shell.Builder.create().setContext(context).setTimeout(15)
            .build("su", "-M", "-c", "/system/bin/sh")
        shell.use { session ->
            if (!session.isRoot) return@use GojekWalkResult.ROOT_UNAVAILABLE
            fun exec(vararg commands: String): List<String> {
                val out = mutableListOf<String>()
                session.newJob().to(out, mutableListOf()).add(*commands).exec()
                return out
            }
            try {
                val installed = exec("pm path com.gojek.app 2>/dev/null").any { it.startsWith("package:") }
                if (!installed) return@use GojekWalkResult.GOJEK_MISSING

                fun foregroundGojek(): Boolean = exec("dumpsys window | grep mCurrentFocus")
                    .firstOrNull()?.contains("com.gojek.app") == true
                if (!foregroundGojek()) {
                    exec("monkey -p com.gojek.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
                    delay(APP_SETTLE_MILLIS)
                }
                if (!dumpHasHistory { exec(it) }) {
                    android.util.Log.i(TAG, "history header not found; looking for an entry link")
                    // The app opened elsewhere; the history entry is worth one look, else bail.
                    val entry = dumpNodes { exec(it) }.firstOrNull { it.matches("riwayat", "aktivitas", "history") }
                    if (entry != null) {
                        entry.tap { exec(it) }
                        delay(SCREEN_SETTLE_MILLIS)
                    }
                    if (!dumpHasHistory { exec(it) }) { android.util.Log.i(TAG, "history screen not found"); return@use GojekWalkResult.HISTORY_SCREEN_NOT_FOUND }
                }
                // Start from the newest payment so the walk covers the whole list fresh.
                repeat(TOP_SWIPES) { exec("input swipe ${SCROLL_UP[0]} ${SCROLL_UP[1]} ${SCROLL_UP[2]} ${SCROLL_UP[3]} 60") }
                delay(SCREEN_SETTLE_MILLIS)

                var last = captureBytes { exec(it) }
                var steadyReads = 0
                var batches = 0
                var oldestSeen = Long.MAX_VALUE
                while (batches < MAX_BATCHES && steadyReads < STEADY_LIMIT) {
                    if (!foregroundGojek()) { android.util.Log.i(TAG, "interrupted: focus left Gojek"); return@use GojekWalkResult.INTERRUPTED }
                    repeat(SWIPES_PER_BATCH) {
                        exec("input swipe ${SCROLL_DOWN[0]} ${SCROLL_DOWN[1]} ${SCROLL_DOWN[2]} ${SCROLL_DOWN[3]} 120")
                        delay(SWIPE_SETTLE_MILLIS)
                    }
                    delay(PAGE_SETTLE_MILLIS)
                    val size = captureBytes { exec(it) }
                    if (size <= last) steadyReads += 1 else steadyReads = 0
                    last = max(last, size)
                    batches += 1

                    // A long walk's own volume would rotate its earliest pages out of the
                    // capture window; hand them to the ledger before eviction.
                    if (size > CAPTURE_PRESSURE_BYTES) {
                        onCapturePressure()
                        last = captureBytes { exec(it) }
                        steadyReads = 0
                    }

                    dumpNodes { exec(it) }.asSequence()
                        .mapNotNull { dateMillisFromText(it.text) }
                        .minOrNull()?.let { visible ->
                            oldestSeen = min(oldestSeen, visible)
                            android.util.Log.i(TAG, "progress: back to ${formatWalkDay(oldestSeen)}, capture=$last"); onProgress("Gathered payments back to ${formatWalkDay(oldestSeen)}")
                        }
                    if (oldestSeen <= stopAtMillis) { // below this every row is already saved
                        android.util.Log.i(TAG, "stop: reached known ledger depth ${formatWalkDay(oldestSeen)}")
                        break
                    }
                }
                exec("input keyevent KEYCODE_HOME")
                if (oldestSeen != Long.MAX_VALUE) storeWalkFloor(context, oldestSeen) // persist how deep the walk already went
                GojekWalkResult.WALKED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                session.newJob().add("input keyevent KEYCODE_HOME").exec()
                GojekWalkResult.FAILED
            }
        }
    }

    private fun captureBytes(run: (String) -> List<String>): Long = run(
        "stat -c %s /data/data/com.gojek.app/files/artha_gojek_capture.jsonl 2>/dev/null; " +
            "stat -c %s /data/data/com.gojek.app/files/artha_gojek_capture.jsonl.1 2>/dev/null",
    ).mapNotNull { it.trim().toLongOrNull() }.sum()

    private fun dump(run: (String) -> List<String>): List<UiNode> {
        // Clear first: a failed dump must never be mistaken for the previous screen.
        val raw = run(
            "rm -f /data/local/tmp/artha_walk.xml; " +
                "uiautomator dump /data/local/tmp/artha_walk.xml; " +
                "cat /data/local/tmp/artha_walk.xml 2>/dev/null",
        ).joinToString("\n")
        android.util.Log.i(TAG, "dump len=${raw.length} riwayat=${raw.contains("Riwayat transaksi")} head=${raw.take(80)}")
        if (raw.length < 200) return emptyList()
        val nodes = NODE_REGEX.findAll(raw).map { match ->
            UiNode(
                text = match.groupValues[1],
                description = match.groupValues[2],
                left = match.groupValues[3].toInt(),
                top = match.groupValues[4].toInt(),
                right = match.groupValues[5].toInt(),
                bottom = match.groupValues[6].toInt(),
            )
        }.toList()
        android.util.Log.i(TAG, "parsed=${nodes.size} rawlen=${raw.length}")
        return nodes
    }

    private fun dumpNodes(run: (String) -> List<String>): List<UiNode> {
        val nodes = dump(run)
        return if (nodes.isEmpty()) dump(run) else nodes // one retry; animations can block a first dump
    }

    private fun dumpHasHistory(run: (String) -> List<String>): Boolean {
        val nodes = dumpNodes(run)
        val hit = nodes.any { it.matches("riwayat transaksi") }
        if (!hit) {
            android.util.Log.i(TAG, "dumpHasHistory nodes=${nodes.size} first=${nodes.firstOrNull()?.text} sample=${nodes.drop(3).take(6).joinToString("|") { it.text }}")
        }
        return hit
    }

    private data class UiNode(
        val text: String, val description: String,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
    ) {
        fun matches(vararg needles: String): Boolean {
            val haystack = text.lowercase() + " " + description.lowercase()
            return needles.any { haystack.contains(it) }
        }

        fun tap(run: (String) -> List<String>) {
            run("input tap ${min(left + right, 2140) / 2} ${min(top + bottom, 4660) / 2}")
        }
    }

    internal companion object {
        /** How deep a previous walk already went: below it the ledger holds all the saves. */
        /** Pure rule so repeated walks keep only genuinely deeper history. */
        internal fun nextWalkFloor(stored: Long, oldestSeen: Long): Long = when {
            oldestSeen == Long.MAX_VALUE || oldestSeen <= 0 -> stored
            else -> minOf(stored, oldestSeen)
        }

        fun storeWalkFloor(context: Context, millis: Long) {
            val preferences = context.getSharedPreferences(WALK_PREFS, Context.MODE_PRIVATE)
            val stored = preferences.getLong(FLOOR_KEY, Long.MAX_VALUE)
            val next = nextWalkFloor(stored, millis)
            if (next != stored) preferences.edit().putLong(FLOOR_KEY, next).apply()
        }

        fun walkFloor(context: Context): Long =
            context.getSharedPreferences(WALK_PREFS, Context.MODE_PRIVATE).getLong(FLOOR_KEY, Long.MAX_VALUE)

        /** Midnight Jakarta of the date a visible list header names; null for non-date texts. */
        fun dateMillisFromText(text: String): Long? {
            val match = DATE_TEXT_REGEX.find(text) ?: return null
            val day = match.groupValues[1].toInt()
            val month = MONTH_NUMBERS[match.groupValues[2].lowercase()] ?: return null
            val year = match.groupValues[3].toInt()
            return runCatching {
                java.time.LocalDate.of(year, month, day)
                    .atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant().toEpochMilli()
            }.getOrNull()
        }

        fun formatWalkDay(millis: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US)
                .withZone(java.time.ZoneId.of("Asia/Jakarta"))
                .format(java.time.Instant.ofEpochMilli(millis))

        // Match the exact XML attribute order uiautomator emits; the quote after bounds= must be literal.
        val NODE_REGEX = Regex(
            "text=\"([^\"]*)\"[^>]*content-desc=\"([^\"]*)\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]",
        )
        val DATE_TEXT_REGEX = Regex("""\b(\d{1,2}) (Jan|Feb|Mar|Apr|Mei|Jun|Jul|Agu|Agt|Sep|Okt|Nov|Des) (\d{4})\b""")
        val MONTH_NUMBERS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mei" to 5, "jun" to 6, "jul" to 7,
            "agu" to 8, "agt" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "des" to 12,
        )
        const val WALK_PREFS = "gojek_history_walk"
        const val FLOOR_KEY = "walk_depth_ms"
        const val MAX_BATCHES = 120
        const val CAPTURE_PRESSURE_BYTES = 9_000_000L
        const val SWIPES_PER_BATCH = 4
        const val TOP_SWIPES = 6
        const val STEADY_LIMIT = 3
        const val APP_SETTLE_MILLIS = 4_500L
        const val SCREEN_SETTLE_MILLIS = 1_500L
        const val PAGE_SETTLE_MILLIS = 1_400L
        const val SWIPE_SETTLE_MILLIS = 220L
    }
}
