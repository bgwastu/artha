package net.wastu.artha.core.log

import java.io.File
import org.json.JSONObject

/** One recorded event. [level] is D, I, W or E; [fields] are short facts such as `result=SYNCED`. */
data class LogEntry(
    val timeMillis: Long,
    val level: Char,
    val tag: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("t", timeMillis)
        put("l", level.toString())
        put("tag", tag)
        put("m", message)
        if (fields.isNotEmpty()) put("f", JSONObject(fields))
    }.toString()

    companion object {
        fun parse(line: String): LogEntry? = runCatching {
            val json = JSONObject(line)
            val fields = json.optJSONObject("f")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } }.orEmpty()
            LogEntry(json.getLong("t"), json.getString("l").first(), json.getString("tag"), json.getString("m"), fields)
        }.getOrNull()
    }
}

/**
 * A size-bounded JSON-lines log: when [file] would pass [maxBytes] it becomes the single backup
 * and a fresh file starts, so the log never takes more than about twice [maxBytes].
 */
class LogFile(private val file: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val backup = File(file.path + ".1")
    private val lock = Any()

    fun append(entry: LogEntry) {
        val line = (entry.toJsonLine() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.length() + line.size > maxBytes) {
                    backup.delete()
                    file.renameTo(backup)
                }
                file.appendBytes(line)
            }
        }
    }

    /** The newest [limit] entries, oldest first. */
    fun read(limit: Int = Int.MAX_VALUE): List<LogEntry> = synchronized(lock) {
        (linesOf(backup) + linesOf(file)).mapNotNull(LogEntry::parse).takeLast(limit)
    }

    fun clear() = synchronized(lock) {
        backup.delete()
        file.delete()
        Unit
    }

    private fun linesOf(source: File): List<String> =
        runCatching { if (source.isFile) source.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList() }
            .getOrDefault(emptyList())

    companion object { const val DEFAULT_MAX_BYTES = 256L * 1024 }
}
