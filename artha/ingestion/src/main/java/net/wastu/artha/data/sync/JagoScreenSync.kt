package net.wastu.artha.data.sync

import android.os.Build
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.data.ingestion.RootFileReader
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Automates Bank Jago session refresh on a headless virtual display by unlocking with the user's PIN,
 * allowing Jago to sync its local transaction cache in the background.
 */
class JagoScreenSync(
    private val reader: RootFileReader,
    private val isInteractive: () -> Boolean,
    private val shouldAbort: () -> Boolean,
    private val getPin: () -> String?,
) {
    enum class Result {
        SYNCED, QUIET, ABORTED, SKIPPED_SCREEN_OFF, SKIPPED_JAGO_IN_USE,
        ROOT_UNAVAILABLE, SKIPPED_NO_PIN, SKIPPED_NOT_INSTALLED, UNLOCK_FAILED, WRONG_PIN
    }

    suspend fun run(): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 33) return@withContext skipped(Result.ROOT_UNAVAILABLE, "screen walk needs Android 13")
        if (shouldAbort() || !isInteractive()) return@withContext skipped(Result.SKIPPED_SCREEN_OFF, "display is off")
        val pin = getPin()?.trim()
        if (pin.isNullOrEmpty()) return@withContext skipped(Result.SKIPPED_NO_PIN, "no PIN configured")

        val installed = sh("pm path com.jago.digitalBanking 2>/dev/null")
            ?: return@withContext skipped(Result.ROOT_UNAVAILABLE, "no root shell")
        if (installed.none { it.startsWith("package:") }) return@withContext skipped(Result.SKIPPED_NOT_INSTALLED, "jago not installed")
        if (jagoResumed()) return@withContext skipped(Result.SKIPPED_JAGO_IN_USE, "jago is on screen")

        val hiveFile = "/data/data/com.jago.digitalBanking/app_flutter/list_transaction_history.hive"
        val mtimeBefore = fileMtime(hiveFile)

        sh("am force-stop com.jago.digitalBanking")
        val session = RootScreenSession.start(reader.context.applicationInfo.sourceDir)
            ?: return@withContext skipped(Result.ROOT_UNAVAILABLE, "screen helper did not start")

        try {
            if (!session.open()) return@withContext skipped(Result.ABORTED, "virtual display unavailable")
            if (!session.start("com.jago.digitalBanking/.MainActivity")) return@withContext skipped(Result.ABORTED, "launch jago failed")

            // Wait for "Enter your PIN"
            var digitCenters: Map<Char, Pair<Int, Int>>? = null
            for (attempt in 0 until 12) {
                if (shouldAbort() || !isInteractive() || jagoResumed()) return@withContext Result.ABORTED
                delay(600)
                val dump = session.dump() ?: continue
                if (dump.contains("Enter your PIN")) {
                    digitCenters = parseDigitCenters(dump)
                    if (digitCenters.isNotEmpty()) break
                }
            }

            if (digitCenters.isNullOrEmpty()) {
                return@withContext skipped(Result.UNLOCK_FAILED, "PIN screen not found or digits unreadable")
            }

            // Tap PIN digits
            for (ch in pin) {
                if (shouldAbort() || !isInteractive() || jagoResumed()) return@withContext Result.ABORTED
                val center = digitCenters[ch] ?: return@withContext skipped(Result.UNLOCK_FAILED, "missing button for digit $ch")
                session.tap(center.first, center.second)
                delay(250)
            }

            // A right PIN leaves the PIN screen; a wrong one keeps it, usually with an error.
            var unlocked = false
            for (attempt in 0 until 16) {
                if (shouldAbort() || !isInteractive() || jagoResumed()) return@withContext Result.ABORTED
                delay(500)
                val dump = session.dump() ?: continue
                if (WRONG_PIN_TEXT.containsMatchIn(dump)) return@withContext skipped(Result.WRONG_PIN, "jago rejected the PIN")
                if (!dump.contains("Enter your PIN")) {
                    unlocked = true
                    break
                }
            }
            if (!unlocked) return@withContext skipped(Result.WRONG_PIN, "still on the PIN screen after typing it")

            // Wait for cache rewrite
            var mtimeAfter = mtimeBefore
            for (attempt in 0 until 12) {
                if (shouldAbort() || !isInteractive() || jagoResumed()) break
                delay(600)
                mtimeAfter = fileMtime(hiveFile)
                if (mtimeAfter != null && mtimeAfter != mtimeBefore) break
            }

            if (mtimeAfter != null && mtimeAfter != mtimeBefore) {
                log("Jago cache updated: $mtimeBefore -> $mtimeAfter")
                Result.SYNCED
            } else {
                Result.QUIET
            }
        } finally {
            withContext(NonCancellable) {
                session.close()
                if (!jagoResumed()) sh("am force-stop com.jago.digitalBanking")
            }
        }
    }

    private suspend fun jagoResumed(): Boolean =
        sh("dumpsys activity activities | awk '/^Display #/ { d = \$2 } /topResumedActivity=/ && /com\\.jago\\.digitalBanking/ && d == \"#0\" { n++ } END { print n + 0 }'")
            ?.firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

    private suspend fun fileMtime(path: String): Long? =
        sh("stat -c %Y $path 2>/dev/null")?.firstOrNull()?.trim()?.toLongOrNull()

    private suspend fun sh(command: String): List<String>? = reader.executeCommandWithOutput(command)

    private fun log(message: String) = android.util.Log.i(TAG, message)

    private fun skipped(result: Result, why: String): Result = log("$result: $why").let { result }

    companion object {
        /** Jago's error under the keypad, in English or Indonesian, e.g. "Incorrect PIN" or "PIN salah". */
        val WRONG_PIN_TEXT = Regex(
            "(?i)\\b(incorrect|wrong|invalid)\\b[^\"]{0,30}\\bpin\\b|\\bpin\\b[^\"]{0,40}\\b(salah|incorrect|wrong|tidak sesuai)\\b",
        )
        private const val TAG = "ArthaJagoScreen"
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
