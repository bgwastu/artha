package net.wastu.artha.bridge.crypto

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONArray
import org.json.JSONObject

enum class BridgeMessageType {
    TRANSACTIONS,
    SETTINGS,
    PING,
    ACK,
}

data class BridgeMessage(
    val type: BridgeMessageType,
    val timestamp: Long,
    val payload: String,
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("type", type.name)
            put("timestamp", timestamp)
            put("payload", payload)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): BridgeMessage {
            val json = JSONObject(jsonStr)
            return BridgeMessage(
                type = BridgeMessageType.valueOf(json.getString("type")),
                timestamp = json.getLong("timestamp"),
                payload = json.getString("payload"),
            )
        }
    }
}

data class BridgeSettings(
    val jagoPin: String? = null,
    val enabledProviders: Set<Provider> = Provider.entries.toSet(),
    val syncIntervalMinutes: Int = 15,
    /** Stable id of the Artha install sending these settings. */
    val deviceId: String? = null,
    /** Human-readable name of that device, shown in the bridge's device list. */
    val deviceName: String? = null,
    /**
     * When the user last changed [jagoPin] on that device, 0 if never. The bridge keeps
     * the latest change, so a device that never set a PIN cannot clear another's.
     */
    val jagoPinChangedAt: Long = 0L,
) {
    fun toJson(): String = JSONObject().apply {
        put("jagoPin", jagoPin ?: JSONObject.NULL)
        put("enabledProviders", JSONArray(enabledProviders.map { it.name }))
        put("syncIntervalMinutes", syncIntervalMinutes)
        put("deviceId", deviceId ?: JSONObject.NULL)
        put("deviceName", deviceName ?: JSONObject.NULL)
        put("jagoPinChangedAt", jagoPinChangedAt)
    }.toString()

    companion object {
        fun fromJson(jsonStr: String): BridgeSettings {
            val json = JSONObject(jsonStr)
            val jagoPin = if (json.isNull("jagoPin")) null else json.getString("jagoPin")
            val providers = mutableSetOf<Provider>()
            val arr = json.optJSONArray("enabledProviders")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    runCatching { Provider.valueOf(arr.getString(i)) }.getOrNull()?.let { providers.add(it) }
                }
            }
            val syncInterval = json.optInt("syncIntervalMinutes", 15)
            val deviceId = if (json.isNull("deviceId")) null else json.optString("deviceId").ifBlank { null }
            val deviceName = if (json.isNull("deviceName")) null else json.optString("deviceName").ifBlank { null }
            val pinChangedAt = json.optLong("jagoPinChangedAt", 0L)
            return BridgeSettings(jagoPin, providers, syncInterval, deviceId, deviceName, pinChangedAt)
        }
    }
}
