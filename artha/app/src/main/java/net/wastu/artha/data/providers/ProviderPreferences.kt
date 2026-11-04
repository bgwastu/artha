package net.wastu.artha.data.providers

import android.content.Context
import androidx.core.content.edit
import net.wastu.artha.core.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


data class ProviderDiagnostic(
    val lastSyncEpochMillis: Long? = null,
    val lastAttemptEpochMillis: Long? = null,
    val lastSyncSummary: String? = null,
    val lastError: String? = null,
    val accountStatus: AccountStatus = AccountStatus.UNKNOWN,
    val accountIdentifier: String? = null,
    val coverageHint: String? = null,
    val lastImportWindow: ProviderImportWindow? = null,
    /** Set when Artha Bridge reported this state, so its counts are the whole history it read. */
    val fromBridge: Boolean = false,
)

/** Observed payment dates in one import, never a guarantee of continuous history. */
data class ProviderImportWindow(val paymentCount: Int, val earliestMillis: Long?, val latestMillis: Long?) {
    init {
        require(paymentCount >= 0)
        require(if (paymentCount == 0) earliestMillis == null && latestMillis == null
            else earliestMillis != null && latestMillis != null && earliestMillis <= latestMillis)
    }
}

/** Latest invisible GoPay flash trigger outcome for the provider screen. */
data class GojekFlashRecord(val millis: Long?, val result: String)

class ProviderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("provider_visibility", Context.MODE_PRIVATE)

    /** Outcome of the last invisible GoPay flash, so sleep-time syncs stay observable in the UI. */
    private val _gojekFlash = MutableStateFlow(
        GojekFlashRecord(preferences.getLong("GOJEK_flash_ms", 0L).takeIf { it > 0L },
            preferences.getString("GOJEK_flash_result", null).orEmpty()))
    val gojekFlash = _gojekFlash.asStateFlow()

    fun recordGojekFlash(result: String, now: Long = System.currentTimeMillis()) {
        preferences.edit { putLong("GOJEK_flash_ms", now).putString("GOJEK_flash_result", result) }
        _gojekFlash.value = GojekFlashRecord(now, result)
    }
    init {
        val obsoleteKeys = preferences.all.keys.filter { it.startsWith("TOKOPEDIA") }
        if (obsoleteKeys.isNotEmpty()) {
            preferences.edit {
                for (key in obsoleteKeys) {
                    remove(key)
                }
            }
        }
    }
    private val _enabled = MutableStateFlow(Provider.entries.filter { preferences.getBoolean(it.name, true) }.toSet())
    val enabled = _enabled.asStateFlow()
    private val _importEnabled = MutableStateFlow(Provider.entries.filter { preferences.getBoolean("${it.name}_import", true) }.toSet())
    val importEnabled = _importEnabled.asStateFlow()

    @Synchronized
    fun setImportEnabled(provider: Provider, enabled: Boolean) {

        preferences.edit { putBoolean("${provider.name}_import", enabled) }
        _importEnabled.value = if (enabled) _importEnabled.value + provider else _importEnabled.value - provider
    }

    private val _diagnostics = MutableStateFlow(loadDiagnostics())
    val diagnostics = _diagnostics.asStateFlow()

    private fun loadDiagnostics(): Map<Provider, ProviderDiagnostic> =
        Provider.entries.associateWith { p ->
            val syncTime = preferences.getLong("${p.name}_last_sync_ms", 0L).takeIf { it > 0L }
            val summary = preferences.getString("${p.name}_last_summary", null)
            val err = preferences.getString("${p.name}_last_error", null)
            val statusStr = preferences.getString("${p.name}_status", AccountStatus.UNKNOWN.name) ?: AccountStatus.UNKNOWN.name
            val status = runCatching { AccountStatus.valueOf(statusStr) }.getOrDefault(AccountStatus.UNKNOWN)
            val ident = preferences.getString("${p.name}_identifier", null)
            ProviderDiagnostic(
                lastSyncEpochMillis = syncTime,
                lastAttemptEpochMillis = preferences.getLong("${p.name}_last_attempt_ms", 0).takeIf { it > 0 },
                lastSyncSummary = summary,
                lastError = err,
                accountStatus = status,
                accountIdentifier = ident,
                coverageHint = preferences.getString("${p.name}_coverage_hint", null),
                lastImportWindow = preferences.getLong("${p.name}_read_count", -1L).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let { count ->
                    runCatching { ProviderImportWindow(count.toInt(),
                        preferences.getLong("${p.name}_read_first_ms", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
                        preferences.getLong("${p.name}_read_last_ms", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }) }.getOrNull()
                },
                fromBridge = preferences.getBoolean("${p.name}_from_bridge", false),
            )
        }

    fun isStale(provider: Provider, now: Long = System.currentTimeMillis()): Boolean {
        val state = diagnostics.value[provider] ?: return true
        val last = state.lastAttemptEpochMillis ?: return true
        val window = when (state.accountStatus) {
            AccountStatus.RATE_LIMITED -> 30 * 60_000
            AccountStatus.ERROR, AccountStatus.NEEDS_LOGIN, AccountStatus.APP_NOT_INSTALLED -> 5 * 60_000
            else -> 15 * 60_000
        }
        return now < last || now - last >= window
    }

    fun setEnabled(provider: Provider, enabled: Boolean) {
        preferences.edit { putBoolean(provider.name, enabled) }
        _enabled.value = if (enabled) _enabled.value + provider else _enabled.value - provider
    }

    @Synchronized
    fun recordSyncSuccess(
        provider: Provider,
        summary: String,
        accountIdentifier: String? = null,
        coverageHint: String? = null,
        partialReason: String? = null,
        importWindow: ProviderImportWindow? = null,
    ) {
        val now = System.currentTimeMillis()
        val current = _diagnostics.value.toMutableMap()
        val prev = current[provider] ?: ProviderDiagnostic()
        val finalIdent = accountIdentifier ?: prev.accountIdentifier
        val updated = ProviderDiagnostic(
            lastSyncEpochMillis = now,
            lastAttemptEpochMillis = now,
            lastSyncSummary = listOfNotNull(summary, partialReason).joinToString(". "),
            lastError = partialReason,
            accountStatus = if (partialReason == null) AccountStatus.CONNECTED else AccountStatus.PARTIAL,
            accountIdentifier = finalIdent,
            coverageHint = coverageHint,
            lastImportWindow = importWindow,
        )
        current[provider] = updated
        preferences.edit {
            putLong("${provider.name}_last_sync_ms", now)
            putLong("${provider.name}_last_attempt_ms", now)
            putString("${provider.name}_last_summary", updated.lastSyncSummary)
            if (partialReason == null) remove("${provider.name}_last_error") else putString("${provider.name}_last_error", partialReason)
            if (coverageHint == null) remove("${provider.name}_coverage_hint") else putString("${provider.name}_coverage_hint", coverageHint)
            putString("${provider.name}_status", updated.accountStatus.name)
            if (finalIdent != null) putString("${provider.name}_identifier", finalIdent)
            if (importWindow == null) remove("${provider.name}_read_count")
            else putLong("${provider.name}_read_count", importWindow.paymentCount.toLong())
            if (importWindow?.earliestMillis == null) remove("${provider.name}_read_first_ms")
            else putLong("${provider.name}_read_first_ms", importWindow.earliestMillis)
            if (importWindow?.latestMillis == null) remove("${provider.name}_read_last_ms")
            else putLong("${provider.name}_read_last_ms", importWindow.latestMillis)
            remove("${provider.name}_from_bridge")
        }
        _diagnostics.value = current
    }

    /**
     * Replaces each source's state with what Artha Bridge reported, as is: on a paired phone
     * the bridge does the reading, so its times, counts and errors are the real ones.
     */
    @Synchronized
    fun applyBridgeStatuses(statuses: List<net.wastu.artha.core.bridge.BridgeSourceStatus>) {
        if (statuses.isEmpty()) return
        val current = _diagnostics.value.toMutableMap()
        preferences.edit {
            for (s in statuses) {
                val window = s.paymentCount?.let { count ->
                    runCatching { ProviderImportWindow(count, s.earliestMillis, s.latestMillis) }.getOrNull()
                }
                current[s.provider] = ProviderDiagnostic(
                    lastSyncEpochMillis = s.syncedAt, lastAttemptEpochMillis = s.attemptedAt,
                    lastSyncSummary = s.summary, lastError = s.error, accountStatus = s.status,
                    accountIdentifier = s.accountIdentifier, coverageHint = s.coverageHint, lastImportWindow = window,
                    fromBridge = true,
                )
                val p = s.provider.name
                fun putOrRemove(key: String, value: String?) { if (value == null) remove(key) else putString(key, value) }
                fun putOrRemove(key: String, value: Long?) { if (value == null) remove(key) else putLong(key, value) }
                putOrRemove("${p}_last_sync_ms", s.syncedAt)
                putLong("${p}_last_attempt_ms", s.attemptedAt)
                putOrRemove("${p}_last_summary", s.summary)
                putOrRemove("${p}_last_error", s.error)
                putString("${p}_status", s.status.name)
                putOrRemove("${p}_identifier", s.accountIdentifier)
                putOrRemove("${p}_coverage_hint", s.coverageHint)
                putOrRemove("${p}_read_count", window?.paymentCount?.toLong())
                putOrRemove("${p}_read_first_ms", window?.earliestMillis)
                putOrRemove("${p}_read_last_ms", window?.latestMillis)
                putBoolean("${p}_from_bridge", true)
            }
        }
        _diagnostics.value = current
    }

    @Synchronized
    fun recordSyncError(
        provider: Provider,
        error: String,
        status: AccountStatus = AccountStatus.ERROR,
        coverageHint: String? = null,
    ) {
        val current = _diagnostics.value.toMutableMap()
        val prev = current[provider] ?: ProviderDiagnostic()
        val attemptedAt = System.currentTimeMillis()
        val updated = prev.copy(
            lastAttemptEpochMillis = attemptedAt,
            lastError = error,
            accountStatus = status,
            coverageHint = coverageHint ?: prev.coverageHint,
        )
        current[provider] = updated
        preferences.edit {
            putLong("${provider.name}_last_attempt_ms", attemptedAt)
            putString("${provider.name}_last_error", error)
            putString("${provider.name}_status", status.name)
            coverageHint?.let { putString("${provider.name}_coverage_hint", it) }
        }
        _diagnostics.value = current
    }

    fun getJagoPin(): String? = preferences.getString("JAGO_pin", null)

    /** When the user last set or cleared the PIN here; 0 if never, so it never overrides another phone. */
    fun getJagoPinChangedAt(): Long =
        preferences.getLong("JAGO_pin_changed_ms", if (getJagoPin() != null) 1L else 0L)

    fun isJagoPinSet(): Boolean =
        !getJagoPin().isNullOrBlank() || preferences.getBoolean("JAGO_bridge_configured", false) || _jagoPinCheck.value.status == JagoPinStatus.VERIFIED

    private val _jagoPinCheck = MutableStateFlow(loadJagoPinCheck())
    /** What happened when Artha Bridge last typed the current PIN into Bank Jago. */
    val jagoPinCheck = _jagoPinCheck.asStateFlow()

    private val _jagoPinSet = MutableStateFlow(isJagoPinSet())
    /** Whether Bank Jago's background PIN is set; without it Jago only syncs when opened. */
    val jagoPinSet = _jagoPinSet.asStateFlow()

    fun setJagoPin(pin: String?) {
        val next = pin?.trim()?.takeIf { it.isNotEmpty() }
        val changed = next != getJagoPin()
        preferences.edit {
            if (next == null) {
                remove("JAGO_pin")
                remove("JAGO_bridge_configured")
            } else {
                putString("JAGO_pin", next)
            }
            if (changed) {
                remove("JAGO_pin_check")
                putLong("JAGO_pin_changed_ms", System.currentTimeMillis())
            }
        }
        _jagoPinSet.value = isJagoPinSet()
        if (changed) _jagoPinCheck.value = JagoPinCheck()
    }

    fun recordJagoPinConfigured(configured: Boolean) {
        preferences.edit { putBoolean("JAGO_bridge_configured", configured) }
        _jagoPinSet.value = isJagoPinSet()
        if (configured && _jagoPinCheck.value.status == JagoPinStatus.UNCHECKED) {
            _jagoPinCheck.value = JagoPinCheck(JagoPinStatus.VERIFIED, "PIN configured on Artha Bridge")
        }
    }

    /** Records the bridge's result, whether for the PIN stored here or reported configured by the bridge. */
    fun recordJagoPinCheck(fingerprint: String, result: String, detail: String, configuredOnBridge: Boolean = true) {
        val status = when (result) {
            "ok" -> JagoPinStatus.VERIFIED
            "wrong" -> JagoPinStatus.WRONG
            else -> JagoPinStatus.UNVERIFIED
        }
        preferences.edit {
            putString("JAGO_pin_check", "${status.name}|$detail")
            putBoolean("JAGO_bridge_configured", configuredOnBridge || status == JagoPinStatus.VERIFIED)
        }
        _jagoPinCheck.value = JagoPinCheck(status, detail)
        _jagoPinSet.value = isJagoPinSet()
    }

    private fun loadJagoPinCheck(): JagoPinCheck {
        val raw = preferences.getString("JAGO_pin_check", null)
        if (raw != null) {
            val status = runCatching { JagoPinStatus.valueOf(raw.substringBefore('|')) }.getOrNull()
            if (status != null) {
                return JagoPinCheck(status, raw.substringAfter('|', "").ifBlank { null })
            }
        }
        if (preferences.getBoolean("JAGO_bridge_configured", false)) {
            return JagoPinCheck(JagoPinStatus.VERIFIED, "PIN configured on Artha Bridge")
        }
        return JagoPinCheck()
    }
}

enum class JagoPinStatus { UNCHECKED, VERIFIED, WRONG, UNVERIFIED }

data class JagoPinCheck(val status: JagoPinStatus = JagoPinStatus.UNCHECKED, val detail: String? = null)

/** Matches Artha Bridge's fingerprint, so its status names a PIN without carrying it. */
fun jagoPinFingerprint(pin: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("artha-jago-pin:${pin.trim()}".toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
