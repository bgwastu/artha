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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VersionCard(
                versionName = updater?.installedVersionName?.ifBlank { "0.1.0" } ?: "0.1.0",
                versionCode = updater?.installedVersionCode ?: 1L,
            )
            UpdateStatusCard(
                state = state,
                onCheck = { scope.launch { updater?.update(install = false) } },
                onInstall = { scope.launch { updater?.update(install = true) } },
            )
            AutoUpdateCard(checked = autoUpdate, onCheckedChange = { updater?.setAutoUpdate(it) })
            if (!canRequestInstalls) {
                InstallPermissionCard(onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = "package:${context.packageName}".toUri()
                        },
                    )
                })
            }
            SecurityNote()
        }
    }
}

@Composable
private fun UpdateCard(color: Color, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = color, modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun VersionCard(versionName: String, versionCode: Long) {
    UpdateCard(MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            leadingContent = {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            headlineContent = { Text("Artha", fontWeight = FontWeight.Medium) },
            supportingContent = { Text("Version $versionName (build $versionCode)") },
        )
    }
}

@Composable
private fun UpdateStatusCard(state: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit) {
    UpdateCard(MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is UpdateState.Idle -> {
                    Text("No update check has been run yet.")
                    Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        RefreshLabel("Check for updates")
                    }
                }
                is UpdateState.Checking -> BusyLine("Checking GitHub for updates...")
                is UpdateState.UpToDate -> {
                    StatusHeadline(Icons.Default.CheckCircle, "Your app is up to date", MaterialTheme.colorScheme.primary)
                    val checkedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.checkedAt))
                    Text("Last checked: $checkedAt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { RefreshLabel("Check again") }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading update ${state.versionName}...", fontWeight = FontWeight.Medium)
                    LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.Ready -> {
                    StatusHeadline(Icons.Default.Download, "Version ${state.versionName} ready to install", MaterialTheme.colorScheme.primary)
                    Text("The update has been downloaded and cryptographically verified.")
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install now") }
                }
                is UpdateState.Installing -> BusyLine("Installing version ${state.versionName}...")
                is UpdateState.WaitingForConfirmation -> {
                    Text("Android requires your confirmation to complete the install.", fontWeight = FontWeight.Medium)
                    Text("Please confirm the installation in the system prompt.")
                }
                is UpdateState.Failed -> {
                    StatusHeadline(Icons.Default.Warning, "Update check failed", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun RefreshLabel(text: String) {
    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text)
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

@Composable
private fun AutoUpdateCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    UpdateCard(MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            headlineContent = { Text("Automatic updates", fontWeight = FontWeight.Medium) },
            supportingContent = { Text("Check and download updates automatically in the background") },
            trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        )
    }
}

@Composable
private fun InstallPermissionCard(onOpenSettings: () -> Unit) {
    val onError = MaterialTheme.colorScheme.onErrorContainer
    UpdateCard(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Install permission needed", fontWeight = FontWeight.Medium, color = onError)
            Text(
                "Android requires permission to install apps before Artha can update itself.",
                style = MaterialTheme.typography.bodySmall,
                color = onError,
            )
            OutlinedButton(onClick = onOpenSettings) { Text("Open install settings") }
        }
    }
}

@Composable
private fun SecurityNote() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Updates are fetched from GitHub Releases over HTTPS. Every update is verified against its SHA-256 checksum and must match Artha's signing certificate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
