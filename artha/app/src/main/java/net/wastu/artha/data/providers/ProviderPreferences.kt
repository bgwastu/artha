package net.wastu.artha.data.providers

import android.content.Context
import androidx.core.content.edit
import net.wastu.artha.core.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccountStatus {
    UNKNOWN,
    PARTIAL,
    CONNECTED,
    NEEDS_LOGIN,
    APP_NOT_INSTALLED,
    RATE_LIMITED,
    ERROR,
}

data class ProviderDiagnostic(
    val lastSyncEpochMillis: Long? = null,
    val lastAttemptEpochMillis: Long? = null,
    val lastSyncSummary: String? = null,
    val lastError: String? = null,
    val accountStatus: AccountStatus = AccountStatus.UNKNOWN,
    val accountIdentifier: String? = null,
    val coverageHint: String? = null,
    val lastImportWindow: ProviderImportWindow? = null,
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

}
