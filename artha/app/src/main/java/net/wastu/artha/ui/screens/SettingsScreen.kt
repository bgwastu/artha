package net.wastu.artha.ui.screens

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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
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
import net.wastu.artha.ui.Page
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
    LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SettingsLink("Sources", "Choose which apps to import", Icons.Default.AccountBalanceWallet) { onOpen(Page.SOURCES) } }
        item { SettingsLink("Categories", "Expense and income categories", Icons.Default.Category) { onOpen(Page.CATEGORIES) } }
        if (viewModel != null) {
            item { SettingsLink("AI categorization", aiSummary, Icons.Default.SettingsSuggest) { onOpen(Page.AI) } }
            item { SettingsLink("Remote access", remoteSummary, Icons.Default.Link) { onOpen(Page.REMOTE) } }
            item { SettingsLink("Export and import", "Back up or restore your data", Icons.Default.FileDownload) { onOpen(Page.DATA) } }
        }
        item {
            ListItem(
                headlineContent = { Text("Widgets") },
                supportingContent = { Text("Add from your home screen") },
                leadingContent = { Icon(Icons.Default.Widgets, null) },
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export and import") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).widthIn(max = 600.dp)) {
            SettingsLink("Export backup", if (exporting) "Exporting" else "Save all data to a file", Icons.Default.FileDownload) {
                if (!exporting) exportDocument.launch(viewModel.suggestedExportFileName())
            }
            SettingsLink("Import backup", if (importingFile) "Importing" else "Restore from a backup file", Icons.Default.FileUpload) {
                if (!importingFile) importDocument.launch(arrayOf("application/json", "application/*", "text/*", "*/*"))
            }
        }
    }
}
