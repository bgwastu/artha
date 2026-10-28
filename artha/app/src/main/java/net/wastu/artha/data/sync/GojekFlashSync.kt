package net.wastu.artha.data.sync

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.data.ingestion.RootFileReader
import net.wastu.artha.data.providers.ProviderPreferences

/**
 * When a GoPay flash may launch Gojek, how long to wait after the previous successful
 * launch, and which outcomes count as that launch. Skips and aborts do not block the next try.
 */
internal object GojekFlashPolicy {
    const val COOLDOWN_MILLIS = 15 * 60_000L
    const val SLEEP_CONFIRM_MILLIS = 2_000L

    fun cooldownActive(result: String?, lastMillis: Long?, now: Long): Boolean {
        if (result != "FLASHED" && result != "QUIET") return false
        val at = lastMillis ?: return false
        if (now < at) return false
        return now - at < COOLDOWN_MILLIS
    }

    fun recordsAsLastRun(result: GojekFlashSync.Result): Boolean =
        result == GojekFlashSync.Result.FLASHED || result == GojekFlashSync.Result.QUIET
}

/**
 * One in-flight Gojek launch shared by screen-off, the periodic job, and manual refresh.
 */
class GojekFlashController(
    private val reader: RootFileReader,
    private val preferences: ProviderPreferences,
    private val isInteractive: () -> Boolean,
) {
    private val running = AtomicBoolean(false)
    private val abort = AtomicBoolean(false)

    fun abortIfRunning() { abort.set(true) }

    suspend fun run(gojekImportEnabled: Boolean, ignoreCooldown: Boolean = false): GojekFlashSync.Result {
        if (!running.compareAndSet(false, true)) {
            android.util.Log.i("ArthaFlash", "BUSY: already running")
            return GojekFlashSync.Result.BUSY
        }
        abort.set(false)
        try {
            val last = preferences.gojekFlash.value
            if (!ignoreCooldown && GojekFlashPolicy.cooldownActive(last.result, last.millis, System.currentTimeMillis())) {
                android.util.Log.i("ArthaFlash", "SKIPPED_COOLDOWN")
                return GojekFlashSync.Result.SKIPPED_COOLDOWN
            }
            val result = GojekFlashSync(reader, isInteractive, abort::get).run(gojekImportEnabled)
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
 * Invisible GoPay history refresh for background imports.
 *
 * Launches Gojek's own history screen through its `gojek://gopay/history` deep link while the
 * display is sleeping: the activity runs behind the keyguard, fetches page one with Gojek's own
 * signed requests, and the in-process capture hook records the responses. Cleanup force-stops
 * Gojek after a short fsync window so a lockscreen or a fast screen-on cannot leave it in front.
 * [isInteractive] is [android.os.PowerManager.isInteractive] — cheaper than dumpsys, and it
 * matches what the user actually sees.
 */
class GojekFlashSync(
    private val reader: RootFileReader,
    private val isInteractive: () -> Boolean,
    private val shouldAbort: () -> Boolean,
) {

    enum class Result {
        FLASHED, QUIET, ABORTED, SKIPPED_SCREEN_ON, ROOT_UNAVAILABLE, SKIPPED, SKIPPED_COOLDOWN, BUSY,
    }

    /**
     * Returns the trigger outcome for logging; a missing capture is not an error because the
     * next import reports provider state from the same data the walk would gather.
     */
    suspend fun run(gojekImportEnabled: Boolean): Result = withContext(Dispatchers.IO) {
        if (!gojekImportEnabled) return@withContext skipped(Result.SKIPPED, "gojek import disabled")
        if (shouldAbort() || isInteractive()) {
            return@withContext skipped(Result.SKIPPED_SCREEN_ON, "display is interactive")
        }
        val installed = sh("pm path com.gojek.app 2>/dev/null")
            ?: return@withContext skipped(Result.ROOT_UNAVAILABLE, "no root shell")
        if (installed.none { it.startsWith("package:") }) {
            return@withContext skipped(Result.SKIPPED, "gojek not installed")
        }

        val before = latestMarkerTimestamp()
        log("launch: newest captured marker ts=$before")
        sh("am start --activity-exclude-from-recents -a android.intent.action.VIEW -d '$DEEP_LINK' com.gojek.app >/dev/null 2>&1")

        var grew = false
        var woken = false
        for (round in 0 until POLL_ROUNDS) {
            if (shouldAbort() || isInteractive()) { woken = true; break }
            delay(POLL_INTERVAL_MILLIS)
            if (latestMarkerTimestamp() > before) { grew = true; break }
        }
        if (grew && !woken) {
            var remaining = BURST_SETTLE_MILLIS
            while (remaining > 0) {
                if (shouldAbort() || isInteractive()) { woken = true; break }
                delay(BURST_SLICE_MILLIS)
                remaining -= BURST_SLICE_MILLIS
            }
        } else if (!grew && !woken) {
            log("quiet: no order-history rows arrived during the settle window")
        }
        closeGojek()
        when {
            grew -> {
                log("flashed: newest marker ts=${latestMarkerTimestamp()}")
                Result.FLASHED
            }
            woken -> skipped(Result.ABORTED, "display woke before capture settled")
            else -> {
                log("done: quiet")
                Result.QUIET
            }
        }
    }

    /** Force-stop is the close path: HOME does not retire a keyguard-hidden Gojek, and it
     * steals the foreground if the user already woke the phone. */
    private suspend fun closeGojek() {
        delay(HOOK_FLUSH_MILLIS)
        sh("am force-stop com.gojek.app >/dev/null 2>&1")
        log("cleanup: force-stopped Gojek")
    }

    /**
     * Newest hook-captured page-one timestamp. Rotation only evicts old rows, so a ts increase
     * stays a reliable growth signal while row counts are not (a rotate rewrites `.1`).
     */
    private suspend fun latestMarkerTimestamp(): Long =
        sh("grep -h '$MARKER' /data/data/com.gojek.app/files/artha_gojek_capture.jsonl.1 /data/data/com.gojek.app/files/artha_gojek_capture.jsonl 2>/dev/null | tail -1 | head -c 96 | grep -o '\"ts\":[0-9]*'")
            ?.firstOrNull()?.filter(Char::isDigit)?.toLongOrNull() ?: 0L

    private suspend fun sh(command: String): List<String>? = reader.executeCommandWithOutput(command)

    private fun log(message: String) = android.util.Log.i(TAG, message)

    private fun skipped(result: Result, why: String): Result = log("$result: $why").let { result }

    private companion object {
        const val TAG = "ArthaFlash"
        const val DEEP_LINK = "gojek://gopay/history"
        const val MARKER = "customer.gopayapi.com/v3/users/order-history"

        const val POLL_ROUNDS = 6
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val BURST_SETTLE_MILLIS = 2_500L
        const val BURST_SLICE_MILLIS = 500L
        const val HOOK_FLUSH_MILLIS = 400L
    }
}
