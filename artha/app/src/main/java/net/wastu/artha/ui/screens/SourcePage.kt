package net.wastu.artha.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.iconRes
import net.wastu.artha.data.attention.AttentionAction
import net.wastu.artha.data.attention.forSource
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.GojekFlashRecord
import net.wastu.artha.data.providers.ProviderDiagnostic
import net.wastu.artha.ui.SourceCoverage
import net.wastu.artha.ui.components.PageScaffold

/** One provider's detail page: status, history, provider-specific facts and the two source switches. */
@Composable
internal fun SourcePage(
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
        if (!feedback.isNullOrBlank()) snackbarHostState.showSnackbar(feedback)
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
            actions = { SyncHeaderAction(importing = importing, enabled = importEnabled && !busy, onSync = onImport) },
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
                    busy = busy,
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
    busy: Boolean,
    allTransactions: List<UnifiedTransaction>,
    providerCounts: Map<Provider, Int>,
    onEnabled: (Boolean) -> Unit,
    onImportEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    flash: GojekFlashRecord?,
) {
    var pinDialogOpen by remember { mutableStateOf(false) }
    val expenseCount = remember(allTransactions, provider, providerCounts) {
        if (allTransactions.isNotEmpty()) countExpenses(allTransactions, provider) else providerCounts[provider] ?: 0
    }

    AttentionCards(rememberAttentionItems().forSource(provider)) { action ->
        when (action) {
            AttentionAction.SET_JAGO_PIN -> pinDialogOpen = true
        }
    }
    SourceStatusSection(provider, diagnostic, importEnabled, importing, busy, onImport)
    SourceHistorySection(diagnostic, coverage, expenseCount)
    if (provider == Provider.GOJEK) GojekSection(flash)
    if (provider == Provider.JAGO) JagoPinSetting(dialogOpen = pinDialogOpen, onDialogOpenChange = { pinDialogOpen = it })
    SettingsHeading("Settings")
    SettingsSwitch(title = "Sync new payments", checked = importEnabled, onChange = onImportEnabled, sourceName = provider.displayName)
    SettingsSwitch(title = "Show in Expenses", checked = enabled, onChange = onEnabled, sourceName = provider.displayName)
}

/** Headline and last-sync line, plus the error text and the Retry / open-the-app action when something is wrong. */
@Composable
private fun SourceStatusSection(
    provider: Provider,
    diagnostic: ProviderDiagnostic?,
    importEnabled: Boolean,
    importing: Boolean,
    busy: Boolean,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val isProblem = isSourceProblem(diagnostic, importEnabled, importing)
    val status = diagnostic?.accountStatus
    val error = diagnostic?.lastError?.takeIf { it.isNotBlank() }

    ListItem(
        headlineContent = {
            Text(
                text = sourceHeadline(provider, diagnostic, importEnabled, importing),
                color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(diagnostic?.lastSyncEpochMillis?.let { "Last synced ${formatRelativeTime(it)}" } ?: "Not synced yet")
        },
    )
    if (status == AccountStatus.PARTIAL && !isProblem && error != null) {
        Text(
            text = error,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!isProblem) return

    if (status == AccountStatus.ERROR && error != null) {
        Text(
            text = error,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Signing in happens in the provider's app, so that comes first; Retry then checks the new session.
        if (status == AccountStatus.NEEDS_LOGIN || status == AccountStatus.APP_NOT_INSTALLED) {
            FilledTonalButton(onClick = { openProviderApp(context, provider) }) { Text("Open ${provider.displayName}") }
        }
        when (status) {
            AccountStatus.APP_NOT_INSTALLED -> Unit
            AccountStatus.NEEDS_LOGIN -> OutlinedButton(onClick = onImport, enabled = !busy) { Text("Retry") }
            else -> FilledTonalButton(onClick = onImport, enabled = !busy) { Text("Retry") }
        }
    }
}

/** Opens the provider's own app, else its Play Store page, else the Play Store website. */
private fun openProviderApp(context: Context, provider: Provider) {
    context.packageManager.getLaunchIntentForPackage(provider.appPackage)?.let {
        context.startActivity(it)
        return
    }
    fun view(uri: String) = Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(view("market://details?id=${provider.appPackage}")) }.onFailure {
        runCatching { context.startActivity(view("https://play.google.com/store/apps/details?id=${provider.appPackage}")) }
    }
}

@Composable
private fun SourceHistorySection(diagnostic: ProviderDiagnostic?, coverage: SourceCoverage?, expenseCount: Int) {
    SettingsHeading("History")
    ListItem(headlineContent = { Text("Expenses") }, trailingContent = { Text(expenseCount.toString()) })

    val coversText = remember(coverage) { coverageText(coverage) }
    if (coversText != null) {
        ListItem(headlineContent = { Text("Covers") }, supportingContent = { Text(coversText) })
    }
    diagnostic?.coverageHint?.takeIf { it.isNotBlank() }?.let { hint ->
        ListItem(
            headlineContent = { Text("What Artha reads") },
            supportingContent = { Text(hint, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    }
    lastSyncText(diagnostic?.lastSyncSummary)?.let { summary ->
        ListItem(headlineContent = { Text("Last sync") }, supportingContent = { Text(summary) })
    }
    diagnostic?.accountIdentifier?.takeIf { it.isNotBlank() }?.let { accountId ->
        ListItem(headlineContent = { Text("Account") }, supportingContent = { Text(accountId) })
    }
}

/** "5 Nov to 9 Nov", a single day, or null when nothing has been saved yet. */
private fun coverageText(coverage: SourceCoverage?): String? {
    val early = coverage?.earliest
    val late = coverage?.latest
    return when {
        early != null && late != null -> {
            val from = formatSavedDay(early)
            val to = formatSavedDay(late)
            if (from == to) from else "$from to $to"
        }
        late != null -> formatSavedDay(late)
        early != null -> formatSavedDay(early)
        else -> null
    }
}

@Composable
private fun GojekSection(flash: GojekFlashRecord?) {
    SettingsHeading("Gojek")
    val text = flash?.millis?.let { millis ->
        val stamp = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        "Last run $stamp"
    } ?: "Not run yet"
    ListItem(headlineContent = { Text("Background refresh") }, supportingContent = { Text(text) })
}

@Composable
private fun SyncHeaderAction(importing: Boolean, enabled: Boolean, onSync: () -> Unit) {
    IconButton(onClick = onSync, enabled = enabled && !importing) {
        if (importing) LoadingIndicator(Modifier.size(20.dp)) else Icon(Icons.Default.Sync, contentDescription = "Sync history")
    }
}
