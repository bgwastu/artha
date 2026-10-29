package net.wastu.artha.ui.screens

import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.export.LedgerExportStorage
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.GojekFlashRecord
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.SourceCoverage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    enabled: Set<Provider> = Provider.entries.toSet(),
    syncingProvider: Provider? = null,
    providerCounts: Map<Provider, Int> = emptyMap(),
    onEnabled: (Provider, Boolean) -> Unit = { _, _ -> },
    viewModel: ArthaViewModel? = null,
    onBack: () -> Unit = {},
) {
    var selectedProvider by rememberSaveable { mutableStateOf<Provider?>(null) }
    val importEnabled by viewModel?.importEnabled?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(Provider.entries.toSet()) }
    val diagnostics by viewModel?.providerDiagnostics?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) }
    val importing by viewModel?.syncingProvider?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(syncingProvider) }
    val categoryAutomation by viewModel?.categoryAutomation?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val categoryProgress by viewModel?.categoryProgress?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(net.wastu.artha.data.analysis.CategoryProgress()) }
    val importBusy by viewModel?.refreshing?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val automaticImport by viewModel?.automaticImportActive?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val requestedProvider by viewModel?.requestedImportProvider?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<Provider?>(null) }
    val importFeedback by viewModel?.importFeedback?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val coverage by viewModel?.providerCoverage?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap<Provider, SourceCoverage>()) }
    val gojekFlash by viewModel?.gojekFlash?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<GojekFlashRecord?>(null) }
    val allTransactions by viewModel?.allTransactions?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList()) }

    val anyImportRunning = importBusy || importing != null || requestedProvider != null
    fun isImporting(provider: Provider) = importBusy && (importing == provider || requestedProvider == provider)
    fun feedback(provider: Provider) = importFeedback?.takeIf { it.provider == null || it.provider == provider }?.message
    val syncAction: (Provider) -> Unit = { target ->
        if (target == Provider.GOJEK) viewModel?.requestGojekRefresh() else viewModel?.syncProvider(target)
    }

    BoxWithConstraints(Modifier.widthIn(max = 1320.dp).fillMaxSize()) {
        val expanded = maxWidth >= 840.dp && maxHeight >= 480.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxSize()) {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (anyImportRunning) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(16.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { viewModel?.refresh() }, enabled = viewModel != null) { Text("Sync all") }
                    }
                },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(Provider.entries, key = { it.name }) { provider ->
                    val diagnostic = diagnostics[provider]
                    val isProviderImportEnabled = provider in importEnabled
                    val isProviderImporting = isImporting(provider)
                    val isProblem = isSourceProblem(diagnostic, isProviderImportEnabled, isProviderImporting)
                    val statusText = sourceRowStatus(provider, diagnostic, isProviderImportEnabled, isProviderImporting)

                    Surface(onClick = { selectedProvider = provider }) {
                        ListItem(
                            headlineContent = { Text(provider.displayName) },
                            supportingContent = {
                                Text(
                                    text = statusText,
                                    color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Image(
                                    painter = painterResource(provider.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    contentScale = ContentScale.Crop,
                                )
                            },
                            trailingContent = {
                                if (isProblem) {
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                    )
                                }
                            },
                        )
                    }
                }

            }
            }

            if (expanded) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val provider = selectedProvider ?: Provider.entries.first()
                    SourcePage(
                        provider = provider,
                        enabled = provider in enabled,
                        importEnabled = provider in importEnabled,
                        diagnostic = diagnostics[provider],
                        coverage = coverage[provider],
                        importing = isImporting(provider),
                        allTransactions = allTransactions,
                        providerCounts = providerCounts,
                        onEnabled = { onEnabled(provider, it) },
                        onImportEnabled = { viewModel?.setImportEnabled(provider, it) },
                        onImport = { syncAction(provider) },
                        busy = importBusy && !automaticImport,
                        feedback = feedback(provider),
                        flash = gojekFlash.takeIf { provider == Provider.GOJEK },
                        onBack = null,
                    )
                }
            }
        }

        if (!expanded) {
            BackHandler(enabled = selectedProvider != null) { selectedProvider = null }
        }
        selectedProvider?.takeIf { !expanded }?.let { provider ->
            SourcePage(
                provider = provider,
                enabled = provider in enabled,
                importEnabled = provider in importEnabled,
                diagnostic = diagnostics[provider],
                coverage = coverage[provider],
                importing = isImporting(provider),
                allTransactions = allTransactions,
                providerCounts = providerCounts,
                onEnabled = { onEnabled(provider, it) },
                onImportEnabled = { viewModel?.setImportEnabled(provider, it) },
                onImport = { syncAction(provider) },
                busy = importBusy && !automaticImport,
                feedback = feedback(provider),
                flash = gojekFlash.takeIf { provider == Provider.GOJEK },
                onBack = { selectedProvider = null },
            )
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePage(
    provider: Provider,
    enabled: Boolean,
    importEnabled: Boolean,
    diagnostic: ProviderDiagnostic?,
    coverage: SourceCoverage?,
    importing: Boolean,
    allTransactions: List<UnifiedTransaction>,
    providerCounts: Map<Provider, Int> = emptyMap(),
    onEnabled: (Boolean) -> Unit,
    onImportEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    busy: Boolean = false,
    feedback: String? = null,
    flash: GojekFlashRecord? = null,
    onBack: (() -> Unit)? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedback) {
        if (!feedback.isNullOrBlank()) {
            snackbarHostState.showSnackbar(feedback)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(provider.iconRes),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(MaterialTheme.shapes.extraSmall),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = provider.displayName,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        SyncHeaderAction(
                            importing = importing,
                            enabled = importEnabled && !busy,
                            onSync = onImport,
                        )
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                SourcePageContent(
                    provider = provider,
                    enabled = enabled,
                    importEnabled = importEnabled,
                    diagnostic = diagnostic,
                    coverage = coverage,
                    importing = importing,
                    allTransactions = allTransactions,
                    providerCounts = providerCounts,
                    onEnabled = onEnabled,
                    onImportEnabled = onImportEnabled,
                    onImport = onImport,
                    flash = flash,
                )
            }
        }
    }

}

@Composable
private fun SourcePageContent(
    provider: Provider,
    enabled: Boolean,
    importEnabled: Boolean,
    diagnostic: ProviderDiagnostic?,
    coverage: SourceCoverage?,
    importing: Boolean,
    allTransactions: List<UnifiedTransaction>,
    providerCounts: Map<Provider, Int> = emptyMap(),
    onEnabled: (Boolean) -> Unit,
    onImportEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    flash: GojekFlashRecord? = null,
) {
    val context = LocalContext.current
    val isProblem = isSourceProblem(diagnostic, importEnabled, importing)
    val status = diagnostic?.accountStatus

    // Status (first, no card)
    ListItem(
        headlineContent = {
            Text(
                text = sourceHeadline(provider, diagnostic, importEnabled, importing),
                color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            val supportingText = if (diagnostic?.lastSyncEpochMillis != null) {
                "Last synced ${formatRelativeTime(diagnostic.lastSyncEpochMillis)}"
            } else {
                "Not synced yet"
            }
            Text(supportingText)
        },
    )

    if (isProblem) {
        if (status == AccountStatus.ERROR && !diagnostic?.lastError.isNullOrBlank()) {
            Text(
                text = diagnostic!!.lastError!!,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
            if (status == AccountStatus.ERROR) {
                FilledTonalButton(onClick = onImport) {
                    Text("Retry")
                }
            } else {
                FilledTonalButton(onClick = {
                    val pm = context.packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(provider.appPackage)
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    } else {
                        val playStoreIntent = Intent(
                            Intent.ACTION_VIEW,
                            "market://details?id=${provider.appPackage}".toUri(),
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { context.startActivity(playStoreIntent) }
                            .onFailure {
                                val webIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    "https://play.google.com/store/apps/details?id=${provider.appPackage}".toUri(),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                runCatching { context.startActivity(webIntent) }
                            }
                    }
                }) {
                    Text("Open ${provider.displayName}")
                }
            }
        }
    }

    // SettingsHeading "History"
    SettingsHeading("History")

    val expenseCount = remember(allTransactions, provider, providerCounts) {
        if (allTransactions.isNotEmpty()) {
            countExpenses(allTransactions, provider)
        } else {
            providerCounts[provider] ?: 0
        }
    }
    ListItem(
        headlineContent = { Text("Expenses") },
        trailingContent = { Text(expenseCount.toString()) },
    )

    val coversText = remember(coverage) {
        val early = coverage?.earliest
        val late = coverage?.latest
        when {
            early != null && late != null -> {
                val e = formatSavedDay(early)
                val l = formatSavedDay(late)
                if (e == l) e else "$e to $l"
            }
            late != null -> formatSavedDay(late)
            early != null -> formatSavedDay(early)
            else -> null
        }
    }
    if (coversText != null) {
        ListItem(
            headlineContent = { Text("Covers") },
            supportingContent = { Text(coversText) },
        )
    }

    diagnostic?.coverageHint?.takeIf { it.isNotBlank() }?.let { hint ->
        ListItem(
            headlineContent = { Text("What Artha reads") },
            supportingContent = { Text(hint, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    }

    lastSyncText(diagnostic?.lastSyncSummary)?.let { syncSummary ->
        ListItem(
            headlineContent = { Text("Last sync") },
            supportingContent = { Text(syncSummary) },
        )
    }

    diagnostic?.accountIdentifier?.takeIf { it.isNotBlank() }?.let { accountId ->
        ListItem(
            headlineContent = { Text("Account") },
            supportingContent = { Text(accountId) },
        )
    }

    // GOJEK only, SettingsHeading "Gojek"
    if (provider == Provider.GOJEK) {
        SettingsHeading("Gojek")
        val backgroundRefreshText = flash?.millis?.let { ms ->
            val stamp = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)
                .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
            "Last run $stamp"
        } ?: "Not run yet"
        ListItem(
            headlineContent = { Text("Background refresh") },
            supportingContent = { Text(backgroundRefreshText) },
        )
    }

    // SettingsHeading "Settings"
    SettingsHeading("Settings")
    SettingsSwitch(
        title = "Sync new payments",
        checked = importEnabled,
        onChange = onImportEnabled,
        sourceName = provider.displayName,
    )
    SettingsSwitch(
        title = "Show in Expenses",
        checked = enabled,
        onChange = onEnabled,
        sourceName = provider.displayName,
    )
}

@Composable
private fun SyncHeaderAction(importing: Boolean, enabled: Boolean, onSync: () -> Unit) {
    IconButton(onClick = onSync, enabled = enabled && !importing) {
        if (importing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Sync, contentDescription = "Sync history")
        }
    }
}

@Composable
internal fun SettingsHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .semantics { heading() }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SettingsLink(
    title: String,
    summary: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = { Icon(icon, null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        )
    }
}

@Composable
internal fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    sourceName: String? = null,
) {
    ListItem(
        modifier = Modifier
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics { if (sourceName != null) contentDescription = "$sourceName, $title" },
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

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
