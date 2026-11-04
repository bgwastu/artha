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
            else DeviceList(devices, updater = app.updater, onRemove = { app.preferences.removeDevice(it.id) })
        }
            if (showUpdates) {
                BridgeUpdateDialog(app.updater, onDismiss = { showUpdates = false })
            }
    }
}

@Composable
private fun DeviceList(devices: List<ConnectedDevice>, updater: AppUpdater, onRemove: (ConnectedDevice) -> Unit) {
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
            Spacer(Modifier.height(16.dp))
            BridgeUpdateCard(updater)
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

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        qr?.let { bitmap ->
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.size(260.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Scan with Artha on your main phone",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val uri = pairingUri ?: return@Button
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Artha pairing link", uri))
                Toast.makeText(context, "Pairing link copied", Toast.LENGTH_SHORT).show()
            },
            enabled = pairingUri != null,
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Copy pairing link")
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
private fun BridgeUpdateCard(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val autoUpdate by updater.autoUpdate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bridge updates", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoUpdate,
                    onCheckedChange = { updater.setAutoUpdate(it) },
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
