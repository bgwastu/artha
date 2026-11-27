package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.YearMonth
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic

internal enum class SourceHealthReason { SIGN_IN, FAILED, LIMITED, MISSING_APP, STALE, PARTIAL }

internal data class SourceHealthIssue(
    val provider: Provider,
    val reason: SourceHealthReason,
    val diagnostic: ProviderDiagnostic,
) {
    val title: String
        get() = when (reason) {
            SourceHealthReason.SIGN_IN -> "Sign in to ${provider.displayName}"
            SourceHealthReason.FAILED -> "${provider.displayName} sync failed"
            SourceHealthReason.LIMITED -> "${provider.displayName} is limiting sync"
            SourceHealthReason.MISSING_APP -> "${provider.displayName} app not installed"
            SourceHealthReason.STALE -> "${provider.displayName} sync is overdue"
            SourceHealthReason.PARTIAL -> "${provider.displayName} sync is incomplete"
        }

    fun affectsMonth(month: YearMonth, currentMonth: YearMonth): Boolean {
        if (month >= currentMonth) return month == currentMonth
        if (diagnostic.lastSyncEpochMillis?.let { month >= accountingMonth(it) } == true) return true
        if (reason != SourceHealthReason.PARTIAL) return false
        val window = diagnostic.lastImportWindow ?: return false
        val first = window.earliestMillis ?: return false
        val last = window.latestMillis ?: return false
        return month in accountingMonth(first)..accountingMonth(last)
    }
}

internal fun sourceHealthIssues(
    diagnostics: Map<Provider, ProviderDiagnostic>,
    importEnabled: Set<Provider>,
    providerCounts: Map<Provider, Int>,
    now: Long,
): List<SourceHealthIssue> = importEnabled.mapNotNull { provider ->
    val diagnostic = diagnostics[provider] ?: return@mapNotNull null
    val lastSync = diagnostic.lastSyncEpochMillis
    val reason = when (diagnostic.accountStatus) {
        AccountStatus.NEEDS_LOGIN -> SourceHealthReason.SIGN_IN
        AccountStatus.ERROR -> SourceHealthReason.FAILED
        AccountStatus.RATE_LIMITED -> SourceHealthReason.LIMITED
        AccountStatus.APP_NOT_INSTALLED -> {
            // Import is enabled by default, even for apps the user has never had.
            if (lastSync == null && providerCounts.getOrDefault(provider, 0) == 0) return@mapNotNull null
            SourceHealthReason.MISSING_APP
        }
        AccountStatus.PARTIAL -> SourceHealthReason.PARTIAL
        AccountStatus.CONNECTED, AccountStatus.UNKNOWN -> {
            if (!isSourceSyncOverdue(lastSync, now)) return@mapNotNull null
            SourceHealthReason.STALE
        }
    }
    SourceHealthIssue(provider, reason, diagnostic)
}.sortedWith(compareBy({ it.reason }, { it.provider }))

private fun accountingMonth(epochMillis: Long): YearMonth =
    YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(AccountingTime.zone))

@Composable
internal fun SourceHealthNotice(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
