package net.wastu.artha.data.sync.bridge

import android.content.Context
import androidx.core.content.edit
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class BridgePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("artha_bridge_prefs", Context.MODE_PRIVATE)

    private val _paired = MutableStateFlow(hasPairing())
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private val _isHelper = MutableStateFlow(prefs.getBoolean("is_helper_mode", false))
    val isHelper: StateFlow<Boolean> = _isHelper.asStateFlow()

    private val _bridgeDeviceName = MutableStateFlow(getBridgeDeviceName())
    val bridgeDeviceName: StateFlow<String?> = _bridgeDeviceName.asStateFlow()

    private val _bridgeVersion = MutableStateFlow(getBridgeVersion())
    val bridgeVersion: StateFlow<String?> = _bridgeVersion.asStateFlow()

    private val _bridgeLastSeen = MutableStateFlow(getBridgeLastSeenMillis())
    val bridgeLastSeen: StateFlow<Long?> = _bridgeLastSeen.asStateFlow()

    private val _lastSync = MutableStateFlow(getLastSyncMillis())
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    fun hasPairing(): Boolean = prefs.getString("secret_key", null) != null

    fun getSecretKey(): ByteArray? {
        val b64 = prefs.getString("secret_key", null) ?: return null
        return runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
    }

    fun getChannelId(): String? = getSecretKey()?.let { BridgeCrypto.deriveChannelId(it) }

    fun getBridgeHost(): String? = prefs.getString("bridge_host", null)

    fun setBridgeHost(host: String?) {
        prefs.edit {
            if (host.isNullOrBlank()) remove("bridge_host")
            else putString("bridge_host", host.trim())
        }
    }

    fun setPairing(secretKey: ByteArray, isHelper: Boolean, host: String? = null) {
        val b64 = Base64.getEncoder().encodeToString(secretKey)
        prefs.edit {
            putString("secret_key", b64)
            putBoolean("is_helper_mode", isHelper)
            if (host != null) putString("bridge_host", host.trim())
        }
        _paired.value = true
        _isHelper.value = isHelper
        _bridgeDeviceName.value = getBridgeDeviceName()
        _bridgeVersion.value = getBridgeVersion()
        _bridgeLastSeen.value = getBridgeLastSeenMillis()
        _lastSync.value = getLastSyncMillis()
    }

    /**
     * Pairs from what Artha Bridge shows: the `artha://pair?key=…` link, or the bare key.
     * Returns false when [code] holds no 32-byte key.
     */
    fun pairWith(code: String): Boolean {
        val clean = code.trim()
        fun param(name: String) = Regex("[?&]$name=([^&#\\s]+)").find(clean)?.groupValues?.get(1)
        val keyParam = param("key") ?: clean
        val key = runCatching { Base64.getUrlDecoder().decode(keyParam) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(keyParam) }.getOrNull()
        if (key == null || key.size != 32) return false
        setPairing(key, isHelper = false, host = param("host"))
        return true
    }

    /** Stable id for this install, sent so the bridge can list it as a connected device. */
    val deviceId: String
        @Synchronized get() = prefs.getString("device_id", null)
            ?: java.util.UUID.randomUUID().toString().also { id -> prefs.edit { putString("device_id", id) } }

    /** Model name the bridge reported in its last status message. */
    fun getBridgeDeviceName(): String? = prefs.getString("bridge_device_name", null)

    fun setBridgeDeviceName(name: String?) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { if (clean == null) remove("bridge_device_name") else putString("bridge_device_name", clean) }
        _bridgeDeviceName.value = clean
    }

    /** App version reported by the bridge. */
    fun getBridgeVersion(): String? = prefs.getString("bridge_version", null)

    fun setBridgeVersion(version: String?) {
        val clean = version?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { if (clean == null) remove("bridge_version") else putString("bridge_version", clean) }
        _bridgeVersion.value = clean
    }

    /** When the bridge last published anything, as stamped by the bridge. */
    fun getBridgeLastSeenMillis(): Long? = prefs.getLong("bridge_last_seen_ms", 0L).takeIf { it > 0L }

    fun recordBridgeSeen(millis: Long) {
        if (millis > (getBridgeLastSeenMillis() ?: 0L)) {
            prefs.edit { putLong("bridge_last_seen_ms", millis) }
            _bridgeLastSeen.value = millis
        }
    }

    fun clearPairing() {
        prefs.edit {
            remove("secret_key")
            remove("is_helper_mode")
            remove("bridge_host")
            remove("last_bridge_sync_ms")
            remove("bridge_device_name")
            remove("bridge_version")
            remove("settings_pending")
            remove("bridge_last_seen_ms")
        }
        _paired.value = false
        _isHelper.value = false
        _bridgeDeviceName.value = null
        _bridgeVersion.value = null
        _bridgeLastSeen.value = null
        _lastSync.value = null
    }

    fun getRelayUrls(): List<String> {
        val raw = prefs.getString("relay_urls", null)
        if (raw != null) {
            val list = mutableListOf<String>()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            if (list.isNotEmpty()) return list
        }
        return DEFAULT_RELAYS
    }

    fun setRelayUrls(urls: List<String>) {
        val arr = JSONArray(urls)
        prefs.edit { putString("relay_urls", arr.toString()) }
    }

    fun getLastSyncMillis(): Long? = prefs.getLong("last_bridge_sync_ms", 0L).takeIf { it > 0L }

    fun recordSync(now: Long = System.currentTimeMillis()) {
        prefs.edit { putLong("last_bridge_sync_ms", now) }
        _lastSync.value = now
    }

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://nostr-relay.wastu.workers.dev",
            "wss://relay.primal.net",
            "wss://nos.lol",
        )
    }
}
