package net.wastu.artha.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic

internal fun isSourceProblem(diagnostic: ProviderDiagnostic?, importEnabled: Boolean, importing: Boolean): Boolean {
    if (importing || !importEnabled) return false
    return when (diagnostic?.accountStatus) {
        AccountStatus.NEEDS_LOGIN,
        AccountStatus.APP_NOT_INSTALLED,
        AccountStatus.RATE_LIMITED,
        AccountStatus.ERROR -> true
        else -> false
    }
}

/** A failed or throttled sync can simply be run again; a signed-out or missing app needs the user first. */
internal fun canRetrySync(diagnostic: ProviderDiagnostic?, importEnabled: Boolean, importing: Boolean): Boolean =
    importEnabled && !importing &&
        (diagnostic?.accountStatus == AccountStatus.ERROR || diagnostic?.accountStatus == AccountStatus.RATE_LIMITED)

internal fun sourceRowStatus(
    provider: Provider,
    diagnostic: ProviderDiagnostic?,
    importEnabled: Boolean,
    importing: Boolean,
    now: Long = System.currentTimeMillis(),
): String {
    if (importing) return "Syncing"
    if (!importEnabled) return "Paused"
    return when (diagnostic?.accountStatus) {
        AccountStatus.NEEDS_LOGIN -> "Sign in to ${provider.displayName}"
        AccountStatus.APP_NOT_INSTALLED -> "${provider.displayName} app not installed"
        AccountStatus.RATE_LIMITED -> "Limited by ${provider.displayName}. Try later"
        AccountStatus.ERROR -> "Last sync failed"
        AccountStatus.PARTIAL -> diagnostic.lastSyncEpochMillis
            ?.let { "Partly synced ${formatRelativeTime(it, now)}" } ?: "Partly synced"
        else -> {
            val lastSync = diagnostic?.lastSyncEpochMillis
            if (lastSync == null) {
                "Not synced yet"
            } else if ((now - lastSync) in 0 until 15 * 60 * 1000L) {
                "Up to date"
            } else {
                "Synced ${formatRelativeTime(lastSync, now)}"
            }
        }
    }
}

internal fun sourceHeadline(
    provider: Provider,
    diagnostic: ProviderDiagnostic?,
    importEnabled: Boolean,
    importing: Boolean,
): String = when {
    importing -> "Syncing"
    !importEnabled -> "Paused"
    diagnostic?.accountStatus == AccountStatus.NEEDS_LOGIN -> "Sign in to ${provider.displayName}"
    diagnostic?.accountStatus == AccountStatus.APP_NOT_INSTALLED -> "${provider.displayName} app not installed"
    diagnostic?.accountStatus == AccountStatus.RATE_LIMITED -> "Limited by ${provider.displayName}"
    diagnostic?.accountStatus == AccountStatus.ERROR -> "Last sync failed"
    diagnostic?.accountStatus == AccountStatus.PARTIAL -> "Partly synced"
    else -> "Up to date"
}

internal fun formatRelativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diffSec = ((now - epochMillis) / 1000).coerceAtLeast(0)
    val diffMin = diffSec / 60
    val diffHours = diffMin / 60
    val diffDays = diffHours / 24
    return when {
        diffSec < 60 -> "just now"
        diffMin < 60 -> "$diffMin min ago"
        diffHours < 24 -> "$diffHours h ago"
        diffDays == 1L -> "yesterday"
        else -> "$diffDays days ago"
    }
}

private val ADDED_REGEX = Regex("""\b(\d+)\s+added\b""", RegexOption.IGNORE_CASE)

internal fun lastSyncText(summary: String?): String? {
    if (summary.isNullOrBlank()) return null
    val match = ADDED_REGEX.find(summary) ?: return null
    val count = match.groupValues[1].toIntOrNull() ?: return null
    return when (count) {
        0 -> "No new payments"
        1 -> "1 new payment"
        else -> "$count new payments"
    }
}

internal fun countExpenses(transactions: List<UnifiedTransaction>, provider: Provider): Int =
    transactions.count { it.provider == provider && it.direction == Direction.DEBIT }

internal fun formatSavedDay(instant: Instant, zone: ZoneId = AccountingTime.zone): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).withZone(zone).format(instant)
