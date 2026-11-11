package net.wastu.artha.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.core.update.UpdateState
import net.wastu.artha.ui.components.PageScaffold
import net.wastu.artha.ui.components.collectOrDefault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val updater = (context.applicationContext as? ArthaApplication)?.updater
    val scope = rememberCoroutineScope()

    val state by updater?.state.collectOrDefault(UpdateState.Idle)
    val autoUpdate by updater?.autoUpdate.collectOrDefault(true)

    // The install permission is granted in system settings, so re-read it when the user comes back.
    var canRequestInstalls by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canRequestInstalls = context.packageManager.canRequestPackageInstalls()
    }

    PageScaffold(title = "App updates", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .widthIn(max = 600.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val versionName = updater?.installedVersionName?.ifBlank { "0.1.0" } ?: "0.1.0"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                    }
                }
                Column {
                    Text("Artha", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Version $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            UpdateStatus(
                state = state,
                onCheck = { scope.launch { updater?.update(install = false) } },
                onInstall = { scope.launch { updater?.update(install = true) } },
            )
            Column(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Update automatically") },
                    trailingContent = { Switch(checked = autoUpdate, onCheckedChange = { updater?.setAutoUpdate(it) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (!canRequestInstalls) {
                    ListItem(
                        headlineContent = { Text("Allow installing updates") },
                        trailingContent = {
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                        data = "package:${context.packageName}".toUri()
                                    },
                                )
                            }) { Text("Allow") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** One line about where things stand and, when there is something to do, one button. */
@Composable
private fun UpdateStatus(state: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            is UpdateState.Idle -> Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
            is UpdateState.Checking -> BusyLine("Checking…")
            is UpdateState.UpToDate -> {
                StatusHeadline(Icons.Default.CheckCircle, "Up to date", MaterialTheme.colorScheme.primary)
                val checkedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.checkedAt))
                Text("Checked $checkedAt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${state.versionName}", fontWeight = FontWeight.Medium)
                LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                StatusHeadline(Icons.Default.Download, "${state.versionName} is ready", MaterialTheme.colorScheme.primary)
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install") }
            }
            is UpdateState.Installing -> BusyLine("Installing ${state.versionName}…")
            is UpdateState.WaitingForConfirmation -> Text("Confirm in the system prompt", fontWeight = FontWeight.Medium)
            is UpdateState.Failed -> {
                StatusHeadline(Icons.Default.Warning, "Update failed", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error)
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun StatusHeadline(icon: ImageVector, text: String, tint: Color, textColor: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, color = textColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}
