package net.wastu.artha.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.iconRes
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.attention.AttentionAction
import net.wastu.artha.data.attention.forSource
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.GojekFlashRecord
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.SourceCoverage
import net.wastu.artha.ui.components.PageScaffold
import net.wastu.artha.ui.components.collectOrDefault

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
    val importEnabled by viewModel?.importEnabled.collectOrDefault(Provider.entries.toSet())
    val diagnostics by viewModel?.providerDiagnostics.collectOrDefault(emptyMap())
    val importing by viewModel?.syncingProvider.collectOrDefault(syncingProvider)
    val categoryAutomation by viewModel?.categoryAutomation.collectOrDefault(null)
    val categoryProgress by viewModel?.categoryProgress.collectOrDefault(CategoryProgress())
    val importBusy by viewModel?.refreshing.collectOrDefault(false)
    val automaticImport by viewModel?.automaticImportActive.collectOrDefault(false)
    val requestedProvider by viewModel?.requestedImportProvider.collectOrDefault(null)
    val importFeedback by viewModel?.importFeedback.collectOrDefault(null)
    val coverage by viewModel?.providerCoverage.collectOrDefault(emptyMap<Provider, SourceCoverage>())
    val gojekFlash by viewModel?.gojekFlash.collectOrDefault(null)
    val allTransactions by viewModel?.allTransactions.collectOrDefault(emptyList())
    val attention = rememberAttentionItems()

    val anyImportRunning = importBusy || importing != null || requestedProvider != null
    fun isImporting(provider: Provider) = importBusy && (importing == provider || requestedProvider == provider)
    fun feedback(provider: Provider) = importFeedback?.takeIf { it.provider == null || it.provider == provider }?.message
    val syncAction: (Provider) -> Unit = { target ->
        if (target == Provider.GOJEK) viewModel?.requestGojekRefresh() else viewModel?.syncProvider(target)
    }

    @Composable
    fun detailPage(provider: Provider, onBackFromPage: (() -> Unit)?) = SourcePage(
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
        onBack = onBackFromPage,
    )

    BoxWithConstraints(Modifier.widthIn(max = 1320.dp).fillMaxSize()) {
        val expanded = maxWidth >= 840.dp && maxHeight >= 480.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxSize()) {
            PageScaffold(
                title = "Sources",
                onBack = onBack,
                actions = {
                    if (anyImportRunning) {
                        LoadingIndicator(Modifier.padding(end = 12.dp).size(28.dp).semantics { contentDescription = "Syncing" })
                    } else {
                        TextButton(onClick = { viewModel?.refresh() }, enabled = viewModel != null) { Text("Sync all") }
                    }
                },
            ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(Provider.entries, key = { it.name }) { provider ->
                    val diagnostic = diagnostics[provider]
                    val isProviderImportEnabled = provider in importEnabled
                    val isProviderImporting = isImporting(provider)
                    val syncProblem = isSourceProblem(diagnostic, isProviderImportEnabled, isProviderImporting)
                    val needsAttention = attention.forSource(provider).firstOrNull()
                    val isProblem = syncProblem || needsAttention != null
                    // A sync problem or a running import says more than a setting to fix.
                    val statusText = needsAttention?.takeUnless { syncProblem || isProviderImporting }?.title
                        ?: sourceRowStatus(provider, diagnostic, isProviderImportEnabled, isProviderImporting)

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
            }

            if (expanded) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    detailPage(selectedProvider ?: Provider.entries.first(), null)
                }
            }
        }

        if (!expanded) {
            BackHandler(enabled = selectedProvider != null) { selectedProvider = null }
            selectedProvider?.let { provider -> detailPage(provider) { selectedProvider = null } }
        }
    }
}

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
        PageScaffold(
            title = provider.displayName,
            onBack = onBack,
            snackbarHost = snackbarHostState,
            titleIcon = {
                Image(
                    painter = painterResource(provider.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(MaterialTheme.shapes.extraSmall),
                    contentScale = ContentScale.Crop,
                )
            },
            actions = {
                SyncHeaderAction(
                    importing = importing,
                    enabled = importEnabled && !busy,
                    onSync = onImport,
                )
            },
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
    var pinDialogOpen by remember { mutableStateOf(false) }

    AttentionCards(rememberAttentionItems().forSource(provider)) { action ->
        when (action) {
            AttentionAction.SET_JAGO_PIN -> pinDialogOpen = true
        }
    }

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
    if (status == AccountStatus.PARTIAL && !isProblem && !diagnostic?.lastError.isNullOrBlank()) {
        Text(
            text = diagnostic!!.lastError!!,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

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
        if (allTransactions.isNotEmpty()) countExpenses(allTransactions, provider) else providerCounts[provider] ?: 0
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

    if (provider == Provider.JAGO) JagoPinSetting(dialogOpen = pinDialogOpen, onDialogOpenChange = { pinDialogOpen = it })
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
            LoadingIndicator(Modifier.size(20.dp),)
        } else {
            Icon(Icons.Default.Sync, contentDescription = "Sync history")
        }
    }
}
