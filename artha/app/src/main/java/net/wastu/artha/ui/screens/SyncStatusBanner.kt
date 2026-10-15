package net.wastu.artha.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic

enum class ProviderNoticeAction {
    OPEN_APP,
    SYNC_NOW,
}

data class ProviderSyncNotice(
    val provider: Provider,
    val title: String,
    val description: String,
    val actionType: ProviderNoticeAction,
    val packageName: String? = null,
)

fun providerPackageName(provider: Provider): String? = when (provider) {
    Provider.JAGO -> "com.jago.digitalBanking"
    Provider.GOJEK -> "com.gojek.app"
    Provider.SHOPEE -> "com.shopee.id"
    Provider.GRAB -> "com.grabtaxi.passenger"
}

fun computeProviderNotices(
    diagnostics: Map<Provider, ProviderDiagnostic>,
    importEnabled: Set<Provider>,
    transactions: List<UnifiedTransaction>,
    nowMillis: Long = System.currentTimeMillis(),
): List<ProviderSyncNotice> {
    val notices = mutableListOf<ProviderSyncNotice>()

    // 1. Check for unlinked GoPay debits in Jago that have no Gojek order record yet
    val hasUnlinkedGopay = transactions.any { tx ->
        tx.provider == Provider.JAGO &&
            tx.rawPayload.contains("GPD") &&
            !tx.rawPayload.contains("groupedTransactions")
    }
    if (hasUnlinkedGopay && Provider.GOJEK in importEnabled) {
        notices += ProviderSyncNotice(
            provider = Provider.GOJEK,
            title = "GoPay payment ready to link",
            description = "Tap to sync merchant & QRIS details",
            actionType = ProviderNoticeAction.SYNC_NOW,
            packageName = providerPackageName(Provider.GOJEK),
        )
    }

    // 2. Check each enabled provider's diagnostics
    for (provider in importEnabled) {
        // Skip Gojek if already flagged for unlinked payment
        if (provider == Provider.GOJEK && hasUnlinkedGopay) continue

        val diag = diagnostics[provider]
        val pkg = providerPackageName(provider)

        when {
            diag?.accountStatus == AccountStatus.NEEDS_LOGIN -> {
                notices += ProviderSyncNotice(
                    provider = provider,
                    title = "${provider.displayName} login expired",
                    description = "Open app to refresh your session",
                    actionType = ProviderNoticeAction.OPEN_APP,
                    packageName = pkg,
                )
            }
            diag?.accountStatus == AccountStatus.ERROR || (diag?.lastError?.contains("403") == true) -> {
                notices += ProviderSyncNotice(
                    provider = provider,
                    title = "${provider.displayName} verification needed",
                    description = "Security check required",
                    actionType = ProviderNoticeAction.OPEN_APP,
                    packageName = pkg,
                )
            }
            provider == Provider.JAGO -> {
                val lastSync = diag?.lastSyncEpochMillis ?: 0L
                val isStale = lastSync == 0L || (nowMillis - lastSync > 12 * 3600_000L)
                if (isStale) {
                    notices += ProviderSyncNotice(
                        provider = Provider.JAGO,
                        title = "Bank Jago statements",
                        description = "Open Jago to unlock latest statements",
                        actionType = ProviderNoticeAction.OPEN_APP,
                        packageName = pkg,
                    )
                }
            }
            else -> {
                val lastSync = diag?.lastSyncEpochMillis ?: 0L
                if (lastSync == 0L || (nowMillis - lastSync > 24 * 3600_000L)) {
                    notices += ProviderSyncNotice(
                        provider = provider,
                        title = "${provider.displayName} ready to sync",
                        description = "Tap to import recent activity",
                        actionType = ProviderNoticeAction.SYNC_NOW,
                        packageName = pkg,
                    )
                }
            }
        }
    }

    return notices
}

@Composable
fun SyncStatusBanner(
    syncingProvider: Provider?,
    refreshing: Boolean,
    diagnostics: Map<Provider, ProviderDiagnostic>,
    importEnabled: Set<Provider>,
    transactions: List<UnifiedTransaction>,
    onSyncProvider: (Provider) -> Unit,
    onSyncAll: () -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember(diagnostics, importEnabled, transactions, refreshing) {
        if (refreshing) emptyList() else computeProviderNotices(diagnostics, importEnabled, transactions)
    }

    val visible = refreshing || notices.isNotEmpty()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            if (refreshing || syncingProvider != null) {
                SyncingProgressCard(syncingProvider = syncingProvider)
            } else if (notices.size == 1) {
                SingleNoticeCard(
                    notice = notices.first(),
                    onSyncProvider = onSyncProvider,
                    onOpenApp = onOpenApp,
                )
            } else {
                MultipleNoticesCard(
                    notices = notices,
                    onSyncAll = onSyncAll,
                    onSyncProvider = onSyncProvider,
                    onOpenApp = onOpenApp,
                )
            }
        }
    }
}

@Composable
private fun SyncingProgressCard(syncingProvider: Provider?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = syncingProvider?.let { "Syncing ${it.displayName}..." } ?: "Syncing accounts...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun SingleNoticeCard(
    notice: ProviderSyncNotice,
    onSyncProvider: (Provider) -> Unit,
    onOpenApp: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(notice.provider.iconRes),
            contentDescription = notice.provider.displayName,
            modifier = Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = notice.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (notice.actionType) {
            ProviderNoticeAction.OPEN_APP -> {
                FilledTonalButton(
                    onClick = { notice.packageName?.let(onOpenApp) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("Open", style = MaterialTheme.typography.labelMedium)
                }
            }
            ProviderNoticeAction.SYNC_NOW -> {
                FilledTonalButton(
                    onClick = { onSyncProvider(notice.provider) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("Sync", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun MultipleNoticesCard(
    notices: List<ProviderSyncNotice>,
    onSyncAll: () -> Unit,
    onSyncProvider: (Provider) -> Unit,
    onOpenApp: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${notices.size} accounts need attention",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onSyncAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Sync all", style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(notices) { notice ->
                AssistChip(
                    onClick = {
                        if (notice.actionType == ProviderNoticeAction.OPEN_APP) {
                            notice.packageName?.let(onOpenApp)
                        } else {
                            onSyncProvider(notice.provider)
                        }
                    },
                    label = {
                        Text(
                            "${notice.provider.displayName}: ${if (notice.actionType == ProviderNoticeAction.OPEN_APP) "Open" else "Sync"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon = {
                        Image(
                            painter = painterResource(notice.provider.iconRes),
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    },
                )
            }
        }
    }
}
