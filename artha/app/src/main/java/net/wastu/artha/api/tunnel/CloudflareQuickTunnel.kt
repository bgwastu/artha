package net.wastu.artha.api.tunnel

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

sealed interface TunnelState {
    data object Inactive : TunnelState
    data class Downloading(val progress: Float) : TunnelState
    data object Starting : TunnelState
    data class Active(val url: String) : TunnelState
    data class Failed(val error: String) : TunnelState
}

class CloudflareQuickTunnel(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        const val DOWNLOAD_URL = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        private val URL_PATTERN = Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
    }

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Inactive)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var tunnelJob: Job? = null
    private var process: Process? = null

    fun binaryFile(): File {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        return File(binDir, "cloudflared")
    }

    fun isBinaryInstalled(): Boolean {
        return findExistingBinary() != null
    }

    private fun findExistingBinary(): File? {
        val commonPaths = listOf(
            "/data/local/tmp/cloudflared",
            "/data/data/com.termux/files/usr/bin/cloudflared",
            "/system/bin/cloudflared",
        )
        for (p in commonPaths) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return f
        }

        val internal = binaryFile()
        if (internal.exists() && internal.canExecute()) return internal
        if (internal.exists()) return internal
        return null
    }

    suspend fun downloadBinary(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = binaryFile()
        val temp = File(target.parentFile, "cloudflared.download.tmp")
        if (temp.exists()) temp.delete()

        var conn: HttpURLConnection? = null
        try {
            var currentUrl = URL(DOWNLOAD_URL)
            var redirects = 0
            while (redirects < 5) {
                conn = currentUrl.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "Artha-Tunnel-Downloader/1.0")

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307 || status == 308) {
                    val location = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    currentUrl = URL(location)
                    redirects++
                } else {
                    break
                }
            }

            val totalBytes = conn!!.contentLengthLong
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            target.setExecutable(true, false)
            target.setReadable(true, false)

            runCatching {
                Shell.cmd("chmod 755 ${target.absolutePath}").exec()
            }

            target
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }
    private fun rootShell(): Shell {
        return runCatching {
            Shell.Builder.create().setContext(context).setTimeout(15).build("su")
        }.getOrNull() ?: Shell.getShell()
    }

    private fun ensureDnsAndCerts() {
        val check = File("/etc/resolv.conf")
        if (check.exists() && check.length() > 0) return

        val script = """
            mkdir -p /data/local/tmp/artha_etc
            mount -t tmpfs tmpfs /data/local/tmp/artha_etc 2>/dev/null || true
            cp -a /system/etc/* /data/local/tmp/artha_etc/ 2>/dev/null || true
            echo -e "nameserver 1.1.1.1\nnameserver 8.8.8.8" > /data/local/tmp/artha_etc/resolv.conf
            mkdir -p /data/local/tmp/artha_etc/ssl/certs
            cat /system/etc/security/cacerts/* > /data/local/tmp/artha_etc/ssl/certs/ca-certificates.crt 2>/dev/null || true
            cp /data/local/tmp/artha_etc/ssl/certs/ca-certificates.crt /data/local/tmp/artha_etc/ssl/cert.pem 2>/dev/null || true
            mount -o bind /data/local/tmp/artha_etc /system/etc 2>/dev/null || true
        """.trimIndent()

        runCatching { rootShell().newJob().add(script).exec() }
    }

    fun start(localPort: Int) {
        if (_state.value is TunnelState.Active || _state.value is TunnelState.Starting) return

        tunnelJob?.cancel()
        tunnelJob = scope.launch(Dispatchers.IO) {
            try {
                var binary = findExistingBinary()
                if (binary == null || !binary.exists()) {
                    _state.value = TunnelState.Downloading(0f)
                    binary = downloadBinary { progress ->
                        _state.value = TunnelState.Downloading(progress)
                    }
                }

                _state.value = TunnelState.Starting

                binary.setExecutable(true, false)
                runCatching { Shell.cmd("chmod 755 ${binary.absolutePath}").exec() }

                val localTmp = File("/data/local/tmp/cloudflared")
                if (!localTmp.exists() && binary.exists()) {
                    runCatching {
                        binary.copyTo(localTmp, overwrite = true)
                        localTmp.setExecutable(true, false)
                        Shell.cmd("chmod 777 ${localTmp.absolutePath}").exec()
                    }
                }
                val execBinary = if (localTmp.exists() && localTmp.canExecute()) localTmp else binary

                ensureDnsAndCerts()

                val logFile = File(context.filesDir, "cloudflared.log")
                if (logFile.exists()) logFile.delete()
                logFile.createNewFile()

                val launchCmd = "${execBinary.absolutePath} tunnel --no-autoupdate --url http://127.0.0.1:$localPort > '${logFile.absolutePath}' 2>&1 & echo \$!"
                val execResult = rootShell().newJob().add(launchCmd).exec()

                if (!execResult.isSuccess || execResult.out.isEmpty()) {
                    // Fallback to direct process if root shell is not available
                    val pb = ProcessBuilder(
                        binary.absolutePath,
                        "tunnel",
                        "--no-autoupdate",
                        "--url",
                        "http://127.0.0.1:$localPort",
                    )
                    pb.redirectErrorStream(true)
                    process = pb.start()
                }

                var activeUrl: String? = null
                val deadline = System.currentTimeMillis() + 60000L
                var filePointer = 0L

                while (System.currentTimeMillis() < deadline && activeUrl == null) {
                    if (logFile.exists() && logFile.length() > filePointer) {
                        val raf = java.io.RandomAccessFile(logFile, "r")
                        try {
                            raf.seek(filePointer)
                            var line = raf.readLine()
                            while (line != null) {
                                android.util.Log.d("ArthaTunnel", line)
                                val matcher = URL_PATTERN.matcher(line)
                                if (matcher.find()) {
                                    activeUrl = matcher.group()
                                    _state.value = TunnelState.Active(activeUrl)
                                    break
                                }
                                line = raf.readLine()
                            }
                            filePointer = raf.filePointer
                        } finally {
                            raf.close()
                        }
                    }

                    if (activeUrl != null) break
                    kotlinx.coroutines.delay(400)
                }

                if (activeUrl == null) {
                    val logs = logFile.readText().takeLast(400)
                    _state.value = TunnelState.Failed("Tunnel failed to start: $logs")
                }
            } catch (e: Exception) {
                _state.value = TunnelState.Failed(e.message ?: "Failed to start Cloudflare tunnel")
            } finally {
                process = null
            }
        }
    }

    fun stop() {
        tunnelJob?.cancel()
        tunnelJob = null

        val p = process
        if (p != null) {
            p.destroy()
            runCatching {
                if (p.isAlive) p.destroyForcibly()
            }
            process = null
        }

        // Also ensure any lingering cloudflared processes are terminated via root
        runCatching {
            rootShell().newJob().add("killall -9 cloudflared").exec()
        }

        _state.value = TunnelState.Inactive
    }
}
