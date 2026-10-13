package net.wastu.artha.ui.screens

import android.accounts.AccountManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.data.sync.CloudSyncStatus
import net.wastu.artha.data.sync.DriveSyncState
import net.wastu.artha.ui.ArthaViewModel

@Composable
fun ProvidersScreen(
    enabled: Set<Provider> = Provider.entries.toSet(),
    syncingProvider: Provider? = null,
    providerCounts: Map<Provider, Int> = emptyMap(),
    onEnabled: (Provider, Boolean) -> Unit = { _, _ -> },
    viewModel: ArthaViewModel? = null,
) {
    var showAiConfigDialog by remember { mutableStateOf(false) }
    var restoringBackup by remember { mutableStateOf(false) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }

    val snapshot by viewModel?.analysisSnapshot?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val diagnostics by viewModel?.providerDiagnostics?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) }
    val currentlySyncing by viewModel?.syncingProvider?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(syncingProvider) }
    val driveSyncState by viewModel?.driveSyncState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(DriveSyncState()) }

    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!email.isNullOrBlank()) {
            viewModel?.setDriveAccountEmail(email)
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (restoringBackup) viewModel?.restoreDriveBackup() else viewModel?.syncDriveNow()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "section_providers") {
            Text(
                "CONNECTED FINANCIAL SOURCES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        items(Provider.entries, key = { it.name }) { provider ->
            val isEnabled = provider in enabled
            val count = providerCounts[provider] ?: 0
            val diagnostic = diagnostics[provider]
            val isSyncingThis = currentlySyncing == provider

            ProviderCard(
                provider = provider,
                isEnabled = isEnabled,
                txCount = count,
                diagnostic = diagnostic,
                isSyncing = isSyncingThis,
                onToggleEnabled = { onEnabled(provider, it) },
                onManualSync = { viewModel?.syncProvider(provider) },
            )
        }

        // Section: Cloud Backup & Sync
        if (viewModel != null) {
            item(key = "section_sync_header") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "CLOUD BACKUP & SYNC",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            item(key = "drive_sync_card") {
                GoogleDriveSyncCard(
                    state = driveSyncState,
                    onToggleEnabled = { enabledVal ->
                        viewModel.setDriveSyncEnabled(enabledVal)
                        if (enabledVal && !driveSyncState.hasPassword) {
                            showPasswordDialog = true
                        }
                    },
                    onConnectAccount = {
                        val chooseIntent = AccountManager.newChooseAccountIntent(
                            null, null, arrayOf("com.google"), null, null, null, null
                        )
                        runCatching {
                            accountPickerLauncher.launch(chooseIntent)
                        }.onFailure {
                            showAccountDialog = true
                        }
                    },
                    onConfigurePassword = { showPasswordDialog = true },
                    onRestoreNow = { showRestoreConfirmation = true },
                    onSyncNow = {
                        restoringBackup = false
                        if (driveSyncState.status == CloudSyncStatus.AUTH_REQUIRED && driveSyncState.consentIntent != null) {
                            consentLauncher.launch(driveSyncState.consentIntent!!)
                        } else {
                            viewModel.syncDriveNow()
                        }
                    },
                )
            }
        }

        // Section: AI Intelligence
        if (viewModel != null && snapshot != null) {
            item(key = "section_ai_header") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "AI INTELLIGENCE & CATEGORIZATION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            item(key = "ai_provider_card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { showAiConfigDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.SettingsSuggest,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "AI Categorization Engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            val model = snapshot?.aiConfig?.model ?: "google/gemini-3.8-flash"
                            Text(
                                "Model: $model",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Configure",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

    }

    if (showAiConfigDialog && snapshot != null && viewModel != null) {
        AiConfigDialog(
            currentConfig = snapshot!!.aiConfig,
            onDismiss = { showAiConfigDialog = false },
            onSave = { updated ->
                viewModel.updateAiConfig(updated)
                showAiConfigDialog = false
            },
        )
    }

    if (showPasswordDialog && viewModel != null) {
        SyncPasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onSave = { pass ->
                viewModel.setDriveEncryptionPassword(pass)
                showPasswordDialog = false
            },
        )
    }

    if (showAccountDialog && viewModel != null) {
        AccountEmailDialog(
            currentEmail = driveSyncState.accountEmail.orEmpty(),
            onDismiss = { showAccountDialog = false },
            onSave = { email ->
                viewModel.setDriveAccountEmail(email)
                showAccountDialog = false
            },
        )
    }
    if (showRestoreConfirmation && viewModel != null) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = { Text("Restore backup?") },
            text = { Text("Add missing history and restore saved notes from your encrypted backup. Existing transactions and this phone’s AI credentials are kept.") },
            confirmButton = { TextButton(onClick = {
                showRestoreConfirmation = false
                restoringBackup = true
                if (driveSyncState.consentIntent != null) consentLauncher.launch(driveSyncState.consentIntent!!)
                else viewModel.restoreDriveBackup()
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { showRestoreConfirmation = false }) { Text("Cancel") } },
        )
    }

}

@Composable
private fun GoogleDriveSyncCard(
    state: DriveSyncState,
    onToggleEnabled: (Boolean) -> Unit,
    onConnectAccount: () -> Unit,
    onConfigurePassword: () -> Unit,
    onSyncNow: () -> Unit,
    onRestoreNow: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row: Cloud Icon + Title & Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column {
                        Text(
                            "Google Drive Backup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Encrypted backup; restore when needed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            // Status Badge & Account Email
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (state.status) {
                        CloudSyncStatus.READY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        CloudSyncStatus.SYNCING -> MaterialTheme.colorScheme.primaryContainer
                        CloudSyncStatus.NEEDS_ACCOUNT, CloudSyncStatus.NEEDS_PASSWORD -> MaterialTheme.colorScheme.tertiaryContainer
                        CloudSyncStatus.WRONG_PASSWORD, CloudSyncStatus.ERROR, CloudSyncStatus.AUTH_REQUIRED -> MaterialTheme.colorScheme.errorContainer
                        CloudSyncStatus.DISABLED -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val dotColor = when (state.status) {
                            CloudSyncStatus.READY -> MaterialTheme.colorScheme.primary
                            CloudSyncStatus.SYNCING -> MaterialTheme.colorScheme.primary
                            CloudSyncStatus.NEEDS_ACCOUNT, CloudSyncStatus.NEEDS_PASSWORD -> MaterialTheme.colorScheme.tertiary
                            CloudSyncStatus.WRONG_PASSWORD, CloudSyncStatus.ERROR, CloudSyncStatus.AUTH_REQUIRED -> MaterialTheme.colorScheme.error
                            CloudSyncStatus.DISABLED -> MaterialTheme.colorScheme.outline
                        }
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                        Text(
                            text = when (state.status) {
                                CloudSyncStatus.READY -> "Connected"
                                CloudSyncStatus.SYNCING -> "Syncing"
                                CloudSyncStatus.NEEDS_ACCOUNT -> "Needs Account"
                                CloudSyncStatus.NEEDS_PASSWORD -> "Needs Password"
                                CloudSyncStatus.WRONG_PASSWORD -> "Wrong Password"
                                CloudSyncStatus.AUTH_REQUIRED -> "Auth Required"
                                CloudSyncStatus.ERROR -> "Error"
                                CloudSyncStatus.DISABLED -> "Disabled"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                TextButton(
                    onClick = onConnectAccount,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = state.accountEmail ?: "Connect Account",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Encryption Password Configuration Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.hasPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (state.hasPassword) "Password Configured" else "No Password Set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                OutlinedButton(
                    onClick = onConfigurePassword,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = if (state.hasPassword) "Change" else "Set Password",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Sync Information & Manual Sync Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.lastSyncEpochMillis != null) "Last backup activity: ${formatRelativeTime(state.lastSyncEpochMillis)}" else "No backup yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.lastSyncSummary != null) {
                        Text(
                            text = state.lastSyncSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onSyncNow,
                    enabled = !state.isSyncing && state.isEnabled && state.hasPassword && state.accountEmail != null,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Syncing", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (state.status == CloudSyncStatus.AUTH_REQUIRED) "Authorize" else "Back Up",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onRestoreNow,
                enabled = !state.isSyncing && state.isEnabled && state.hasPassword && state.accountEmail != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore backup") }

            // Error Display Banner (if any error occurred)
            if (!state.lastError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = state.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
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
        title = { Text("Sync Encryption Password", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your ledger is encrypted on-device with AES-256-GCM before uploading to Google Drive. Google cannot read your transactions. You will need this password to sync with other devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Master Password") },
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
                    label = { Text("Confirm Password") },
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
        title = { Text("Google Account", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the Google Account email associated with your Google Drive backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Google Account Email") },
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

@Composable
private fun ProviderCard(
    provider: Provider,
    isEnabled: Boolean,
    txCount: Int,
    diagnostic: ProviderDiagnostic?,
    isSyncing: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onManualSync: () -> Unit,
) {
    val accountStatus = diagnostic?.accountStatus ?: AccountStatus.CONNECTED
    val lastError = diagnostic?.lastError
    val lastSyncTime = diagnostic?.lastSyncEpochMillis
    val lastSyncSummary = diagnostic?.lastSyncSummary
    val accountIdentifier = diagnostic?.accountIdentifier

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row: App Icon + Name + Identifier & Switch Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleEnabled(!isEnabled) },
                ) {
                    Image(
                        painter = painterResource(provider.iconRes),
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Column {
                        Text(
                            provider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (accountIdentifier != null) {
                            Text(
                                accountIdentifier,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = { onToggleEnabled(it) },
                )
            }

            // Status Badge and Transaction Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (accountStatus) {
                        AccountStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        AccountStatus.NEEDS_LOGIN -> MaterialTheme.colorScheme.tertiaryContainer
                        AccountStatus.APP_NOT_INSTALLED -> MaterialTheme.colorScheme.surfaceVariant
                        AccountStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val statusDotColor = when (accountStatus) {
                            AccountStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            AccountStatus.NEEDS_LOGIN -> MaterialTheme.colorScheme.tertiary
                            AccountStatus.APP_NOT_INSTALLED -> MaterialTheme.colorScheme.outline
                            AccountStatus.ERROR -> MaterialTheme.colorScheme.error
                        }
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Text(
                            text = when (accountStatus) {
                                AccountStatus.CONNECTED -> "Connected"
                                AccountStatus.NEEDS_LOGIN -> "Needs Login"
                                AccountStatus.APP_NOT_INSTALLED -> "Not Installed"
                                AccountStatus.ERROR -> "Sync Error"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Text(
                    text = "$txCount transactions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Sync Information & Manual Sync Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (lastSyncTime != null) "Last synced: ${formatRelativeTime(lastSyncTime)}" else "Never synced",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lastSyncSummary != null) {
                        Text(
                            text = lastSyncSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }

                OutlinedButton(
                    onClick = onManualSync,
                    enabled = !isSyncing && isEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Syncing", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Error Display Banner (if any error occurred)
            if (!lastError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
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
fun AiConfigDialog(
    currentConfig: AiConfig,
    onDismiss: () -> Unit,
    onSave: (AiConfig) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var model by remember { mutableStateOf(currentConfig.model) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Provider Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key / Bearer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    onSave(
                        currentConfig.copy(
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                        )
                    )
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

