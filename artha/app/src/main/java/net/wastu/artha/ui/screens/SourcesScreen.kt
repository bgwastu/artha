package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.iconRes
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.attention.forSource
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
                            val providerImporting = isImporting(provider)
                            val syncProblem = isSourceProblem(diagnostic, provider in importEnabled, providerImporting)
                            val needsAttention = attention.forSource(provider).firstOrNull()
                            // A sync problem or a running import says more than a setting to fix.
                            val statusText = needsAttention?.takeUnless { syncProblem || providerImporting }?.title
                                ?: sourceRowStatus(provider, diagnostic, provider in importEnabled, providerImporting)
                            SourceListRow(
                                provider = provider,
                                status = statusText,
                                problem = syncProblem || needsAttention != null,
                                onClick = { selectedProvider = provider },
                                onRetry = { syncAction(provider) }
                                    .takeIf { canRetrySync(diagnostic, provider in importEnabled, providerImporting) },
                                retryEnabled = !(importBusy && !automaticImport),
                            )
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
private fun SourceListRow(
    provider: Provider,
    status: String,
    problem: Boolean,
    onClick: () -> Unit,
    onRetry: (() -> Unit)?,
    retryEnabled: Boolean,
) {
    Surface(onClick = onClick) {
        ListItem(
            headlineContent = { Text(provider.displayName) },
            supportingContent = {
                Text(
                    text = status,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingContent = {
                Image(
                    painter = painterResource(provider.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
            },
            trailingContent = {
                if (onRetry != null) {
                    TextButton(onClick = onRetry, enabled = retryEnabled) { Text("Retry") }
                } else if (problem) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                } else {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            },
        )
    }
}
