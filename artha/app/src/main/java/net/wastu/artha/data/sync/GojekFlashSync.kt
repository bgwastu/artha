package net.wastu.artha.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.data.ingestion.RootFileReader
/**
 * Invisible GoPay history refresh for background imports.
 *
 * Launches Gojek's own history screen through its `gojek://gopay/history` deep link while the
 * display is sleeping: the activity runs behind the keyguard, fetches page one with Gojek's own
 * signed requests, and the in-process capture hook records the responses without anything being
 * rendered. Cleanup uses activity starts only — injected key events wake the display.
 */
class GojekFlashSync(private val reader: RootFileReader) {

    enum class Result { FLASHED, QUIET, SKIPPED_SCREEN_ON, ROOT_UNAVAILABLE, SKIPPED }

    /**
     * Returns the trigger outcome for logging; a missing capture is not an error because the
     * next import reports provider state from the same data the walk would gather.
     */
    suspend fun run(gojekImportEnabled: Boolean): Result = withContext(Dispatchers.IO) {
        if (!gojekImportEnabled) return@withContext skipped(Result.SKIPPED, "gojek import disabled")
        val wakefulness = sh("dumpsys power | grep -m1 '  mWakefulness=' | cut -d= -f2")
            ?.firstOrNull()?.trim()?.lowercase()
        if (wakefulness == null) return@withContext skipped(Result.ROOT_UNAVAILABLE, "no root shell")
        // Fail closed: unknown wakefulness values mean the display state is unverified, so the
        // launch is deferred to a later run rather than risk covering the user's open app.
        if (wakefulness !in SLEEPING_WAKEFULNESS) {
            return@withContext skipped(Result.SKIPPED_SCREEN_ON, "display state '$wakefulness' is not a known sleep state")
        }
        if (sh("pm path com.gojek.app 2>/dev/null").isNullOrEmpty()) {
            return@withContext skipped(Result.SKIPPED, "gojek not installed")
        }

        val before = latestMarkerTimestamp()
        log("launch: newest captured marker ts=$before wakefulness=$wakefulness")
        sh("am start -a android.intent.action.VIEW -d '$DEEP_LINK' com.gojek.app >/dev/null 2>&1")

        var grew = false
        for (round in 0 until POLL_ROUNDS) {
            delay(POLL_INTERVAL_MILLIS)
            if (latestMarkerTimestamp() > before) { grew = true; break }
        }
        if (grew) {
            // The listing screen fires page-one plus a prefetch burst; give the burst room to
            // land before cleanup replaces Gojek behind the keyguard.
            delay(BURST_SETTLE_MILLIS)
        } else {
            log("quiet: no order-history rows arrived during the settle window")
        }
        closeGojek()
        log(if (grew) "flashed: newest marker ts=${latestMarkerTimestamp()}" else "done: quiet")
        if (grew) Result.FLASHED else Result.QUIET
    }

    /** Replaces Gojek's (possibly user-visible) history screen with the launcher; a stuck screen
     * falls back to force-stop so the user never has to dismiss the flash themselves. */
    private suspend fun closeGojek() {
        sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1")
        delay(CLEANUP_SETTLE_MILLIS)
        val focus = sh("dumpsys window | grep -m1 mCurrentFocus").orEmpty().joinToString(" ")
        if (focus.contains("com.gojek.app")) {
            log("cleanup: launcher did not take focus, force-stopping Gojek")
            sh("am force-stop com.gojek.app >/dev/null 2>&1")
        } else {
            log("cleanup: launcher focus ok")
        }
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

        // Known sleep states for `dumpsys power` wakefulness; HyperOS reports words, some builds digits.
        val SLEEPING_WAKEFULNESS = setOf("dozing", "asleep", "0", "3")

        const val POLL_ROUNDS = 10
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val BURST_SETTLE_MILLIS = 6_000L
        const val CLEANUP_SETTLE_MILLIS = 3_000L
    }
}
