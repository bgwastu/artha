package net.wastu.artha.api.tunnel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

sealed interface TunnelState {
    data object Inactive : TunnelState
    data object Starting : TunnelState
    data class Active(val url: String) : TunnelState
    data class Failed(val error: String) : TunnelState
}

/**
 * Runs the bundled cloudflared binary (packaged as libcloudflared.so so Android 10+ allows exec).
 *
 * Go's resolver cannot work on Android (no /etc/resolv.conf), so everything that needs DNS is done
 * here: the quick tunnel is registered over HTTPS and the edge IPs are resolved and passed explicitly.
 */
class CloudflareQuickTunnel(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "ArthaTunnel"
        const val REGISTER_URL = "https://api.trycloudflare.com/tunnel"
        val EDGE_HOSTS = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
        const val EDGE_PORT = 7844
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val CERT_DIR = "/system/etc/security/cacerts"
    }

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Inactive)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var tunnelJob: Job? = null
    @Volatile private var process: Process? = null

    fun start(localPort: Int) {
        if (_state.value is TunnelState.Active || _state.value is TunnelState.Starting) return
        _state.value = TunnelState.Starting

        tunnelJob?.cancel()
        tunnelJob = scope.launch(Dispatchers.IO) {
            try {
                run(localPort)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Tunnel failed", e)
                destroyProcess()
                _state.value = TunnelState.Failed(e.message ?: "Could not start the share link")
            }
        }
    }

    private suspend fun run(localPort: Int) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!binary.exists()) error("Sharing is not supported on this device (arm64 only)")

        val registration = register()
        val edges = resolveEdges()

        val dir = File(context.cacheDir, "tunnel").apply { mkdirs() }
        val credentials = File(dir, "credentials.json").apply {
            writeText(
                JSONObject()
                    .put("AccountTag", registration.accountTag)
                    .put("TunnelSecret", registration.secret)
                    .put("TunnelID", registration.id)
                    .toString(),
            )
        }
        val config = File(dir, "config.yml").apply {
            writeText(
                """
                tunnel: ${registration.id}
                credentials-file: ${credentials.absolutePath}
                protocol: http2
                ingress:
                  - service: http://127.0.0.1:$localPort
                """.trimIndent(),
            )
        }

        val command = mutableListOf(
            binary.absolutePath, "tunnel",
            "--config", config.absolutePath,
            "--edge-ip-version", "4",
            "--no-autoupdate",
            "--metrics", "127.0.0.1:0",
        )
        edges.forEach { command += listOf("--edge", it) }
        command += listOf("run", registration.id)

        val pb = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
        pb.environment()["HOME"] = dir.absolutePath
        caBundle(dir)?.let { pb.environment()["SSL_CERT_FILE"] = it.absolutePath }
        val proc = pb.start()
        process = proc

        val tail = ArrayDeque<String>()
        val connected = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reader = scope.launch(Dispatchers.IO) {
            proc.inputStream.bufferedReader().forEachLine { line ->
                android.util.Log.d(TAG, line)
                synchronized(tail) {
                    tail.addLast(line)
                    if (tail.size > 6) tail.removeFirst()
                }
                if ("Registered tunnel connection" in line) connected.complete(Unit)
            }
            // Output ended: the process exited.
            if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("cloudflared exited"))
            else if (_state.value is TunnelState.Active) _state.value = TunnelState.Failed("Connection lost")
        }

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { connected.await() }.isSuccess
        } ?: false
        if (!ok) {
            val logs = synchronized(tail) { tail.joinToString("\n") }
            reader.cancel()
            destroyProcess()
            error("Could not reach Cloudflare. ${logs.takeLast(300)}".trim())
        }
        _state.value = TunnelState.Active("https://${registration.hostname}")
    }

    private class Registration(val id: String, val hostname: String, val accountTag: String, val secret: String)

    private fun register(): Registration {
        val conn = URL(REGISTER_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Artha")
            conn.doOutput = true
            conn.outputStream.use { }
            if (conn.responseCode !in 200..299) error("Cloudflare refused the request (${conn.responseCode})")
            val result = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("result")
            return Registration(
                id = result.getString("id"),
                hostname = result.getString("hostname"),
                accountTag = result.getString("account_tag"),
                secret = result.getString("secret"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveEdges(): List<String> {
        val ips = EDGE_HOSTS.flatMap { host ->
            runCatching { InetAddress.getAllByName(host).filterIsInstance<Inet4Address>() }.getOrDefault(emptyList())
        }.mapNotNull { it.hostAddress }.distinct()
        if (ips.isEmpty()) error("Could not resolve Cloudflare servers. Check your connection.")
        return ips.map { "$it:$EDGE_PORT" }
    }

    /** Go finds no CA store on Android, so hand it the system roots as one PEM bundle. */
    private fun caBundle(dir: File): File? {
        val bundle = File(dir, "ca-bundle.pem")
        if (bundle.exists() && bundle.length() > 0) return bundle
        val certs = File(CERT_DIR).listFiles()?.takeIf { it.isNotEmpty() } ?: return null
        bundle.outputStream().use { out -> certs.forEach { runCatching { out.write(it.readBytes()) } } }
        return bundle.takeIf { it.length() > 0 }
    }

    private fun destroyProcess() {
        process?.let {
            it.destroy()
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
    }

    fun stop() {
        tunnelJob?.cancel()
        tunnelJob = null
        destroyProcess()
        _state.value = TunnelState.Inactive
    }
}
