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
    ERROR,
}

data class ProviderDiagnostic(
    val lastSyncEpochMillis: Long? = null,
    val lastAttemptEpochMillis: Long? = null,
    val lastSyncSummary: String? = null,
    val lastError: String? = null,
    val accountStatus: AccountStatus = AccountStatus.UNKNOWN,
    val accountIdentifier: String? = null,
)

class ProviderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("provider_visibility", Context.MODE_PRIVATE)
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
            )
        }

    fun isStale(provider: Provider, now: Long = System.currentTimeMillis()): Boolean {
        val state = diagnostics.value[provider] ?: return true
        val last = state.lastAttemptEpochMillis ?: return true
        val window = if (state.accountStatus in setOf(AccountStatus.ERROR, AccountStatus.NEEDS_LOGIN, AccountStatus.APP_NOT_INSTALLED)) 5 * 60_000 else 15 * 60_000
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
    ) {
        val now = System.currentTimeMillis()
        val current = _diagnostics.value.toMutableMap()
        val prev = current[provider] ?: ProviderDiagnostic()
        val finalIdent = accountIdentifier ?: prev.accountIdentifier
        val updated = ProviderDiagnostic(
            lastSyncEpochMillis = now,
            lastAttemptEpochMillis = now,
            lastSyncSummary = listOfNotNull(summary, coverageHint).joinToString(" · "),
            lastError = coverageHint,
            accountStatus = if (coverageHint == null) AccountStatus.CONNECTED else AccountStatus.PARTIAL,
            accountIdentifier = finalIdent,
        )
        current[provider] = updated
        preferences.edit {
            putLong("${provider.name}_last_sync_ms", now)
            putLong("${provider.name}_last_attempt_ms", now)
            putString("${provider.name}_last_summary", updated.lastSyncSummary)
            if (coverageHint == null) remove("${provider.name}_last_error") else putString("${provider.name}_last_error", coverageHint)
            putString("${provider.name}_status", updated.accountStatus.name)
            if (finalIdent != null) putString("${provider.name}_identifier", finalIdent)
        }
        _diagnostics.value = current
    }

    @Synchronized
    fun recordSyncError(
        provider: Provider,
        error: String,
        status: AccountStatus = AccountStatus.ERROR,
    ) {
        val current = _diagnostics.value.toMutableMap()
        val prev = current[provider] ?: ProviderDiagnostic()
        val attemptedAt = System.currentTimeMillis()
        val updated = prev.copy(
            lastAttemptEpochMillis = attemptedAt,
            lastError = error,
            accountStatus = status,
        )
        current[provider] = updated
        preferences.edit {
            putLong("${provider.name}_last_attempt_ms", attemptedAt)
            putString("${provider.name}_last_error", error)
            putString("${provider.name}_status", status.name)
        }
        _diagnostics.value = current
    }

}
