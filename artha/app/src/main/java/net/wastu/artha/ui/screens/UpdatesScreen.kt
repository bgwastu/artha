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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val app = context.applicationContext as? ArthaApplication
    val updater = app?.updater
    val scope = rememberCoroutineScope()

    val state by updater?.state.collectOrDefault(UpdateState.Idle)
    val autoUpdate by updater?.autoUpdate.collectOrDefault(true)

    var canRequestInstalls by remember {
        mutableStateOf(context.packageManager.canRequestPackageInstalls())
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
            // Version card
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                    headlineContent = {
                        Text("Artha", fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        val vName = updater?.installedVersionName?.ifBlank { "0.1.0" } ?: "0.1.0"
                        val vCode = updater?.installedVersionCode ?: 1
                        Text("Version $vName (build $vCode)")
                    },
                )
            }

            // Status & Actions Card
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val s = state) {
                        is UpdateState.Idle -> {
                            Text("No update check has been run yet.")
                            Button(
                                onClick = { scope.launch { updater?.update(install = false) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Check for updates")
                            }
                        }
                        is UpdateState.Checking -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LoadingIndicator(modifier = Modifier.size(20.dp),)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking GitHub for updates...")
                            }
                        }
                        is UpdateState.UpToDate -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Your app is up to date", fontWeight = FontWeight.Medium)
                            }
                            val dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.checkedAt))
                            Text("Last checked: $dateStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(
                                onClick = { scope.launch { updater?.update(install = false) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Check again")
                            }
                        }
                        is UpdateState.Downloading -> {
                            Text("Downloading update ${s.versionName}...", fontWeight = FontWeight.Medium)
                            LinearWavyProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        is UpdateState.Ready -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Version ${s.versionName} ready to install", fontWeight = FontWeight.Medium)
                            }
                            Text("The update has been downloaded and cryptographically verified.")
                            Button(
                                onClick = { scope.launch { updater?.update(install = true) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Install now")
                            }
                        }
                        is UpdateState.Installing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LoadingIndicator(modifier = Modifier.size(20.dp),)
                                Spacer(Modifier.width(12.dp))
                                Text("Installing version ${s.versionName}...")
                            }
                        }
                        is UpdateState.WaitingForConfirmation -> {
                            Text("Android requires your confirmation to complete the install.", fontWeight = FontWeight.Medium)
                            Text("Please confirm the installation in the system prompt.")
                        }
                        is UpdateState.Failed -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text("Update check failed", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                            }
                            Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Button(
                                onClick = { scope.launch { updater?.update(install = false) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Try again")
                            }
                        }
                    }
                }
            }

            // Auto-update toggle
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text("Automatic updates", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("Check and download updates automatically in the background") },
                    trailingContent = {
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { updater?.setAutoUpdate(it) },
                        )
                    },
                )
            }

            // Unknown sources permission card if needed
            if (!canRequestInstalls) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Install permission needed", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "Android requires permission to install apps before Artha can update itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                                context.startActivity(intent)
                            },
                        ) {
                            Text("Open install settings")
                        }
                    }
                }
            }

            // Security note
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
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
    }
}
