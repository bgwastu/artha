package net.wastu.artha.core.log

import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Artha's one place for diagnostics. Every line goes to logcat; everything from INFO up also goes to a
 * small rotating file under the app's private storage, which the Activity log page reads, so a problem
 * that happened while nobody was watching can still be explained afterwards.
 *
 * Never pass a PIN, token, cookie, amount or merchant name: the file is plain text on the device.
 */
object ArthaLog {
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "artha-log").apply { isDaemon = true } }
    @Volatile private var file: LogFile? = null

    /** Starts the file log. Call once from [android.app.Application.onCreate]; earlier lines reach logcat only. */
    fun install(directory: File) {
        val log = LogFile(File(directory, "activity.log"))
        file = log
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The process is about to die, so this write is synchronous.
            log.append(LogEntry(System.currentTimeMillis(), 'E', "Crash", "${thread.name}: ${describe(error)}"))
            previous?.uncaughtException(thread, error)
        }
    }

    fun d(tag: String, message: String, fields: Map<String, String> = emptyMap()) = emit('D', tag, message, null, fields)
    fun i(tag: String, message: String, fields: Map<String, String> = emptyMap()) = emit('I', tag, message, null, fields)
    fun w(tag: String, message: String, error: Throwable? = null, fields: Map<String, String> = emptyMap()) = emit('W', tag, message, error, fields)
    fun e(tag: String, message: String, error: Throwable? = null, fields: Map<String, String> = emptyMap()) = emit('E', tag, message, error, fields)

    fun recent(limit: Int = 400): List<LogEntry> = file?.read(limit).orEmpty()

    fun clear() { file?.clear() }

    private fun emit(level: Char, tag: String, message: String, error: Throwable?, fields: Map<String, String>) {
        val text = if (error == null) message else "$message (${describe(error)})"
        mirror(level, tag, text + render(fields), error)
        if (level == 'D') return
        val entry = LogEntry(System.currentTimeMillis(), level, tag, text.take(MAX_MESSAGE), fields)
        val sink = file ?: return
        writer.execute { sink.append(entry) }
    }

    // The android.util.Log stubs throw in plain JVM tests, and logging must never break a caller.
    private fun mirror(level: Char, tag: String, text: String, error: Throwable?) {
        runCatching {
            when (level) {
                'D' -> Log.d(tag, text)
                'I' -> Log.i(tag, text)
                'W' -> if (error != null) Log.w(tag, text, error) else Log.w(tag, text)
                else -> if (error != null) Log.e(tag, text, error) else Log.e(tag, text)
            }
        }
    }

    private fun describe(error: Throwable) = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(200)}"

    private fun render(fields: Map<String, String>) =
        if (fields.isEmpty()) "" else fields.entries.joinToString(prefix = " [", postfix = "]", separator = ", ") { "${it.key}=${it.value}" }

    private val timeFormat = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault())

    /** One readable line: `Nov 10 11:03:57 I JagoSync: message [k=v]`. */
    fun format(entry: LogEntry): String =
        "${timeFormat.format(Instant.ofEpochMilli(entry.timeMillis))} ${entry.level} ${entry.tag}: ${entry.message}${render(entry.fields)}"

    private const val MAX_MESSAGE = 2_000
}
