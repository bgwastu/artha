package net.wastu.artha.bridge.daemon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
) {
    fun format(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        return "[$time] $level/$tag: $message"
    }
}

object BridgeLog {
    private const val MAX_LOGS = 100
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        android.util.Log.w(tag, message, tr)
        val text = if (tr != null) "$message (${tr.message ?: tr::class.java.simpleName})" else message
        append("W", tag, text)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        android.util.Log.e(tag, message, tr)
        val text = if (tr != null) "$message (${tr.message ?: tr::class.java.simpleName})" else message
        append("E", tag, text)
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        _logs.value = (_logs.value + entry).takeLast(MAX_LOGS)
    }
}
