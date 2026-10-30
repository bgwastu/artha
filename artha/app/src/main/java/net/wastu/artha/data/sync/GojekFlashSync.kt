package net.wastu.artha.data.sync

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.ingestion.GojekScreenStore
import net.wastu.artha.data.ingestion.RootFileReader
import net.wastu.artha.data.providers.ProviderPreferences

/**
 * How long to wait after the previous completed GoPay walk, and which outcomes count as one.
 * Skips and aborts do not block the next try.
 */
internal object GojekFlashPolicy {
    const val COOLDOWN_MILLIS = 15 * 60_000L

    fun cooldownActive(result: String?, lastMillis: Long?, now: Long): Boolean {
        if (result != "FLASHED" && result != "QUIET") return false
        val at = lastMillis ?: return false
        if (now < at) return false
        return now - at < COOLDOWN_MILLIS
    }

    fun recordsAsLastRun(result: GojekScreenSync.Result): Boolean =
        result == GojekScreenSync.Result.FLASHED || result == GojekScreenSync.Result.QUIET
}

/**
 * One in-flight GoPay walk shared by the periodic job and manual refresh.
 */
class GojekFlashController(
    private val reader: RootFileReader,
    private val preferences: ProviderPreferences,
    private val isInteractive: () -> Boolean,
    private val ledgerPayments: suspend () -> List<UnifiedTransaction>,
) {
    private val running = AtomicBoolean(false)
    private val abort = AtomicBoolean(false)

    fun abortIfRunning() { abort.set(true) }

    suspend fun run(gojekImportEnabled: Boolean, ignoreCooldown: Boolean = false): GojekScreenSync.Result {
        if (!running.compareAndSet(false, true)) {
            android.util.Log.i("ArthaScreen", "BUSY: already running")
            return GojekScreenSync.Result.BUSY
        }
        abort.set(false)
        try {
            val last = preferences.gojekFlash.value
            if (!ignoreCooldown && GojekFlashPolicy.cooldownActive(last.result, last.millis, System.currentTimeMillis())) {
                android.util.Log.i("ArthaScreen", "SKIPPED_COOLDOWN")
                return GojekScreenSync.Result.SKIPPED_COOLDOWN
            }
            val result = GojekScreenSync(reader, isInteractive, abort::get, ledgerPayments).run(gojekImportEnabled)
            if (GojekFlashPolicy.recordsAsLastRun(result)) {
                preferences.recordGojekFlash(result.name)
            }
            return result
        } finally {
            running.set(false)
        }
    }
}

/**
 * Reads new GoPay payments from Gojek's own history screens, invisibly.
 *
 * A root helper opens an always-unlocked virtual display with its own focus, Gojek's history
 * deep link runs there, and [GojekScreenWalk] reads the list and the detail of each payment the
 * ledger lacks into [GojekScreenStore]. Android pauses that display while the phone sleeps, so
 * the walk only runs while the screen is on; the user's own screen and focus stay untouched.
 */
class GojekScreenSync(
    private val reader: RootFileReader,
    private val isInteractive: () -> Boolean,
    private val shouldAbort: () -> Boolean,
    private val ledgerPayments: suspend () -> List<UnifiedTransaction>,
) {

    enum class Result {
        FLASHED, QUIET, ABORTED, SKIPPED_SCREEN_OFF, SKIPPED_GOJEK_IN_USE, ROOT_UNAVAILABLE, SKIPPED, SKIPPED_COOLDOWN, BUSY,
    }

    suspend fun run(gojekImportEnabled: Boolean): Result = withContext(Dispatchers.IO) {
        if (!gojekImportEnabled) return@withContext skipped(Result.SKIPPED, "gojek import disabled")
        if (android.os.Build.VERSION.SDK_INT < 33) return@withContext skipped(Result.ROOT_UNAVAILABLE, "screen walk needs Android 13")
        if (shouldAbort() || !isInteractive()) return@withContext skipped(Result.SKIPPED_SCREEN_OFF, "display is off")
        val installed = sh("pm path com.gojek.app 2>/dev/null")
            ?: return@withContext skipped(Result.ROOT_UNAVAILABLE, "no root shell")
        if (installed.none { it.startsWith("package:") }) return@withContext skipped(Result.SKIPPED, "gojek not installed")
        if (gojekResumed()) return@withContext skipped(Result.SKIPPED_GOJEK_IN_USE, "gojek is on screen")

        val store = GojekScreenStore(reader.context.filesDir)
        val zone = ZoneId.systemDefault()
        val known = ledgerPayments().filter { it.provider == Provider.GOJEK && it.providerTransactionId.startsWith("gopay:") } +
            store.transactions()
        // A fresh Gojek process starts on the walk's display instead of moving a background task there.
        sh("am force-stop com.gojek.app")
        val session = RootScreenSession.start(reader.context.applicationInfo.sourceDir)
            ?: return@withContext skipped(Result.ROOT_UNAVAILABLE, "screen helper did not start")
        val outcome = try {
            if (!session.open()) return@withContext skipped(Result.ABORTED, "virtual display unavailable")
            if (!session.launch(DEEP_LINK)) return@withContext skipped(Result.ABORTED, "history deep link failed")
            GojekScreenWalk(
                screen = session,
                known = known,
                skipped = store.skipped(),
                backfill = store.backfillPending,
                zone = zone,
                today = LocalDate.now(zone),
                shouldAbort = { shouldAbort() || !isInteractive() },
                onPayment = store::append,
                onSkip = store::skip,
            ).run()
        } finally {
            // Cleanup must finish even when the caller is cancelled.
            withContext(NonCancellable) {
                session.close()
                // Leave Gojek alone if the user opened it meanwhile; otherwise retire the walk's process.
                if (!gojekResumed()) sh("am force-stop com.gojek.app")
            }
        }
        log("walk: $outcome")
        when (outcome.stop) {
            GojekScreenWalk.Stop.DETAIL_BUDGET -> store.backfillPending = true
            GojekScreenWalk.Stop.CAUGHT_UP, GojekScreenWalk.Stop.END_OF_LIST -> store.backfillPending = false
            else -> Unit
        }
        when {
            outcome.stored > 0 -> Result.FLASHED
            outcome.stop == GojekScreenWalk.Stop.ABORTED || outcome.stop == GojekScreenWalk.Stop.LIST_UNREADABLE -> Result.ABORTED
            else -> Result.QUIET
        }
    }

    /** True when a Gojek activity is resumed on the user's display, which means they have it open. */
    private suspend fun gojekResumed(): Boolean =
        sh("dumpsys activity activities | awk '/^Display #/ { d = \$2 } /ResumedActivity/ && /com\\.gojek\\.app/ && d == \"#0\" { n++ } END { print n + 0 }'")
            ?.firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

    private suspend fun sh(command: String): List<String>? = reader.executeCommandWithOutput(command)

    private fun log(message: String) = android.util.Log.i(TAG, message)

    private fun skipped(result: Result, why: String): Result = log("$result: $why").let { result }

    private companion object {
        const val TAG = "ArthaScreen"
        const val DEEP_LINK = "gojek://gopay/history"
    }
}
