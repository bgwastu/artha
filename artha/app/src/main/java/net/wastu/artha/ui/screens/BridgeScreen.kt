package net.wastu.artha.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import net.wastu.artha.ui.bridge.CameraQrScanner
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.ui.ArthaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    viewModel: ArthaViewModel? = null,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as? ArthaApplication
    val prefs = app?.bridgePreferences
    val coordinator = app?.bridgeCoordinator
    val scope = rememberCoroutineScope()

    val paired by prefs?.paired?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    var syncInProgress by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    // Bumped after each sync so values read from preferences recompose.
    var refreshTick by remember { mutableStateOf(0) }

    val sync: () -> Unit = {
        scope.launch {
            if (syncInProgress) return@launch
            syncInProgress = true
            syncStatus = "Waiting for the bridge to answer"
            val answered = runCatching { coordinator?.sync() == true }.getOrDefault(false)
            refreshTick++
            syncInProgress = false
            syncStatus = if (answered) "Transactions updated" else "The bridge did not answer, check that it is online"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Artha Bridge") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 600.dp),
        ) {
            if (paired && prefs != null) {
                PairedContent(
                    prefs = prefs,
                    refreshTick = refreshTick,
                    syncInProgress = syncInProgress,
                    syncStatus = syncStatus,
                    onSync = sync,
                    onUnpair = {
                        prefs.clearPairing()
                        syncStatus = null
                        Toast.makeText(context, "Unpaired from Artha Bridge", Toast.LENGTH_SHORT).show()
                    },
                )
            } else {
                UnpairedContent(
                    onConnect = { code ->
                        val paired = prefs?.pairWith(code) == true
                        if (paired) {
                            sync()
                            Toast.makeText(context, "Paired with Artha Bridge", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "That is not an Artha Bridge pairing code", Toast.LENGTH_SHORT).show()
                        }
                        paired
                    },
                )
            }
        }
    }
}

@Composable
private fun PairedContent(
    prefs: net.wastu.artha.data.sync.bridge.BridgePreferences,
    refreshTick: Int,
    syncInProgress: Boolean,
    syncStatus: String?,
    onSync: () -> Unit,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val bridgeName by prefs.bridgeDeviceName.collectAsStateWithLifecycle()
    val lastConnected by prefs.bridgeLastSeen.collectAsStateWithLifecycle()
    val bridgeVersion by prefs.bridgeVersion.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Devices, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        bridgeName ?: "Bridge phone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        bridgeLastConnectedLabel(lastConnected),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (bridgeVersion != null) {
                        Text(
                            "App v$bridgeVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Stays enabled while syncing (a disabled button greys out); taps are ignored instead.
            Button(
                onClick = { if (!syncInProgress) onSync() },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (syncInProgress) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text("Syncing")
                } else {
                    Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sync now")
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { confirmUnpair = true },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Unpair")
            }

            syncStatus?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncInProgress) colors.onSurfaceVariant else colors.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = { Icon(Icons.Default.LinkOff, null) },
            title = { Text("Unpair Artha Bridge?") },
            text = { Text("Artha stops syncing and stays locked until you pair a bridge again. Your transactions stay on this phone.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmUnpair = false; onUnpair() },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }

    Spacer(Modifier.height(24.dp))

    SectionHeading("Connection details")
    SectionCard {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Default.Schedule, null, tint = colors.primary) },
            headlineContent = { Text("Last connected") },
            supportingContent = { Text(if (lastConnected == null) "Not yet" else formatLastSync(lastConnected)) },
        )
        HorizontalDivider(color = colors.surfaceContainer)
        val channelId = prefs.getChannelId().orEmpty()
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Default.Lock, null, tint = colors.primary) },
            headlineContent = { Text("Encrypted channel") },
            supportingContent = { Text(channelId.take(16), fontFamily = FontFamily.Monospace) },
            trailingContent = {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Channel ID", channelId))
                    Toast.makeText(context, "Channel ID copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy channel ID")
                }
            },
        )
        HorizontalDivider(color = colors.surfaceContainer)
        val relays = remember { prefs.getRelayUrls().map { it.removePrefix("wss://").removePrefix("ws://") } }
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Default.Cloud, null, tint = colors.primary) },
            headlineContent = { Text("Nostr relays") },
            supportingContent = { Text(relays.joinToString(", ")) },
        )
    }
}

@Composable
private fun UnpairedContent(onConnect: (String) -> Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var inputCode by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0: Scan QR, 1: Enter Code

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Devices, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Connect to Helper Device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Artha does not need root. Pair it with a rooted phone running Artha Bridge, and your transactions sync automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                ) {
                    val scanSelected = selectedTab == 0
                    val enterSelected = selectedTab == 1

                    Surface(
                        onClick = { selectedTab = 0 },
                        shape = RoundedCornerShape(12.dp),
                        color = if (scanSelected) colors.primaryContainer else Color.Transparent,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = if (scanSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Scan QR",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (scanSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (scanSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            )
                        }
                    }

                    Surface(
                        onClick = { selectedTab = 1 },
                        shape = RoundedCornerShape(12.dp),
                        color = if (enterSelected) colors.primaryContainer else Color.Transparent,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = if (enterSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Enter code",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (enterSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (enterSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (selectedTab == 0) {
                CameraQrScanner(
                    onQrScanned = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { inputCode = it },
                    label = { Text("Pairing link or key") },
                    placeholder = { Text("Copy it from Artha Bridge") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clip.isNotBlank()) inputCode = clip
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste")
                    }
                    Button(
                        onClick = { onConnect(inputCode) },
                        enabled = inputCode.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
}

private fun formatLastSync(millis: Long?): String {
    if (millis == null) return "Never synced"
    val diffSec = (System.currentTimeMillis() - millis) / 1000
    return when {
        diffSec < 60 -> "Just now"
        diffSec < 3600 -> "${diffSec / 60} min ago"
        diffSec < 86400 -> "${diffSec / 3600} h ago"
        else -> DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

/** "Last connected 5 min ago", or a waiting note before the bridge has answered once. */
internal fun bridgeLastConnectedLabel(millis: Long?): String =
    if (millis == null) "Waiting for the bridge to connect"
    else "Last connected ${formatLastSync(millis).replaceFirstChar { it.lowercase() }}"

