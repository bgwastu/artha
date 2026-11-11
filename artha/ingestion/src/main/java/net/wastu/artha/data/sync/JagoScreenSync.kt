package net.wastu.artha.data.sync

import android.os.Build
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.core.log.ArthaLog
import net.wastu.artha.data.ingestion.RootFileReader
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** The hidden-display unlock, as the Jago adapter sees it. */
fun interface JagoUnlock {
    suspend fun run(trigger: String): JagoScreenSync.Result
}

/**
 * Unlocks Bank Jago with the user's PIN on a hidden virtual display so Jago refreshes its own cache.
 * Launching a banking app that way is a heavy-handed thing to do, so it only ever runs for a sync the
 * user asked for, and every run leaves one summary line in [ArthaLog] saying how far it got and how
 * Jago ended.
 */
class JagoScreenSync(
    private val reader: RootFileReader,
    private val isInteractive: () -> Boolean,
    private val shouldAbort: () -> Boolean,
    private val getPin: () -> String?,
    /** Called the moment Bank Jago accepts (true) or rejects (false) the typed PIN, before the cache refresh is awaited. */
    private val onPinAnswer: (accepted: Boolean) -> Unit = {},
) : JagoUnlock {
    enum class Result {
        SYNCED, QUIET, ABORTED, SKIPPED_SCREEN_OFF, SKIPPED_JAGO_IN_USE,
        ROOT_UNAVAILABLE, SKIPPED_NO_PIN, SKIPPED_NOT_INSTALLED, UNLOCK_FAILED, WRONG_PIN, JAGO_EXITED
    }

    /** The furthest point a run reached, in order. */
    enum class Stage { LAUNCHED, PIN_SCREEN, PIN_ENTERED, UNLOCKED, SETTLED }

    /** [trigger] says what asked for the run; it is only recorded. */
    override suspend fun run(trigger: String): Result = RootScreenSession.exclusive {
        withContext(Dispatchers.IO) { keepingScreenOn(reader.context, "jago_unlock", SCREEN_LOCK_MAX_MS) { Attempt(trigger).execute() } }
    }

    private inner class Attempt(private val trigger: String) {
        private val startedAt = System.currentTimeMillis()
        private var stage: Stage? = null
        private var pinScreenAtMs: Long? = null
        private var biometricDismissals = 0
        private var dialogTaps = 0
        private var note = ""
        private var screen: String? = null
        private var exit: JagoScreens.ExitRecord? = null
        private var cacheBefore: String? = null
        private var cacheAfter: String? = null
        private var keypad: Map<Char, Pair<Int, Int>> = emptyMap()
        private var lastDump: String? = null
        private var jagoSeen = false
        private lateinit var session: RootScreenSession

        suspend fun execute(): Result {
            var result = Result.ABORTED
            try {
                result = attempt()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                note = "cancelled"
                throw cancelled
            } catch (error: Exception) {
                note = "failed with ${error.javaClass.simpleName}"
                throw error
            } finally {
                record(result)
            }
            return result
        }

        private suspend fun attempt(): Result {
            if (Build.VERSION.SDK_INT < 33) return skipped(Result.ROOT_UNAVAILABLE, "screen walk needs Android 13")
            if (shouldAbort() || !isInteractive()) return skipped(Result.SKIPPED_SCREEN_OFF, "display is off")
            val pin = getPin()?.trim()
            if (pin.isNullOrEmpty()) return skipped(Result.SKIPPED_NO_PIN, "no usable PIN")
            val installed = sh("pm path $PACKAGE 2>/dev/null") ?: return skipped(Result.ROOT_UNAVAILABLE, "no root shell")
            if (installed.none { it.startsWith("package:") }) return skipped(Result.SKIPPED_NOT_INSTALLED, "Bank Jago is not installed")
            if (jagoResumed()) return skipped(Result.SKIPPED_JAGO_IN_USE, "Bank Jago is on screen")

            cacheBefore = cacheStat()
            ArthaLog.i(TAG, "launching Bank Jago on a hidden display", mapOf("trigger" to trigger))
            sh("am force-stop $PACKAGE")
            session = RootScreenSession.start(reader.context.applicationInfo.sourceDir)
                ?: return skipped(Result.ROOT_UNAVAILABLE, "screen helper did not start")
            try {
                if (!session.open()) return skipped(Result.ABORTED, "virtual display unavailable")
                if (!session.start("$PACKAGE/.MainActivity")) return skipped(Result.ABORTED, "launching Bank Jago failed")
                stage = Stage.LAUNCHED
                awaitPinScreen()?.let { return it }
                enterPin(pin)?.let { return it }
                return settle()
            } finally {
                withContext(NonCancellable) {
                    // Still alive here means we are about to stop it; dead means it ended by itself.
                    if (exit == null && pid() == null) exit = readExit()
                    session.close()
                    if (!jagoResumed()) sh("am force-stop $PACKAGE")
                }
            }
        }

        /** Waits for the keypad, clearing fingerprint prompts and benign dialogs that stand in the way. */
        private suspend fun awaitPinScreen(): Result? {
            val deadline = System.currentTimeMillis() + PIN_SCREEN_WAIT_MS
            var polls = 0
            while (System.currentTimeMillis() < deadline) {
                if (interrupted()) return skipped(Result.ABORTED, "interrupted while waiting for the PIN screen")
                delay(POLL_MS)
                polls++
                if (exited()) return jagoExited("Bank Jago closed itself before the PIN screen")
                val dump = session.dump()
                if (dump != null) {
                    lastDump = dump
                    if (JagoScreens.hasPinPrompt(dump)) {
                        val digits = parseDigitCenters(dump)
                        if (digits.isNotEmpty()) {
                            keypad = digits
                            stage = Stage.PIN_SCREEN
                            pinScreenAtMs = elapsed()
                            return null
                        }
                    }
                    val target = if (dialogTaps < MAX_DIALOG_TAPS) JagoScreens.dismissTarget(dump) else null
                    if (target != null) {
                        dialogTaps++
                        ArthaLog.i(TAG, "tapping a button in front of the PIN screen", mapOf("tap" to dialogTaps.toString()))
                        session.tap(target.first, target.second)
                        continue
                    }
                }
                if (polls % 3 == 0) dismissBiometricPrompt()
            }
            screen = lastDump?.let(JagoScreens::signature)
            return skipped(Result.UNLOCK_FAILED, "PIN screen not found within ${PIN_SCREEN_WAIT_MS / 1000} s")
        }

        private suspend fun enterPin(pin: String): Result? {
            dismissBiometricPrompt()
            for (digit in pin) {
                if (interrupted()) return skipped(Result.ABORTED, "interrupted while typing the PIN")
                val center = keypad[digit] ?: return skipped(Result.UNLOCK_FAILED, "the keypad has no button for one PIN digit")
                session.tap(center.first, center.second)
                delay(250)
            }
            stage = Stage.PIN_ENTERED
            // A right PIN leaves the PIN screen; a wrong one keeps it, usually with an error.
            val deadline = System.currentTimeMillis() + PIN_ACCEPT_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (interrupted()) return skipped(Result.ABORTED, "interrupted after typing the PIN")
                delay(500)
                if (exited()) return jagoExited("Bank Jago closed itself after the PIN was typed")
                val dump = session.dump() ?: continue
                lastDump = dump
                if (WRONG_PIN_TEXT.containsMatchIn(dump)) {
                    onPinAnswer(false)
                    return skipped(Result.WRONG_PIN, "Bank Jago rejected the PIN")
                }
                if (JagoScreens.isPastPinScreen(dump)) { stage = Stage.UNLOCKED; onPinAnswer(true); return null }
            }
            screen = lastDump?.let(JagoScreens::signature)
            return skipped(Result.UNLOCK_FAILED, "still on the PIN screen ${PIN_ACCEPT_WAIT_MS / 1000} s after typing it")
        }

        /** Keeps Jago alive until its cache file has stopped changing, so it is never cut off mid-write. */
        private suspend fun settle(): Result {
            val unlockedAt = System.currentTimeMillis()
            var last = cacheStat()
            var lastChangeAt = unlockedAt
            var changed = last != cacheBefore
            var polls = 0
            while (true) {
                if (interrupted()) { note = "interrupted while Bank Jago refreshed"; break }
                delay(SETTLE_POLL_MS)
                polls++
                val now = System.currentTimeMillis()
                if (exited()) {
                    exit = readExit()
                    cacheAfter = cacheStat()
                    note = "Bank Jago closed itself after unlocking"
                    return if (cacheAfter != cacheBefore) Result.SYNCED else Result.JAGO_EXITED
                }
                val current = cacheStat()
                if (current != last) {
                    last = current
                    lastChangeAt = now
                    if (current != cacheBefore) changed = true
                }
                if (changed && now - lastChangeAt >= SETTLE_QUIET_MS) { stage = Stage.SETTLED; break }
                if (!changed && now - unlockedAt >= SETTLE_IDLE_MS) { stage = Stage.SETTLED; break }
                if (now - unlockedAt >= SETTLE_MAX_MS) break
                if (polls % 3 == 0) dismissBiometricPrompt()
            }
            cacheAfter = last
            if (note.isEmpty()) note = if (changed) "cache refreshed" else "cache unchanged"
            return if (changed) Result.SYNCED else Result.QUIET
        }

        /**
         * The fingerprint prompt Bank Jago raises is drawn by System UI on the real screen, not on the
         * hidden display, so it has to be closed from outside: first the system-dialog broadcast, then Back
         * only if the prompt itself holds focus. Stopping Jago is left to the end of the run.
         */
        private suspend fun dismissBiometricPrompt() {
            if (biometricDismissals >= MAX_BIOMETRIC_DISMISSALS) return
            val signal = biometricSignal() ?: return
            biometricDismissals++
            sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; echo done")
            delay(800)
            var closed = biometricSignal() == null
            if (!closed && promptFocused()) {
                sh("input keyevent 4; echo done")
                delay(800)
                closed = biometricSignal() == null
            }
            ArthaLog.i(TAG, if (closed) "closed a fingerprint prompt" else "a fingerprint prompt is still showing",
                mapOf("seenVia" to signal, "attempt" to biometricDismissals.toString()))
        }

        /** "window" or "fingerprint" when a biometric prompt is up for Bank Jago, otherwise null. */
        private suspend fun biometricSignal(): String? {
            val counts = sh(
                "echo \"$(dumpsys window windows 2>/dev/null | grep -c -i biometricprompt) " +
                    "$(dumpsys fingerprint 2>/dev/null | grep -i 'current operation' | grep -c '$PACKAGE')\"",
            )?.firstOrNull()?.trim()?.split(' ')?.mapNotNull(String::toIntOrNull) ?: return null
            return when {
                counts.getOrElse(0) { 0 } > 0 -> "window"
                counts.getOrElse(1) { 0 } > 0 -> "fingerprint"
                else -> null
            }
        }

        private suspend fun promptFocused(): Boolean =
            sh("dumpsys window 2>/dev/null | grep -i mCurrentFocus | grep -c -i biometricprompt")
                ?.firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

        private suspend fun interrupted(): Boolean = shouldAbort() || !isInteractive() || jagoResumed()

        /** True once Bank Jago has been running and its process is gone. */
        private suspend fun exited(): Boolean {
            if (pid() != null) { jagoSeen = true; return false }
            return jagoSeen
        }

        private suspend fun jagoExited(why: String): Result {
            exit = readExit()
            screen = lastDump?.let(JagoScreens::signature)
            return skipped(Result.JAGO_EXITED, why)
        }

        private suspend fun pid(): String? =
            sh("pidof $PACKAGE || echo none")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "none" }

        private suspend fun readExit(): JagoScreens.ExitRecord? =
            JagoScreens.parseExitInfo(sh("dumpsys activity exit-info $PACKAGE 2>/dev/null | head -14").orEmpty())

        /** Modification time and size of Jago's history file: changes whenever Jago writes it. */
        private suspend fun cacheStat(): String? = sh("stat -c %Y:%s $HIVE_FILE 2>/dev/null")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private fun elapsed() = System.currentTimeMillis() - startedAt

        private fun skipped(result: Result, why: String): Result {
            note = why
            return result
        }

        private fun record(result: Result) {
            val fields = linkedMapOf("trigger" to trigger, "result" to result.name, "reached" to (stage?.name ?: "NOT_LAUNCHED"), "ms" to elapsed().toString())
            pinScreenAtMs?.let { fields["pinScreenAtMs"] = it.toString() }
            if (cacheBefore != null || cacheAfter != null) fields["cache"] = "$cacheBefore -> $cacheAfter"
            if (biometricDismissals > 0) fields["fingerprintPrompts"] = biometricDismissals.toString()
            if (dialogTaps > 0) fields["dialogTaps"] = dialogTaps.toString()
            exit?.let { fields["jagoExit"] = it.toString() }
            screen?.let { fields["screen"] = it }
            val ok = result == Result.SYNCED || result == Result.QUIET
            val message = "hidden Bank Jago session ended: ${note.ifEmpty { result.name }}"
            if (ok || result.name.startsWith("SKIPPED")) ArthaLog.i(TAG, message, fields) else ArthaLog.w(TAG, message, fields = fields)
        }
    }

    private suspend fun jagoResumed(): Boolean =
        sh("dumpsys activity activities | awk '/^Display #/ { d = \$2 } /topResumedActivity=/ && /com\\.jago\\.digitalBanking/ && d == \"#0\" { n++ } END { print n + 0 }'")
            ?.firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

    private suspend fun sh(command: String): List<String>? = reader.executeCommandWithOutput(command)

    companion object {
        /** Jago's error under the keypad, in English or Indonesian, e.g. "Incorrect PIN" or "PIN salah". */
        val WRONG_PIN_TEXT = Regex(
            "(?i)\\b(incorrect|wrong|invalid)\\b[^\"]{0,30}\\bpin\\b|\\bpin\\b[^\"]{0,40}\\b(salah|incorrect|wrong|tidak sesuai)\\b",
        )
        private const val TAG = "JagoSync"
        private const val PACKAGE = "com.jago.digitalBanking"
        private const val HIVE_FILE = "/data/data/$PACKAGE/app_flutter/list_transaction_history.hive"
        private const val POLL_MS = 600L
        private const val SETTLE_POLL_MS = 1_000L
        private const val PIN_SCREEN_WAIT_MS = 25_000L
        private const val PIN_ACCEPT_WAIT_MS = 15_000L
        /** After unlocking: done once the file has been quiet this long, or never changed in [SETTLE_IDLE_MS], or at [SETTLE_MAX_MS]. */
        private const val SETTLE_QUIET_MS = 4_000L
        private const val SETTLE_IDLE_MS = 15_000L
        private const val SETTLE_MAX_MS = 45_000L
        /** Longer than the three waits above add up to, so the lock frees itself even if release is missed. */
        private const val SCREEN_LOCK_MAX_MS = 120_000L
        private const val MAX_DIALOG_TAPS = 3
        private const val MAX_BIOMETRIC_DISMISSALS = 4
        private val BOUNDS_REGEX = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

        fun parseDigitCenters(xml: String): Map<Char, Pair<Int, Int>> {
            val result = mutableMapOf<Char, Pair<Int, Int>>()
            runCatching {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val cleanXml = if (xml.startsWith("ok ")) xml.substring(3).trim() else xml.trim()
                val doc = builder.parse(InputSource(StringReader(cleanXml)))
                val nodes = doc.getElementsByTagName("node")
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as? Element ?: continue
                    val desc = element.getAttribute("content-desc")
                    if (desc.length == 1 && desc[0] in '0'..'9') {
                        val bounds = element.getAttribute("bounds")
                        val match = BOUNDS_REGEX.matchEntire(bounds)
                        if (match != null) {
                            val (x1, y1, x2, y2) = match.destructured
                            val cx = (x1.toInt() + x2.toInt()) / 2
                            val cy = (y1.toInt() + y2.toInt()) / 2
                            result[desc[0]] = Pair(cx, cy)
                        }
                    }
                }
            }
            return result
        }
    }
}
