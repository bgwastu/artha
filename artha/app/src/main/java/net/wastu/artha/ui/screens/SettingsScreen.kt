package net.wastu.artha.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import net.wastu.artha.ui.components.PageScaffold
import net.wastu.artha.ui.components.Segment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.wastu.artha.data.export.LedgerExportStorage
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.Page
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Widgets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    viewModel: ArthaViewModel?,
    onOpen: (Page) -> Unit,
) {
    val snapshot by viewModel?.analysisSnapshot?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val tunnelState by net.wastu.artha.api.service.ApiServerController.tunnelState.collectAsStateWithLifecycle()
    val config = snapshot?.aiConfig
    val aiSummary = if (config != null && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) "On" else "Not set up"
    val remoteSummary = when (tunnelState) {
        is net.wastu.artha.api.tunnel.TunnelState.Active -> "Link is live"
        is net.wastu.artha.api.tunnel.TunnelState.Starting -> "Creating link"
        is net.wastu.artha.api.tunnel.TunnelState.Failed -> "Could not connect"
        is net.wastu.artha.api.tunnel.TunnelState.Inactive -> "Off"
    }
    val context = LocalContext.current
    val updater = (context.applicationContext as? net.wastu.artha.ArthaApplication)?.updater
    val updateState by updater?.state?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val updateSummary = when (val s = updateState) {
        is net.wastu.artha.core.update.UpdateState.Ready -> "Update ready (${s.versionName})"
        is net.wastu.artha.core.update.UpdateState.Downloading -> "Downloading... (${(s.progress * 100).toInt()}%)"
        is net.wastu.artha.core.update.UpdateState.Checking -> "Checking for updates..."
        is net.wastu.artha.core.update.UpdateState.Installing -> "Installing ${s.versionName}..."
        is net.wastu.artha.core.update.UpdateState.WaitingForConfirmation -> "Confirmation requested"
        is net.wastu.artha.core.update.UpdateState.Failed -> "Update check failed"
        is net.wastu.artha.core.update.UpdateState.UpToDate -> "Up to date"
        else -> updater?.installedVersionName?.ifBlank { null }?.let { "v$it" } ?: "Check for updates"
    }
    val attention = rememberAttentionItems()
    val sourcesAttention = attention.filter { it.scope is net.wastu.artha.data.attention.AttentionScope.Source }
    LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            SettingsLink(
                "Sources",
                "Choose which apps to import",
                Icons.Default.AccountBalanceWallet,
                attention = sourcesAttention,
                segment = Segment.First,
            ) { onOpen(Page.SOURCES) }
        }
        item { SettingsLink("Categories", "Expense and income categories", Icons.Default.Category, segment = Segment.Middle) { onOpen(Page.CATEGORIES) } }
        if (viewModel != null) {
            item { SettingsLink("AI categorization", aiSummary, Icons.Default.SettingsSuggest, segment = Segment.Last) { onOpen(Page.AI) } }
            item { Spacer(Modifier.height(16.dp)) }
            item { SettingsLink("Remote access", remoteSummary, Icons.Default.Link, segment = Segment.First) { onOpen(Page.REMOTE) } }
            item { SettingsLink("Export and import", "Back up or restore your data", Icons.Default.FileDownload, segment = Segment.Middle) { onOpen(Page.DATA) } }
            item { SettingsLink("Updates", updateSummary, Icons.Default.SystemUpdate, segment = Segment.Last) { onOpen(Page.UPDATES) } }
            item { Spacer(Modifier.height(16.dp)) }
        }
        item {
            SettingsLink(
                "Widgets",
                "Add from your home screen",
                Icons.Default.Widgets,
                segment = Segment.Single,
                onClick = null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataScreen(viewModel: ArthaViewModel, onBack: () -> Unit) {
    var exporting by remember { mutableStateOf(false) }
    var importingFile by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importingFile = true
            try {
                viewModel.importExportFile(context, uri)
                Toast.makeText(context, "Import saved", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, "Import failed: ${error.message ?: error::class.java.simpleName}", Toast.LENGTH_LONG).show()
            } finally {
                importingFile = false
            }
        }
    }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            try {
                LedgerExportStorage.writeToUri(context, uri, viewModel.buildExportJsonl())
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
    PageScaffold(title = "Export and import", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).widthIn(max = 600.dp)) {
            Spacer(Modifier.height(8.dp))
            SettingsLink("Export backup", if (exporting) "Exporting" else "Save all data to a file", Icons.Default.FileDownload, segment = Segment.First) {
                if (!exporting) exportDocument.launch(viewModel.suggestedExportFileName())
            }
            SettingsLink("Import backup", if (importingFile) "Importing" else "Restore from a backup file", Icons.Default.FileUpload, segment = Segment.Last) {
                if (!importingFile) importDocument.launch(arrayOf("application/json", "application/*", "text/*", "*/*"))
            }
        }
    }
}
