package net.wastu.artha.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Image
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AiAnalysisService
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.ui.theme.incomeColor
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.data.export.LedgerExportStorage
import net.wastu.artha.ui.ArthaViewModel

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import net.wastu.artha.ui.SourceCoverage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    enabled: Set<Provider> = Provider.entries.toSet(),
    syncingProvider: Provider? = null,
    providerCounts: Map<Provider, Int> = emptyMap(),
    onEnabled: (Provider, Boolean) -> Unit = { _, _ -> },
    viewModel: ArthaViewModel? = null,
    onOpenDemo: (() -> Unit)? = null,
) {
    var selectedProvider by rememberSaveable { mutableStateOf<Provider?>(null) }
    var showAnalysisSettings by rememberSaveable { mutableStateOf(false) }
    var showContext by rememberSaveable { mutableStateOf(false) }
    var showWidgetHelp by rememberSaveable { mutableStateOf(false) }
    var savingAi by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importingFile by remember { mutableStateOf(false) }
    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val vm = viewModel ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importingFile = true
            try {
                vm.importExportFile(context, uri)
                Toast.makeText(context, "Import saved", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
            } finally {
                importingFile = false
            }
        }
    }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val vm = viewModel ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            try {
                val body = vm.buildExportJsonl()
                LedgerExportStorage.writeToUri(context, uri, body)
                Toast.makeText(context, "Export saved", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
            } finally {
                exporting = false
            }
        }
    }

    val snapshot by viewModel?.analysisSnapshot?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val importEnabled by viewModel?.importEnabled?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(Provider.entries.toSet()) }
    val diagnostics by viewModel?.providerDiagnostics?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) }
    val importing by viewModel?.syncingProvider?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(syncingProvider) }
    val categoryAutomation by viewModel?.categoryAutomation?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<net.wastu.artha.data.analysis.CategoryAutomationState?>(null) }
    val importBusy by viewModel?.refreshing?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val automaticImport by viewModel?.automaticImportActive?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val requestedProvider by viewModel?.requestedImportProvider?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<Provider?>(null) }
    val importFeedback by viewModel?.importFeedback?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val coverage by viewModel?.providerCoverage?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap<Provider, SourceCoverage>()) }
    fun isImporting(provider: Provider) = importBusy && (importing == provider || requestedProvider == provider)
    fun feedback(provider: Provider) = importFeedback?.takeIf { it.provider == null || it.provider == provider }?.message
    BoxWithConstraints(Modifier.widthIn(max = 1320.dp).fillMaxSize()) {
    val expanded = maxWidth >= 840.dp && maxHeight >= 480.dp
    Row(Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.weight(1f).widthIn(max = 600.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SettingsHeading("Sources") }
        items(Provider.entries, key = { it.name }) { provider ->
            Surface(onClick = { selectedProvider = provider }) {
                ListItem(
                    headlineContent = { Text(provider.displayName) },
                    supportingContent = {
                        Column {
                            Text(sourceStateLine(diagnostics[provider], coverage[provider],
                                providerCounts[provider] ?: 0, provider in importEnabled, importing == provider))
                            sourceRowHint(diagnostics[provider])?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    leadingContent = {
                        Image(painterResource(provider.iconRes), null,
                            Modifier.size(40.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceStatePill(diagnostics[provider], provider in importEnabled, importing == provider)
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    },
                )
            }
        }
        if (viewModel != null) {
            item { SettingsHeading("Analysis") }
            item {
                val enabled = snapshot?.analysisEnabled == true
                val config = snapshot?.aiConfig
                val filled = config != null && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()
                val summary = when {
                    !enabled -> "Off"
                    !filled -> "On - not configured"
                    else -> "On"
                }
                SettingsLink("Analysis", summary, Icons.Default.SettingsSuggest) {
                    aiError = null
                    showAnalysisSettings = true
                }
            }
            item { SettingsLink("Personal context", null, Icons.Default.EditNote) { showContext = true } }
            item {
                SettingsLink(
                    "Export transactions",
                    if (exporting) "Exporting..." else null,
                    Icons.Default.FileDownload,
                ) {
                    if (exporting) return@SettingsLink
                    exportDocument.launch(viewModel.suggestedExportFileName())
                }
            }
            item {
                SettingsLink(
                    "Import transactions",
                    if (importingFile) "Importing..." else null,
                    Icons.Default.FileUpload,
                ) {
                    if (importingFile) return@SettingsLink
                    importDocument.launch(arrayOf("application/json", "application/*", "text/*"))
                }
            }
        }
        item { SettingsLink("Widgets", null, Icons.Default.Widgets) { showWidgetHelp = true } }
        if (onOpenDemo != null) item { SettingsLink("Demo mode", "Explore sample data", Icons.Default.PlayArrow, onOpenDemo) }
    }

    if (expanded) {
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState())) {
            val provider = selectedProvider ?: Provider.entries.first()
            SourcePageContent(provider, provider in enabled, provider in importEnabled, providerCounts[provider] ?: 0,
                diagnostics[provider], coverage[provider], isImporting(provider),
                { onEnabled(provider, it) }, { viewModel?.setImportEnabled(provider, it) }, { viewModel?.syncProvider(provider) },
                busy = importBusy && !automaticImport, feedback = feedback(provider))
        }
    }
    }

    if (!expanded) {
        BackHandler(enabled = selectedProvider != null) { selectedProvider = null }
    }
    selectedProvider?.takeIf { !expanded }?.let { provider ->
        SourcePage(provider, provider in enabled, provider in importEnabled, providerCounts[provider] ?: 0,
            diagnostics[provider], coverage[provider], isImporting(provider),
            { onEnabled(provider, it) }, { viewModel?.setImportEnabled(provider, it) }, { viewModel?.syncProvider(provider) },
            busy = importBusy && !automaticImport, feedback = feedback(provider),
            onBack = { selectedProvider = null })
    }
    if (showWidgetHelp) {
        AlertDialog(onDismissRequest = { showWidgetHelp = false }, title = { Text("Widgets") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Touch and hold your home screen, choose Widgets, then Artha.")
                    Text("Analysis opens the month overview. Recent payments opens Transactions.")
                    Text("Touch and hold a widget to resize it. Use more space for larger text and complete amounts.")
                    Text("Widgets use saved payments. Check the update time when history is unavailable.")
                }
            },
            confirmButton = { TextButton(onClick = { showWidgetHelp = false }) { Text("Close") } })
    }
    if (showAnalysisSettings && snapshot != null && viewModel != null) {
        val analysisSnapshot = snapshot!!
        AnalysisSettingsSheet(
            aiConfig = analysisSnapshot.aiConfig,
            analysisEnabled = analysisSnapshot.analysisEnabled,
            customCategories = analysisSnapshot.customCategories,
            categoryIcons = analysisSnapshot.categoryIcons,
            automationState = categoryAutomation,
            savingAi = savingAi,
            aiError = aiError,
            onDismiss = { if (!savingAi) showAnalysisSettings = false },
            onAnalysisEnabled = viewModel::setAnalysisEnabled,
            onSaveAi = { config ->
                savingAi = true
                aiError = null
                viewModel.updateAiConfig(config) { error ->
                    savingAi = false
                    aiError = error
                }
            },
            onAddCategory = viewModel::addCustomCategory,
            onRemoveCategory = viewModel::removeCustomCategory,
            onSetCategoryIcon = viewModel::setCategoryIcon,
            onResetCategories = viewModel::resetCategoriesToDefault,
            onRetryAi = viewModel::retryAutomaticCategories,
        )
    }
    if (showContext && snapshot != null && viewModel != null) {
        KnowledgeEditorDialog(snapshot!!.knowledge, { showContext = false }) {
            viewModel.applyKnowledgeRewrite(it)
            showContext = false
        }
    }
    }
}

@Composable
private fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SettingsLink(title: String, summary: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick) {
        ListItem(headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceStatePill(diagnostic: ProviderDiagnostic?, importEnabled: Boolean, importing: Boolean) {
    val status = diagnostic?.accountStatus ?: AccountStatus.UNKNOWN
    val colors = when {
        importing -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        !importEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        status in PROBLEM_STATUSES -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        status == AccountStatus.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = colors.first, contentColor = colors.second, shape = MaterialTheme.shapes.small) {
        Text(sourceStatusLabel(status, importEnabled, importing),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

private val PROBLEM_STATUSES = setOf(
    AccountStatus.NEEDS_LOGIN, AccountStatus.ERROR,
    AccountStatus.RATE_LIMITED, AccountStatus.APP_NOT_INSTALLED,
)

/** One entry point for manual source sync; keeps the busy animation in one place. */
@Composable
private fun ImportNowButton(importing: Boolean, busy: Boolean, importEnabled: Boolean, onImport: () -> Unit) {
    FilledTonalButton(
        onClick = { if (!importing && !busy) onImport() },
        enabled = importEnabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).height(52.dp),
    ) {
        AnimatedContent(
            targetState = importing,
            label = "import_button_content",
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        ) { loading ->
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp)
                    Text("Syncing history...", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (busy) "Sync in progress" else "Sync history", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Body shared by the wide pane and the phone source page; ordered status → data → last import → settings. */
@Composable
private fun SourcePageContent(
    provider: Provider, enabled: Boolean, importEnabled: Boolean, count: Int,
    diagnostic: ProviderDiagnostic?, coverage: SourceCoverage?, importing: Boolean,
    onEnabled: (Boolean) -> Unit, onImportEnabled: (Boolean) -> Unit, onImport: () -> Unit,
    busy: Boolean = false, feedback: String? = null,
) {
    val isEnabled = enabled && importEnabled
    val status = diagnostic?.accountStatus ?: AccountStatus.UNKNOWN
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ImportNowButton(importing, busy, importEnabled, onImport)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceStatePill(diagnostic, importEnabled, importing)
            Text(sourceStateDescription(status, count > 0), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Text(sourceStateLine(diagnostic, coverage, count, importEnabled, importing),
            modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SettingsHeading("Data")
    ListItem(headlineContent = { Text("Saved payments") }, trailingContent = { Text(count.toString()) })
    coverage?.latest?.let { ListItem(headlineContent = { Text("Latest payment") }, trailingContent = { Text(formatSavedDay(it)) }) }
    coverage?.earliest?.takeIf { coverage.latest != null && it != coverage.latest }?.let {
        ListItem(headlineContent = { Text("Earliest payment") }, trailingContent = { Text(formatSavedDay(it)) })
    }
    diagnostic?.accountIdentifier?.takeIf { it.isNotBlank() }?.let {
        ListItem(headlineContent = { Text("Account") }, supportingContent = { Text(it) })
    }
    diagnostic?.coverageHint?.takeIf { it.isNotBlank() }?.let { hint ->
        Text(hint, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val hasImportDetails = diagnostic?.lastSyncSummary?.isNotBlank() == true || diagnostic?.lastImportWindow != null ||
        diagnostic?.lastError?.isNotBlank() == true
    if (hasImportDetails) {
        SettingsHeading("Last import")
        diagnostic?.lastImportWindow?.let { window ->
            ListItem(headlineContent = { Text("Payments read") }, trailingContent = { Text(window.paymentCount.toString()) })
            if (window.earliestMillis != null && window.latestMillis != null) {
                val first = formatSavedDay(java.time.Instant.ofEpochMilli(window.earliestMillis))
                val last = formatSavedDay(java.time.Instant.ofEpochMilli(window.latestMillis))
                ListItem(headlineContent = { Text("Import range") },
                    supportingContent = { Text(if (first == last) first else "$first to $last") })
            }
        }
        diagnostic?.lastSyncSummary?.takeIf { it.isNotBlank() }?.let {
            Text(it.replace(" \u00b7 ", ". "), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
        }
        diagnostic?.lastError?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }

    SettingsHeading("Settings")
    SettingsSwitch(
        title = "Enabled",
        checked = isEnabled,
        onChange = { checked -> onEnabled(checked); onImportEnabled(checked) },
        summary = if (isEnabled) "New payments import in every sync" else "Imports are paused; saved payments stay",
        sourceName = provider.displayName,
    )
    if (provider == Provider.GOJEK) {
        Text("This source imports what the Gojek app loads on screen. Open GoPay's transaction history there and scroll a little; the next sync picks it up.",
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    feedback?.let {
        Text(it, Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** Phone-sized source page: own header row because the shell's app bar already names the tab. */
@Composable
private fun SourcePage(
    provider: Provider, enabled: Boolean, importEnabled: Boolean, count: Int,
    diagnostic: ProviderDiagnostic?, coverage: SourceCoverage?, importing: Boolean,
    onEnabled: (Boolean) -> Unit, onImportEnabled: (Boolean) -> Unit, onImport: () -> Unit,
    busy: Boolean = false, feedback: String? = null, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(provider.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SourcePageContent(provider, enabled, importEnabled, count, diagnostic, coverage, importing,
                onEnabled, onImportEnabled, onImport, busy = busy, feedback = feedback)
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null, sourceName: String? = null) {
    ListItem(modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange).semantics { if (sourceName != null) contentDescription = "$sourceName, $title" },
        headlineContent = { Text(title) }, supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked, onCheckedChange = null) })
}

internal fun sourceStatusLabel(status: AccountStatus, importEnabled: Boolean, importing: Boolean): String = when {
    importing -> "Syncing"
    !importEnabled -> "Paused"
    else -> when (status) {
        AccountStatus.PARTIAL -> "Partial"
        AccountStatus.NEEDS_LOGIN -> "Sign in"
        AccountStatus.APP_NOT_INSTALLED -> "No app"
        AccountStatus.RATE_LIMITED -> "Limited"
        AccountStatus.ERROR -> "Error"
        AccountStatus.UNKNOWN -> "New"
        else -> "Connected"
    }
}

/** Sentence state description for the source page; problems carry their recovery step. */
internal fun sourceStateDescription(status: AccountStatus, hasSavedRows: Boolean): String = when (status) {
    AccountStatus.PARTIAL -> "This import captured only what the source offers; the details below say what stayed out."
    AccountStatus.NEEDS_LOGIN -> "Sign in in the source app, then import again."
    AccountStatus.APP_NOT_INSTALLED -> "Install the source app to import its history."
    AccountStatus.RATE_LIMITED -> "The source is limiting imports. Wait a little, then import again."
    AccountStatus.ERROR -> "The last import failed. The details below say why."
    else -> if (hasSavedRows) "Imports are healthy. New payments appear after each sync." else "No history has been imported yet."
}

/** Row-level state summary: data freshness first, then sync recency. */
internal fun sourceStateLine(
    diagnostic: ProviderDiagnostic?,
    coverage: SourceCoverage?,
    count: Int,
    importEnabled: Boolean,
    importing: Boolean,
): String = listOfNotNull(
    when {
        importing -> "Checking for new payments"
        !importEnabled -> "Import paused"
        coverage?.latest != null -> "Payments to ${formatSavedDay(coverage.latest)}"
        count > 0 -> null
        else -> "No saved payments yet"
    },
    diagnostic?.lastSyncEpochMillis?.takeIf { !importing }?.let { "synced ${it.formatRelativeTime()}" },
).joinToString(" · ").ifBlank { "Not imported yet" }

/** Short recovery hint under problem rows; gets its own line so states stay scannable. */
internal fun sourceRowHint(diagnostic: ProviderDiagnostic?): String? = when (diagnostic?.accountStatus) {
    AccountStatus.NEEDS_LOGIN -> "Open the source app, sign in, then import again"
    AccountStatus.APP_NOT_INSTALLED -> "Install the source app to import"
    AccountStatus.ERROR -> diagnostic.lastError?.substringBefore(". ")?.takeIf(String::isNotBlank)
    else -> null
}

private fun Long.formatRelativeTime(): String {
    val diffSec = ((System.currentTimeMillis() - this) / 1000).coerceAtLeast(0)
    val diffMin = diffSec / 60
    val diffHours = diffMin / 60
    val diffDays = diffHours / 24
    return when {
        diffSec < 60 -> "just now"
        diffMin < 60 -> "$diffMin min ago"
        diffHours < 24 -> "$diffHours hours ago"
        diffDays == 1L -> "yesterday"
        else -> "$diffDays days ago"
    }
}

internal fun formatSavedDay(instant: java.time.Instant, zone: java.time.ZoneId = net.wastu.artha.core.model.AccountingTime.zone): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US).withZone(zone).format(instant)




@Composable
fun AiConfigDialog(currentConfig: AiConfig, onDismiss: () -> Unit, onSave: (AiConfig) -> Unit,
    saving: Boolean = false, error: String? = null,
) {
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var model by remember { mutableStateOf(currentConfig.model) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val feedbackRequester = remember { BringIntoViewRequester() }
    fun draft() = currentConfig.copy(baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim())
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("AI") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Automatically categorizes payments and writes summaries. Sends payment details and personal context to this provider", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(baseUrl, { baseUrl = it; validationError = null; testResult = null }, label = { Text("Endpoint URL") },
                    singleLine = true, enabled = !saving && !testing, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it; validationError = null; testResult = null }, label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !saving && !testing, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it; validationError = null; testResult = null }, label = { Text("Model") },
                    singleLine = true, enabled = !saving && !testing, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !saving && !testing, onClick = {
                    val config = draft()
                    validationError = aiConfigValidation(config)
                    testResult = null
                    if (validationError == null) {
                        testing = true
                        scope.launch {
                            try {
                                AiAnalysisService.testConnection(config)
                                testSucceeded = true; testResult = "Connected"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { testSucceeded = false; testResult = aiConnectionFailure(failure) }
                            finally { testing = false }
                        }
                    }
                }) { Text(if (testing) "Testing" else "Test connection") }
                if (testResult != null || validationError != null || error != null) {
                    Column(Modifier.bringIntoViewRequester(feedbackRequester)) {
                        testResult?.let { Text(it, color = if (testSucceeded) incomeColor() else MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        (validationError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    }
                    LaunchedEffect(testResult, validationError, error) {
                        feedbackRequester.bringIntoView()
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(enabled = !saving && !testing, onClick = {
                val config = draft()
                validationError = aiConfigValidation(config)
                if (validationError == null) onSave(config)
            }) { Text(if (saving) "Saving" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } })
}
