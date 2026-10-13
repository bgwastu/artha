package net.wastu.artha.data.providers

import android.content.Context
import androidx.core.content.edit
import net.wastu.artha.core.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccountStatus {
    CONNECTED,
    NEEDS_LOGIN,
    APP_NOT_INSTALLED,
    ERROR,
}

data class ProviderDiagnostic(
    val lastSyncEpochMillis: Long? = null,
    val lastSyncSummary: String? = null,
    val lastError: String? = null,
    val accountStatus: AccountStatus = AccountStatus.CONNECTED,
    val accountIdentifier: String? = null,
)

class ProviderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("provider_visibility", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(Provider.entries.filter { preferences.getBoolean(it.name, true) }.toSet())
    val enabled = _enabled.asStateFlow()

    private val _diagnostics = MutableStateFlow(loadDiagnostics())
    val diagnostics = _diagnostics.asStateFlow()

    private fun loadDiagnostics(): Map<Provider, ProviderDiagnostic> =
        Provider.entries.associateWith { p ->
            val syncTime = preferences.getLong("${p.name}_last_sync_ms", 0L).takeIf { it > 0L }
            val summary = preferences.getString("${p.name}_last_summary", null)
            val err = preferences.getString("${p.name}_last_error", null)
            val statusStr = preferences.getString("${p.name}_status", AccountStatus.CONNECTED.name) ?: AccountStatus.CONNECTED.name
            val status = runCatching { AccountStatus.valueOf(statusStr) }.getOrDefault(AccountStatus.CONNECTED)
            val ident = preferences.getString("${p.name}_identifier", null)
            ProviderDiagnostic(
                lastSyncEpochMillis = syncTime,
                lastSyncSummary = summary,
                lastError = err,
                accountStatus = status,
                accountIdentifier = ident,
            )
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
    ) {
        val now = System.currentTimeMillis()
        val current = _diagnostics.value.toMutableMap()
        val prev = current[provider] ?: ProviderDiagnostic()
        val finalIdent = accountIdentifier ?: prev.accountIdentifier
        val updated = ProviderDiagnostic(
            lastSyncEpochMillis = now,
            lastSyncSummary = summary,
            lastError = null,
            accountStatus = AccountStatus.CONNECTED,
            accountIdentifier = finalIdent,
        )
        current[provider] = updated
        preferences.edit {
            putLong("${provider.name}_last_sync_ms", now)
            putString("${provider.name}_last_summary", summary)
            remove("${provider.name}_last_error")
            putString("${provider.name}_status", AccountStatus.CONNECTED.name)
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
        val updated = prev.copy(
            lastError = error,
            accountStatus = status,
        )
        current[provider] = updated
        preferences.edit {
            putString("${provider.name}_last_error", error)
            putString("${provider.name}_status", status.name)
        }
        _diagnostics.value = current
    }
}
