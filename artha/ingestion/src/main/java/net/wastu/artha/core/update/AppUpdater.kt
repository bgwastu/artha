package net.wastu.artha.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import net.wastu.artha.core.log.ArthaLog
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where this app's update stands, for the screens that show it. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Downloading(val versionName: String, val progress: Float) : UpdateState
    /** Downloaded and verified; installs when the app allows it. */
    data class Ready(val versionName: String) : UpdateState
    data class Installing(val versionName: String) : UpdateState
    /** Android asked the user to confirm the install. */
    data class WaitingForConfirmation(val versionName: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Hands a verified APK to Android. The app is usually replaced, and killed, before this returns. */
fun interface UpdateInstaller {
    suspend fun install(apk: File)
}

/**
 * Keeps one app up to date from Artha's GitHub releases. A release is only installed when
 * it is newer, matches the size and SHA-256 in `update.json`, is the same package, and is
 * signed with the same certificate as the installed app, so a tampered or mixed-up file
 * never reaches the installer. Calls run one at a time, and a verified download is kept,
 * so a later attempt installs without downloading again.
 */
class AppUpdater(
    private val context: Context,
    private val installer: UpdateInstaller,
    private val manifestUrl: String = UpdateRelease.MANIFEST_URL,
) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val dir = File(context.noBackupFilesDir, "updates")
    private val lock = Mutex()

    private val installed: PackageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlags())
    val installedVersionCode: Long = PackageInfoCompat.getLongVersionCode(installed)
    val installedVersionName: String = installed.versionName.orEmpty()

    private val _state = MutableStateFlow(savedState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _autoUpdate = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    fun setAutoUpdate(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO, enabled) }
        _autoUpdate.value = enabled
    }

    val lastCheckedAt: Long? get() = prefs.getLong(KEY_CHECKED, 0L).takeIf { it > 0L }

    /** True when the last check is older than [maxAgeMillis], so a scheduled check is due. */
    fun isCheckDue(maxAgeMillis: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - (lastCheckedAt ?: 0L) >= maxAgeMillis || _state.value is UpdateState.Ready

    /**
     * Looks for a newer release and downloads and verifies it; installs it too when
     * [install] is true. Returns the resulting state; failures are reported there, not thrown.
     */
    suspend fun update(install: Boolean): UpdateState = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                _state.value = UpdateState.Checking
                val release = UpdateRelease.parse(fetchText(manifestUrl))
                val asset = release.assets[context.packageName]
                val now = System.currentTimeMillis()
                prefs.edit { putLong(KEY_CHECKED, now); remove(KEY_ERROR) }
                if (asset == null || release.versionCode <= installedVersionCode) {
                    clearDownloads()
                    _state.value = UpdateState.UpToDate(now)
                } else {
                    val apk = download(release, asset)
                    prefs.edit { putString(KEY_READY, release.versionName); putLong(KEY_READY_CODE, release.versionCode) }
                    _state.value = UpdateState.Ready(release.versionName)
                    if (install) {
                        ArthaLog.i(TAG, "Installing ${release.versionName}")
                        _state.value = UpdateState.Installing(release.versionName)
                        installer.install(apk)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message?.takeIf { it.isNotBlank() } ?: "Update failed", e)
            }
            _state.value
        }
    }

    /** Reports what Android said about an install this updater started. */
    fun onInstallResult(state: UpdateState) {
        if (state is UpdateState.Failed) fail(state.message) else _state.value = state
    }

    private fun fail(message: String, error: Exception? = null) {
        ArthaLog.w(TAG, "Update failed: $message", error)
        prefs.edit { putString(KEY_ERROR, message) }
        _state.value = UpdateState.Failed(message)
    }

    private fun savedState(): UpdateState {
        val readyCode = prefs.getLong(KEY_READY_CODE, 0L)
        if (readyCode > installedVersionCode) return UpdateState.Ready(prefs.getString(KEY_READY, null).orEmpty())
        if (readyCode > 0L) clearDownloads()
        prefs.getString(KEY_ERROR, null)?.let { return UpdateState.Failed(it) }
        return lastCheckedAt?.let { UpdateState.UpToDate(it) } ?: UpdateState.Idle
    }

    private fun clearDownloads() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit { remove(KEY_READY); remove(KEY_READY_CODE) }
    }

    /** The verified APK for [release], downloading it unless an earlier download already checks out. */
    private fun download(release: UpdateRelease, asset: UpdateAsset): File {
        val target = File(dir, "${context.packageName}-${release.versionCode}.apk")
        if (target.length() == asset.size && sha256(target) == asset.sha256) {
            verifyPackage(target, release)
            return target
        }
        clearDownloads()
        dir.mkdirs()
        val partial = File(dir, "partial-${target.name}")
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        ArthaLog.i(TAG, "Downloading ${release.versionName}")
        val connection = open(asset.url)
        try {
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0f
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        if (received > asset.size) throw IOException("Download is larger than the release says")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val progress = received.toFloat() / asset.size
                        if (progress - lastReport >= 0.02f) {
                            lastReport = progress
                            _state.value = UpdateState.Downloading(release.versionName, progress)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (received != asset.size) throw IOException("Download stopped early")
        if (digest.digest().toHex() != asset.sha256) throw IOException("Download does not match its checksum")
        verifyPackage(partial, release)
        if (!partial.renameTo(target)) throw IOException("Could not keep the download")
        return target
    }

    /** Refuses an APK that is another app, not the release it claims, or signed by someone else. */
    private fun verifyPackage(apk: File, release: UpdateRelease) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, signingFlags())
            ?: throw IOException("Downloaded file is not an app")
        if (info.packageName != context.packageName) throw IOException("Downloaded app is ${info.packageName}")
        if (PackageInfoCompat.getLongVersionCode(info) != release.versionCode) throw IOException("Downloaded app is not version ${release.versionName}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android refuses a different signer anyway; checking first gives a clear message and keeps the bad file out.
            if (signers(info).isEmpty() || signers(info) != signers(installed)) {
                throw IOException("Downloaded app is not signed by Artha")
            }
        }
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0

    private fun signers(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        return signing.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_MANIFEST_BYTES) }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** Follows GitHub's redirects to its download host itself, so every hop stays on HTTPS. */
    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            if (current.protocol != "https") throw IOException("Refusing a non-HTTPS download")
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Artha-Updater")
            }
            when (val code = connection.responseCode) {
                in 300..399 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    connection.disconnect()
                    current = URL(current, location)
                }
                200 -> return connection
                else -> {
                    connection.disconnect()
                    throw IOException(if (code == 404) "No release published yet" else "GitHub answered $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private companion object {
        const val TAG = "ArthaUpdate"
        const val KEY_AUTO = "auto_update"
        const val KEY_CHECKED = "checked_at"
        const val KEY_ERROR = "error"
        const val KEY_READY = "ready_version"
        const val KEY_READY_CODE = "ready_code"
        const val TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_MANIFEST_BYTES = 64 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** Reads at most [limit] bytes; a manifest is tiny, so anything bigger is not one. */
        fun InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) throw IOException("Update manifest is too large")
            }
            return out.toByteArray()
        }
    }
}
