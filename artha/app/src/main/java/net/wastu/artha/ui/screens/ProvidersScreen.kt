package net.wastu.artha.ui.screens

import android.accounts.AccountManager
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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Widgets
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
import net.wastu.artha.data.sync.CloudSyncStatus
import net.wastu.artha.data.sync.DriveSyncState
import net.wastu.artha.ui.ArthaViewModel

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    openBackup: Boolean = false,
    onBackupOpened: () -> Unit = {},
    enabled: Set<Provider> = Provider.entries.toSet(),
    syncingProvider: Provider? = null,
    providerCounts: Map<Provider, Int> = emptyMap(),
    onEnabled: (Provider, Boolean) -> Unit = { _, _ -> },
    viewModel: ArthaViewModel? = null,
    onOpenDemo: (() -> Unit)? = null,
) {
    var selectedProvider by rememberSaveable { mutableStateOf<Provider?>(null) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openBackup) { if (openBackup) { showBackup = true; onBackupOpened() } }
    var showAiConfig by rememberSaveable { mutableStateOf(false) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    var showContext by rememberSaveable { mutableStateOf(false) }
    var showWidgetHelp by rememberSaveable { mutableStateOf(false) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var restoringBackup by remember { mutableStateOf(false) }
    var savingAi by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

    val snapshot by viewModel?.analysisSnapshot?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val importEnabled by viewModel?.importEnabled?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(Provider.entries.toSet()) }
    val diagnostics by viewModel?.providerDiagnostics?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) }
    val importing by viewModel?.syncingProvider?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(syncingProvider) }
    val categoryAutomation by viewModel?.categoryAutomation?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<net.wastu.artha.data.analysis.CategoryAutomationState?>(null) }
    val importBusy by viewModel?.refreshing?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val automaticImport by viewModel?.automaticImportActive?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val requestedProvider by viewModel?.requestedImportProvider?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<Provider?>(null) }
    val importFeedback by viewModel?.importFeedback?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    fun isImporting(provider: Provider) = importBusy && (importing == provider || requestedProvider == provider)
    fun feedback(provider: Provider) = importFeedback?.takeIf { it.provider == null || it.provider == provider }?.message
    val backup by viewModel?.driveSyncState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(DriveSyncState()) }

    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() }?.let {
            viewModel?.setDriveAccountEmail(it)
        }
        showBackup = true
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (restoringBackup) viewModel?.restoreDriveBackup() else viewModel?.syncDriveNow()
        }
        showBackup = true
    }

    val backupContent: @Composable () -> Unit = {
        if (viewModel != null) BackupSettings(
                backup, restoringBackup,
                onEnabled = viewModel::setDriveSyncEnabled,
                onAccount = {
                    showBackup = false
                    val intent = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
                    runCatching { accountPicker.launch(intent) }.onFailure { showAccountDialog = true }
                },
                onPassword = { showBackup = false; showPasswordDialog = true },
                onBackup = {
                    restoringBackup = false
                    if (backup.status == CloudSyncStatus.AUTH_REQUIRED && backup.consentIntent != null) {
                        showBackup = false
                        consent.launch(backup.consentIntent!!)
                    } else viewModel.syncDriveNow()
                },
                onRestore = { showBackup = false; showRestoreConfirmation = true },
            )
    }
    BoxWithConstraints(Modifier.widthIn(max = 1320.dp).fillMaxSize()) {
    val expanded = maxWidth >= 840.dp && maxHeight >= 480.dp
    Row(Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.weight(1f).widthIn(max = 600.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SettingsHeading("Sources") }
        items(Provider.entries, key = { it.name }) { provider ->
            Surface(onClick = { selectedProvider = provider; showBackup = false }) {
                ListItem(
                    headlineContent = { Text(provider.displayName) },
                    supportingContent = {
                        Text(providerSummary(diagnostics[provider], providerCounts[provider] ?: 0,
                            provider in importEnabled, importing == provider))
                    },
                    leadingContent = {
                        Image(painterResource(provider.iconRes), null,
                            Modifier.size(40.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                )
            }
        }
        if (viewModel != null) {
            item { SettingsHeading("Backup") }
            item {
                SettingsLink("Google Drive", backupSummary(backup, restoringBackup), Icons.Default.CloudSync) { showBackup = true }
            }
            item { SettingsHeading("Analysis") }
            item {
                val config = snapshot?.aiConfig
                val filled = config != null && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()
                val validUrl = config != null && runCatching { AiAnalysisService.endpoint(config.baseUrl) }.isSuccess
                val summary = if (!filled) "Not set up" else if (!validUrl) "Check URL" else "Configured"
                SettingsLink("AI", summary, Icons.Default.SettingsSuggest) {
                    aiError = null
                    showAiConfig = true
                }
            }
            item { SettingsLink("Categories", null, Icons.Default.Category) { showCategories = true } }
            item { SettingsLink("Personal context", null, Icons.Default.EditNote) { showContext = true } }
        }
        item { SettingsLink("Widgets", null, Icons.Default.Widgets) { showWidgetHelp = true } }
        if (onOpenDemo != null) item { SettingsLink("Demo mode", "Explore sample data", Icons.Default.PlayArrow, onOpenDemo) }
    }

    if (expanded) {
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (showBackup && viewModel != null) {
                Text("Google Drive", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                backupContent()
            } else {
                val provider = selectedProvider ?: Provider.entries.first()
                Text(provider.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                ProviderSettings(provider.displayName, provider in enabled, provider in importEnabled,
                    providerCounts[provider] ?: 0, diagnostics[provider], isImporting(provider),
                    { onEnabled(provider, it) }, { viewModel?.setImportEnabled(provider, it) }, { viewModel?.syncProvider(provider) }, busy = importBusy && !automaticImport, feedback = feedback(provider))
            }
        }
    }
    }

    selectedProvider?.takeIf { !expanded }?.let { provider ->
        SettingsDetailSheet(provider.displayName, { selectedProvider = null }) {
            ProviderSettings(
                sourceName = provider.displayName, enabled = provider in enabled, importEnabled = provider in importEnabled,
                count = providerCounts[provider] ?: 0, diagnostic = diagnostics[provider],
                importing = isImporting(provider),
                busy = importBusy && !automaticImport, feedback = feedback(provider),
                onEnabled = { onEnabled(provider, it) },
                onImportEnabled = { viewModel?.setImportEnabled(provider, it) },
                onImport = { viewModel?.syncProvider(provider) },
            )
        }
    }
    if (showBackup && viewModel != null && !expanded) {
        SettingsDetailSheet("Google Drive", { showBackup = false }) {
            backupContent()
        }
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
    if (showAiConfig && snapshot != null && viewModel != null) {
        AiConfigDialog(snapshot!!.aiConfig,
            onDismiss = { if (!savingAi) showAiConfig = false },
            saving = savingAi, error = aiError,
            onSave = { config ->
                savingAi = true
                aiError = null
                viewModel.updateAiConfig(config) { error ->
                    savingAi = false
                    aiError = error
                    if (error == null) showAiConfig = false
                }
            })
    }
    if (showCategories && snapshot != null && viewModel != null) {
        ManageCategoriesSheet(snapshot!!.customCategories, snapshot!!.categoryIcons,
            { showCategories = false }, viewModel::addCustomCategory, viewModel::removeCustomCategory,
            viewModel::setCategoryIcon, viewModel::resetCategoriesToDefault,
            automationState = categoryAutomation, onRetryAi = viewModel::retryAutomaticCategories)
    }
    if (showContext && snapshot != null && viewModel != null) {
        KnowledgeEditorDialog(snapshot!!.knowledge, { showContext = false }) {
            viewModel.applyKnowledgeRewrite(it)
            showContext = false
        }
    }
    if (showPasswordDialog && viewModel != null) {
        SyncPasswordDialog({ showPasswordDialog = false; showBackup = true }) {
            viewModel.setDriveEncryptionPassword(it)
            showPasswordDialog = false
            showBackup = true
        }
    }
    if (showAccountDialog && viewModel != null) {
        AccountEmailDialog(backup.accountEmail.orEmpty(), { showAccountDialog = false; showBackup = true }) {
            viewModel.setDriveAccountEmail(it)
            showAccountDialog = false
            showBackup = true
        }
    }
    if (showRestoreConfirmation && viewModel != null) {
        AlertDialog(onDismissRequest = { showRestoreConfirmation = false; showBackup = true },
            title = { Text("Restore backup?") },
            text = { Text("Add missing history and restore notes. Existing transactions, chosen categories and this phone’s AI credentials are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirmation = false
                    restoringBackup = true
                    if (backup.consentIntent != null) consent.launch(backup.consentIntent!!)
                    else { showBackup = true; viewModel.restoreDriveBackup() }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirmation = false; showBackup = true }) { Text("Cancel") } })
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
private fun SettingsDetailSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null, sourceName: String? = null) {
    ListItem(modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange).semantics { if (sourceName != null) contentDescription = "$sourceName, $title" },
        headlineContent = { Text(title) }, supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked, onCheckedChange = null) })
}

private fun providerSummary(diagnostic: ProviderDiagnostic?, count: Int, importEnabled: Boolean, importing: Boolean): String = when {
    importing -> "Importing"
    !importEnabled -> "Import paused"
    else -> when (diagnostic?.accountStatus ?: AccountStatus.UNKNOWN) {
        AccountStatus.PARTIAL -> "Partial import"
        AccountStatus.CONNECTED -> diagnostic?.lastSyncEpochMillis?.let { "Imported ${formatRelativeTime(it)}" }
            ?: if (count > 0) "$count saved transactions" else "No saved history"
        AccountStatus.NEEDS_LOGIN -> "Sign in required"
        AccountStatus.APP_NOT_INSTALLED -> "App not installed"
        AccountStatus.ERROR -> "Import failed"
        AccountStatus.UNKNOWN -> if (count > 0) "$count saved transactions" else "Not imported"
    }
}

@Composable
private fun ProviderSettings(
    sourceName: String, enabled: Boolean, importEnabled: Boolean, count: Int, diagnostic: ProviderDiagnostic?, importing: Boolean,
    onEnabled: (Boolean) -> Unit, onImportEnabled: (Boolean) -> Unit, onImport: () -> Unit,
    busy: Boolean = false, feedback: String? = null,
) {
    val isEnabled = enabled && importEnabled
    SettingsSwitch(
        title = "Enabled",
        checked = isEnabled,
        onChange = { checked ->
            onEnabled(checked)
            onImportEnabled(checked)
        },
        summary = if (isEnabled) "Active and syncing transactions" else "Disabled",
        sourceName = sourceName
    )
    ListItem(headlineContent = { Text("Saved transactions") }, trailingContent = { Text(count.toString()) })
    diagnostic?.accountIdentifier?.let { ListItem(headlineContent = { Text("Account") }, supportingContent = { Text(it) }) }
    val status = providerSummary(diagnostic, count, importEnabled, importing)
    ListItem(headlineContent = { Text(status) }, supportingContent = diagnostic?.lastSyncEpochMillis
        ?.takeIf { diagnostic.accountStatus != AccountStatus.CONNECTED }?.let { { Text("Last imported ${formatRelativeTime(it)}") } })
    if (diagnostic?.accountStatus == AccountStatus.NEEDS_LOGIN) {
        Text("Sign in in the source app, then import again", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
    }
    FilledTonalButton(
        onClick = { if (!importing && !busy) onImport() },
        enabled = importEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        AnimatedContent(
            targetState = importing,
            label = "import_button_content",
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        ) { loading ->
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                    )
                    Text("Syncing history...", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (busy) "Sync in progress" else "Sync history",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
    feedback?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
    if (!diagnostic?.lastSyncSummary.isNullOrBlank() || !diagnostic?.coverageHint.isNullOrBlank() || diagnostic?.lastError != null || diagnostic?.lastImportWindow != null) {
        var showDiagnostics by remember { mutableStateOf(false) }
        TextButton(onClick = { showDiagnostics = !showDiagnostics }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (showDiagnostics) "Hide details" else "Import details")
        }
        if (showDiagnostics) {
            diagnostic?.lastError?.let { Text(it,
                modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
            diagnostic?.coverageHint?.let { Text(it,
                modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            diagnostic?.lastImportWindow?.let { window ->
                Text("${window.paymentCount} payments read in the last import", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                if (window.earliestMillis != null && window.latestMillis != null) {
                    val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US)
                        .withZone(net.wastu.artha.core.model.AccountingTime.zone)
                    val first = formatter.format(java.time.Instant.ofEpochMilli(window.earliestMillis))
                    val last = formatter.format(java.time.Instant.ofEpochMilli(window.latestMillis))
                    Text(if (first == last) "Payment dates: $first" else "Payment dates: $first to $last",
                        modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            diagnostic?.lastSyncSummary?.let { Text(it.replace(" \u00b7 ", ". "),
                modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun backupSummary(state: DriveSyncState, restoring: Boolean): String = when (state.status) {
    CloudSyncStatus.DISABLED -> "Off"
    CloudSyncStatus.NEEDS_ACCOUNT -> "Choose an account"
    CloudSyncStatus.NEEDS_PASSWORD -> "Set a password"
    CloudSyncStatus.READY -> if (state.lastSyncEpochMillis == null) "Ready" else "Last activity ${formatRelativeTime(state.lastSyncEpochMillis)}"
    CloudSyncStatus.SYNCING -> if (restoring) "Restoring" else "Backing up"
    CloudSyncStatus.WRONG_PASSWORD -> "Check password"
    CloudSyncStatus.AUTH_REQUIRED -> "Authorization required"
    CloudSyncStatus.ERROR -> if (restoring) "Restore failed" else "Backup failed"
}

@Composable
private fun BackupSettings(state: DriveSyncState, restoring: Boolean, onEnabled: (Boolean) -> Unit,
    onAccount: () -> Unit, onPassword: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit,
) {
    SettingsSwitch("Backup", state.isEnabled, onEnabled, "Back up after imports")
    SettingsLink("Account", state.accountEmail ?: "Choose an account", Icons.Default.CloudSync, onAccount)
    SettingsLink("Backup password", if (state.hasPassword) "Set" else "Not set", Icons.Default.Lock, onPassword)
    if (state.isEnabled) {
        Text(backupSummary(state, restoring), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
    val ready = state.isEnabled && state.hasPassword && !state.accountEmail.isNullOrBlank() && !state.isSyncing
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onBackup, enabled = ready, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.status == CloudSyncStatus.AUTH_REQUIRED) "Authorize backup" else "Back up now")
        }
        OutlinedButton(onClick = onRestore, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Restore backup") }
    }
    state.lastSyncSummary?.let { Text(it.replace(" \u00b7 ", ". "), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun SyncPasswordDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup password", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Keep this password to restore your encrypted backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val p = password.trim()
                    val c = confirmPassword.trim()
                    when {
                        p.length < 6 -> errorMessage = "Password must be at least 6 characters."
                        p != c -> errorMessage = "Passwords do not match."
                        else -> onSave(p)
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun AccountEmailDialog(
    currentEmail: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var email by remember { mutableStateOf(currentEmail) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google account", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Google account Email") },
                    placeholder = { Text("e.g. user@gmail.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (email.isNotBlank()) onSave(email.trim())
                },
                enabled = email.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatRelativeTime(epochMillis: Long): String {
    val diffMs = System.currentTimeMillis() - epochMillis
    val diffSec = (diffMs / 1000).coerceAtLeast(0)
    val diffMin = diffSec / 60
    val diffHours = diffMin / 60
    val diffDays = diffHours / 24
    return when {
        diffSec < 60 -> "Just now"
        diffMin < 60 -> "$diffMin min ago"
        diffHours < 24 -> "$diffHours hours ago"
        diffDays == 1L -> "Yesterday"
        else -> "$diffDays days ago"
    }
}

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
