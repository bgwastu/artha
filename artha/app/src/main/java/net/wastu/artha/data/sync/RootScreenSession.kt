package net.wastu.artha.data.sync

import java.io.BufferedWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives [net.wastu.artha.walk.GojekScreenHelper] in a root `app_process` started from Artha's
 * own APK. One request line, one reply line; a reply that does not arrive in time ends the session.
 */
internal class RootScreenSession private constructor(private val process: Process) : GojekScreenWalk.Screen, AutoCloseable {
    private val input: BufferedWriter = process.outputStream.bufferedWriter()
    private val replies = LinkedBlockingQueue<String>()

    init {
        Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { replies.put(it) } }
            replies.put(CLOSED)
        }, "artha-screen-replies").apply { isDaemon = true }.start()
    }

    var displayId: Int = -1
        private set

    suspend fun open(): Boolean {
        val reply = call("open", 15_000) ?: return false
        displayId = reply.trim().toIntOrNull() ?: return false
        return true
    }

    suspend fun launch(uri: String): Boolean = call("launch $uri", 20_000) != null

    override suspend fun dump(): String? = call("dump", 10_000)
    override suspend fun tap(x: Int, y: Int) { call("tap $x $y", 10_000) }
    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, millis: Int) { call("swipe $fromX $fromY $toX $toY $millis", 10_000) }
    override suspend fun back() { call("back", 10_000) }

    /** Returns the text after `ok`, or null on `err`, timeout, or a dead helper. */
    private suspend fun call(command: String, timeoutMillis: Long): String? = withContext(Dispatchers.IO) {
        if (!process.isAlive) return@withContext null
        runCatching {
            input.write(command)
            input.newLine()
            input.flush()
        }.onFailure { return@withContext null }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val left = deadline - System.nanoTime()
            val line = replies.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (line == null || line == CLOSED) {
                if (line == CLOSED) replies.put(CLOSED)
                android.util.Log.w(TAG, "$command: no reply")
                process.destroy()
                return@withContext null
            }
            when {
                line == "ok" -> return@withContext ""
                line.startsWith("ok ") -> return@withContext line.substring(3)
                line.startsWith("err") -> {
                    android.util.Log.w(TAG, "$command: $line")
                    return@withContext null
                }
                // Anything else is runtime noise on stdout, not a reply.
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    override fun close() {
        runCatching {
            input.write("quit")
            input.newLine()
            input.flush()
        }
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
    }

    companion object {
        private const val TAG = "ArthaScreen"
        private const val CLOSED = "\u0000closed"
        private const val HELPER = "net.wastu.artha.walk.GojekScreenHelper"

        fun start(apkPath: String): RootScreenSession? = runCatching {
            require(apkPath.matches(Regex("/[A-Za-z0-9._~=+/-]+\\.apk"))) { "unexpected APK path" }
            val command = "CLASSPATH=$apkPath exec app_process /system/bin $HELPER 2>/dev/null"
            RootScreenSession(ProcessBuilder("su", "-M", "-c", command).start())
        }.onFailure { android.util.Log.w(TAG, "helper did not start", it) }.getOrNull()
    }
}
