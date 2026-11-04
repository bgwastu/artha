package net.wastu.artha.bridge.ui

import androidx.compose.foundation.lazy.itemsIndexed
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.MaterialExpressiveTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.wastu.artha.core.update.AppUpdater
import net.wastu.artha.core.update.UpdateState
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import net.wastu.artha.core.model.Provider
import net.wastu.artha.bridge.daemon.BridgeLog
import net.wastu.artha.bridge.daemon.BridgeDaemonService
import net.wastu.artha.bridge.BuildConfig
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Base64
import kotlinx.coroutines.delay
import net.wastu.artha.bridge.daemon.BridgeApplication
import net.wastu.artha.bridge.daemon.ConnectedDevice

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as BridgeApplication).startDaemon()
        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive()) {
                BridgeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen() {
    val app = LocalContext.current.applicationContext as BridgeApplication
    val devices by app.preferences.devices.collectAsStateWithLifecycle()

    var adding by remember { mutableStateOf(false) }
    var showUpdates by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    // Leave the pairing view as soon as the new device shows up.
    var countWhenAdding by remember { mutableStateOf(0) }
    LaunchedEffect(devices.size) { if (adding && devices.size > countWhenAdding) adding = false }
    BackHandler(enabled = adding) { adding = false }
    val showPairing = devices.isEmpty() || adding

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (adding) "Add device" else "Artha Bridge") },
                navigationIcon = {
                    if (adding) {
                        IconButton(onClick = { adding = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    val isSyncing by BridgeDaemonService.isSyncing.collectAsStateWithLifecycle()
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { showLogs = true }) {
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Logs")
                    }
                    IconButton(onClick = { showUpdates = true }) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Updates")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!showPairing) {
                ExtendedFloatingActionButton(
                    onClick = { countWhenAdding = devices.size; adding = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add device") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showPairing) PairingContent(app)
            else DeviceList(app = app, devices = devices, onRemove = { app.preferences.removeDevice(it.id) })
        }
            if (showUpdates) {
                BridgeUpdateDialog(app.updater, onDismiss = { showUpdates = false })
            }
            if (showLogs) {
                BridgeLogsDialog(onDismiss = { showLogs = false })
            }
    }
}

@Composable
private fun DeviceList(app: BridgeApplication, devices: List<ConnectedDevice>, onRemove: (ConnectedDevice) -> Unit) {
    // Re-render every minute so "last synced" stays current.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.id == selectedId }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        itemsIndexed(devices, key = { _, device -> device.id }) { index, device ->
            // Expressive grouped list: large outer corners, small joins, 2dp gaps.
            val big = 20.dp
            val small = 4.dp
            val shape = when {
                devices.size == 1 -> RoundedCornerShape(big)
                index == 0 -> RoundedCornerShape(big, big, small, small)
                index == devices.lastIndex -> RoundedCornerShape(small, small, big, big)
                else -> RoundedCornerShape(small)
            }
            Surface(
                onClick = { selectedId = device.id },
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            ) {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text("Last synced ${lastSeen(device.lastSeenMillis, now)}") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SourcesCard(app)
        }
        item {
            Spacer(Modifier.height(8.dp))
            BridgeUpdateCard(app.updater)
        }
    }

    if (selected != null && !confirmRemove) {
        AlertDialog(
            onDismissRequest = { selectedId = null },
            icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
            title = { Text(selected.name) },
            text = {
                Column {
                    DetailRow("Last synced", lastSeen(selected.lastSeenMillis, now))
                    DetailRow("First connected", formatDate(selected.firstSeenMillis))
                    DetailRow("Device ID", selected.id.take(8))
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { confirmRemove = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Remove device")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedId = null }) { Text("Close") } },
        )
    }

    if (selected != null && confirmRemove) {
        val last = devices.size == 1
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Remove ${selected.name}?") },
            text = {
                Text(
                    if (last) "This phone stops sending it transactions and gets a new pairing code. " +
                        "Pair Artha again to keep syncing."
                    else "This bridge ignores its requests from now on. " +
                        "To pair it again, remove every device to get a new pairing code."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(selected)
                        confirmRemove = false
                        selectedId = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PairingContent(app: BridgeApplication) {
    val context = LocalContext.current
    var pairingUri by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        val key = app.preferences.getSecretKey() ?: return@LaunchedEffect
        val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val uri = "artha://pair?key=$encodedKey"
        pairingUri = uri
        qr = runCatching { encodeQr(uri) }.getOrNull()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    qr?.let { bitmap ->
                        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.size(240.dp)) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Pairing QR code",
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Scan with Artha on your main phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val uri = pairingUri ?: return@Button
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Artha pairing link", uri))
                            Toast.makeText(context, "Pairing link copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = pairingUri != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Copy pairing link")
                    }
                }
            }
        }
        item {
            SourcesCard(app)
        }
        item {
            BridgeUpdateCard(app.updater)
        }
    }
}

private fun lastSeen(millis: Long, now: Long): String =
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()

private fun formatDate(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(millis))

private fun encodeQr(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}

@Composable
private fun BridgeUpdateDialog(updater: AppUpdater, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bridge updates") },
        text = { BridgeUpdateCard(updater) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BridgeLogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val logs by BridgeLog.logs.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bridge event log") },
        text = {
            Column(Modifier.fillMaxWidth().height(360.dp)) {
                if (logs.isEmpty()) {
                    Text("No log entries recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(logs) { entry ->
                            val color = when (entry.level) {
                                "E" -> MaterialTheme.colorScheme.error
                                "W" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                entry.format(),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                color = color,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val text = logs.joinToString("\n") { it.format() }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Artha Bridge logs", text))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = logs.isNotEmpty(),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy logs")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SourcesCard(app: BridgeApplication) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val statuses = remember(refreshTick) { app.importer.sourceStatuses().associateBy { it.provider } }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sources & export status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Apps monitored on this bridge", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                try {
                                    app.importer.importAll()
                                    refreshTick++
                                } finally {
                                    refreshing = false
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Export & refresh now", modifier = Modifier.size(18.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Provider.entries.forEach { provider ->
                val isInstalled = remember(provider) { app.importer.isProviderInstalled(provider) }
                val status = statuses[provider]

                val title = when (provider) {
                    Provider.JAGO -> "Bank Jago"
                    Provider.GOJEK -> "Gojek / GoPay"
                    Provider.SHOPEE -> "Shopee"
                    Provider.GRAB -> "Grab"
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        val statusText = when {
                            !isInstalled -> "Not installed on this device"
                            status == null -> "Installed · ready to sync"
                            status.status == net.wastu.artha.data.providers.AccountStatus.CONNECTED ->
                                status.summary ?: ("Up to date" + (status.paymentCount?.let { " · $it payments" } ?: ""))
                            status.status == net.wastu.artha.data.providers.AccountStatus.PARTIAL ->
                                status.summary ?: "Partly synced"
                            status.status == net.wastu.artha.data.providers.AccountStatus.ERROR ->
                                "Sync failed · ${status.error ?: "Unknown error"}"
                            else -> status.summary ?: "Installed"
                        }
                        val statusColor = when {
                            !isInstalled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            status?.status == net.wastu.artha.data.providers.AccountStatus.ERROR -> MaterialTheme.colorScheme.error
                            status?.status == net.wastu.artha.data.providers.AccountStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
                            status?.status == net.wastu.artha.data.providers.AccountStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                    }

                    if (!isInstalled) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                "Missing",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (status?.status == net.wastu.artha.data.providers.AccountStatus.CONNECTED) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else if (status?.status == net.wastu.artha.data.providers.AccountStatus.ERROR) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeUpdateCard(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val autoUpdate by updater.autoUpdate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bridge updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text("Automatic updates", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("Install verified updates automatically in the background") },
                    trailingContent = {
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { updater.setAutoUpdate(it) },
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            when (val s = state) {
                is UpdateState.Idle -> {
                    Text("No update check has been run yet.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { scope.launch { updater.update(install = false) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Check for updates")
                    }
                }
                is UpdateState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Checking GitHub for updates...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is UpdateState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Bridge is up to date", style = MaterialTheme.typography.bodyMedium)
                    }
                    val dateStr = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(s.checkedAt))
                    Text("Last checked: $dateStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { scope.launch { updater.update(install = false) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Check again")
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading ${s.versionName}...", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.Ready -> {
                    Text("Update ${s.versionName} downloaded and verified.", style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { updater.update(install = true) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Install update now")
                    }
                }
                is UpdateState.Installing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Installing ${s.versionName} with root...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is UpdateState.WaitingForConfirmation -> {
                    Text("Waiting for install confirmation.", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Update check failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { scope.launch { updater.update(install = false) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}
