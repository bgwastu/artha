package net.wastu.artha.bridge.daemon

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.edit
import com.topjohnwu.superuser.Shell
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.wastu.artha.bridge.crypto.BridgeCrypto
import net.wastu.artha.bridge.crypto.BridgeSettings
import org.json.JSONObject
import net.wastu.artha.bridge.importing.BridgeImporter
import net.wastu.artha.core.update.AppUpdater
import net.wastu.artha.data.ingestion.RootFileReader
import net.wastu.artha.data.sync.AppSessionWakeSync
import net.wastu.artha.data.sync.JagoScreenSync

/** An Artha install that has synced with this bridge. */
data class ConnectedDevice(
    val id: String,
    val name: String,
    val lastSeenMillis: Long,
    val firstSeenMillis: Long = lastSeenMillis,
)

/**
 * The result of trying a Jago PIN: [fingerprint] names the PIN without revealing it,
 * [result] is one of [PinCheck.OK], [PinCheck.WRONG] or [PinCheck.UNVERIFIED].
 */
data class PinCheck(val fingerprint: String, val result: String, val detail: String, val checkedAt: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("fp", fingerprint).put("result", result).put("detail", detail).put("at", checkedAt)

    companion object {
        const val OK = "ok"
        const val WRONG = "wrong"
        const val UNVERIFIED = "unverified"

        fun fromJson(json: JSONObject) = PinCheck(
            json.getString("fp"), json.getString("result"), json.optString("detail"), json.optLong("at"),
        )

        /** Same on both apps: a short hash, so status messages never carry the PIN. */
        fun fingerprint(pin: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("artha-jago-pin:${pin.trim()}".toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
    }
}

class BridgePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_daemon_prefs", Context.MODE_PRIVATE)

    private val _paired = MutableStateFlow(hasPairing())
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private val _devices = MutableStateFlow(readDevices())
    val devices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    fun hasPairing(): Boolean = prefs.getString("secret_key", null) != null

    fun getSecretKey(): ByteArray? {
        val b64 = prefs.getString("secret_key", null) ?: return null
        return runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
    }

    fun getChannelId(): String? = getSecretKey()?.let { BridgeCrypto.deriveChannelId(it) }

    fun setPairing(secretKey: ByteArray) {
        val b64 = Base64.getEncoder().encodeToString(secretKey)
        prefs.edit { putString("secret_key", b64) }
        _paired.value = true
    }

    fun clearPairing() {
        prefs.edit { remove("secret_key"); remove("last_sync_ms") }
        _paired.value = false
    }

    fun getJagoPin(): String? = prefs.getString("jago_pin", null)

    /** Saves [pin]; true when it differs from the stored one, so it needs checking again. */
    fun setJagoPin(pin: String?): Boolean {
        val next = pin?.trim()?.takeIf { it.isNotEmpty() }
        if (next == getJagoPin()) return false
        prefs.edit {
            if (next == null) remove("jago_pin") else putString("jago_pin", next)
            remove("jago_pin_check")
        }
        return true
    }

    /** What happened the last time the stored PIN was tried in Bank Jago. */
    fun getPinCheck(): PinCheck? = runCatching {
        PinCheck.fromJson(JSONObject(prefs.getString("jago_pin_check", null) ?: return null))
    }.getOrNull()

    fun setPinCheck(check: PinCheck) {
        prefs.edit { putString("jago_pin_check", check.toJson().toString()) }
    }

    /** Remembers [id] as connected, keeping the newest [seenAt] and name. */
    @Synchronized
    fun recordDevice(id: String, name: String, seenAt: Long = System.currentTimeMillis()) {
        if (isRemoved(id)) return
        val current = _devices.value.associateBy { it.id }.toMutableMap()
        val previous = current[id]
        if (previous != null && previous.lastSeenMillis >= seenAt && previous.name == name) return
        current[id] = ConnectedDevice(
            id = id,
            name = name,
            lastSeenMillis = maxOf(seenAt, previous?.lastSeenMillis ?: 0L),
            firstSeenMillis = previous?.firstSeenMillis ?: seenAt,
        )
        saveDevices(current.values)
    }

    fun isRemoved(id: String): Boolean = prefs.getStringSet("removed_devices", emptySet())!!.contains(id)

    /**
     * Forgets [id] and ignores its requests from now on. Every device shares the pairing
     * key, so when the last one goes the key is replaced: the removed phone can no longer
     * read the relays and this phone shows a fresh pairing QR.
     */
    @Synchronized
    fun removeDevice(id: String) {
        val remaining = _devices.value.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            resetPairing()
            return
        }
        prefs.edit { putStringSet("removed_devices", prefs.getStringSet("removed_devices", emptySet())!! + id) }
        saveDevices(remaining)
    }

    /** New pairing key; every device has to pair again. */
    @Synchronized
    fun resetPairing() {
        prefs.edit {
            remove("devices"); remove("removed_devices"); remove("last_settings_ms"); remove("last_sync_ms"); remove("jago_pin"); remove("jago_pin_check"); remove("jago_pin_changed_ms")
        }
        _devices.value = emptyList()
        setPairing(BridgeCrypto.generateKey())
    }

    private fun saveDevices(devices: Collection<ConnectedDevice>) {
        val json = JSONObject()
        devices.forEach {
            json.put(it.id, JSONObject().put("name", it.name).put("lastSeen", it.lastSeenMillis).put("firstSeen", it.firstSeenMillis))
        }
        prefs.edit { putString("devices", json.toString()) }
        _devices.value = devices.sortedByDescending { it.lastSeenMillis }
    }

    private fun readDevices(): List<ConnectedDevice> = runCatching {
        val json = JSONObject(prefs.getString("devices", null) ?: return emptyList())
        json.keys().asSequence().map { id ->
            val entry = json.getJSONObject(id)
            val lastSeen = entry.optLong("lastSeen")
            ConnectedDevice(id, entry.optString("name", "Artha"), lastSeen, entry.optLong("firstSeen", lastSeen))
        }.sortedByDescending { it.lastSeenMillis }.toList()
    }.getOrDefault(emptyList())

    /** When the PIN in use was changed on the device that sent it. */
    var jagoPinChangedAt: Long
        get() = prefs.getLong("jago_pin_changed_ms", 0L)
        set(value) = prefs.edit { putLong("jago_pin_changed_ms", value) }

    /** Timestamp of the newest settings message applied, so relay replays are ignored. */
    var lastSettingsMillis: Long
        get() = prefs.getLong("last_settings_ms", 0L)
        set(value) = prefs.edit { putLong("last_settings_ms", value) }

    fun getRelayUrls(): List<String> = listOf(
        "wss://nostr-relay.wastu.workers.dev",
        "wss://relay.primal.net",
        "wss://nos.lol",
    )

    fun getLastSyncMillis(): Long? = prefs.getLong("last_sync_ms", 0L).takeIf { it > 0L }

    fun recordSync(now: Long = System.currentTimeMillis()) {
        prefs.edit { putLong("last_sync_ms", now) }
    }

    /** Transaction slots last published; unknown counts as many so legacy slots get cleared. */
    var publishedChunks: Int
        get() = prefs.getInt("published_chunks", 16)
        set(value) = prefs.edit { putInt("published_chunks", value) }
}

class BridgeApplication : Application() {

    val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    lateinit var preferences: BridgePreferences
    val importer by lazy { BridgeImporter(this) }
    val updater by lazy { AppUpdater(this, RootUpdateInstaller()) }

    override fun onCreate() {
        super.onCreate()
        preferences = BridgePreferences(this)

        // Initialize root shell builder
        // Root commands read session tokens, so they never go to logcat.
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))

        // Ensure key is created if not already
        if (!preferences.hasPairing()) {
            preferences.setPairing(BridgeCrypto.generateKey())
        }

        startDaemon()
    }

    /**
     * Starts the sync service. Android refuses foreground services started from the
     * background (e.g. a process start while locked); MainActivity and boot retry then.
     */
    fun startDaemon() {
        val serviceIntent = Intent(this, BridgeDaemonService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        }.onFailure { android.util.Log.w("ArthaBridge", "Sync service not started", it) }
    }

    /**
     * Applies settings sent by the main Artha app: the Jago PIN lives there now,
     * and every message identifies the device so it shows up in the device list.
     * Messages older than the last applied one are ignored.
     */
    fun applySettings(settings: BridgeSettings, sentAt: Long, seenAt: Long = sentAt) {
        if (settings.deviceId?.let(preferences::isRemoved) == true) return
        synchronized(this) {
            if (sentAt > preferences.lastSettingsMillis) preferences.lastSettingsMillis = sentAt
            if (settings.jagoPinChangedAt > preferences.jagoPinChangedAt) {
                preferences.setJagoPin(settings.jagoPin)
                preferences.jagoPinChangedAt = settings.jagoPinChangedAt
            }
        }
        val id = settings.deviceId ?: return
        preferences.recordDevice(id, settings.deviceName ?: "Artha", seenAt)
    }

    /**
     * Unlocks Jago with the PIN so it fetches new transactions, then wakes the Shopee
     * and Grab sessions and imports all four. A drawer phone usually has its screen off, so the screen is
     * switched on for the walk and off again afterwards. A PIN Jago rejected is not
     * typed again until Artha sends a different one, so Jago never locks the account.
     */
    suspend fun runBackgroundSync() {
        val pin = preferences.getJagoPin()
        if (!pin.isNullOrBlank()) {
            val fingerprint = PinCheck.fingerprint(pin)
            val previous = preferences.getPinCheck()?.takeIf { it.fingerprint == fingerprint }
            if (previous?.result != PinCheck.WRONG) {
                val result = withScreenOn { isInteractive ->
                    JagoScreenSync(RootFileReader(this@BridgeApplication), isInteractive, { false }, { pin }).run()
                }
                val check = when (result) {
                    JagoScreenSync.Result.SYNCED, JagoScreenSync.Result.QUIET ->
                        PinCheck(fingerprint, PinCheck.OK, "Bank Jago accepted the PIN", System.currentTimeMillis())
                    JagoScreenSync.Result.WRONG_PIN ->
                        PinCheck(fingerprint, PinCheck.WRONG, "Bank Jago rejected the PIN", System.currentTimeMillis())
                    else -> PinCheck(fingerprint, PinCheck.UNVERIFIED, unverifiedReason(result), System.currentTimeMillis())
                }
                // An earlier success still stands when a later walk could not run.
                if (check.result != PinCheck.UNVERIFIED || previous?.result != PinCheck.OK) preferences.setPinCheck(check)
            }
        }

        val isInteractive = { getSystemService(PowerManager::class.java)?.isInteractive == true }
        AppSessionWakeSync(RootFileReader(this@BridgeApplication), isInteractive, { false }).apply {
            wakeShopee()
            wakeGrab()
        }
        importer.importAll()
    }

    private suspend fun <T> withScreenOn(block: suspend (isInteractive: () -> Boolean) -> T): T {
        val power = getSystemService(PowerManager::class.java)
        val isInteractive = { power?.isInteractive == true }
        val wasOff = !isInteractive()
        if (wasOff) {
            Shell.cmd("input keyevent KEYCODE_WAKEUP").exec()
            repeat(20) { if (!isInteractive()) kotlinx.coroutines.delay(100) }
        }
        return try {
            block(isInteractive)
        } finally {
            if (wasOff && isInteractive()) Shell.cmd("input keyevent KEYCODE_SLEEP").exec()
        }
    }

    private fun unverifiedReason(result: JagoScreenSync.Result): String = when (result) {
        JagoScreenSync.Result.SKIPPED_NOT_INSTALLED -> "Bank Jago is not installed on the bridge phone"
        JagoScreenSync.Result.SKIPPED_JAGO_IN_USE -> "Bank Jago was open on the bridge phone"
        JagoScreenSync.Result.ROOT_UNAVAILABLE -> "The bridge phone has no root access"
        JagoScreenSync.Result.SKIPPED_SCREEN_OFF -> "The bridge phone screen would not turn on"
        JagoScreenSync.Result.UNLOCK_FAILED -> "Bank Jago did not show its PIN screen"
        else -> "Bank Jago could not be opened"
    }
}
