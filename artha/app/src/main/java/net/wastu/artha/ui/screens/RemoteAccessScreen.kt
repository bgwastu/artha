package net.wastu.artha.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.api.service.ApiServerController
import net.wastu.artha.api.tunnel.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by ApiServerController.tunnelState.collectAsStateWithLifecycle()
    val shareUrl by ApiServerController.publicUrl.collectAsStateWithLifecycle()
    val on = state is TunnelState.Starting || state is TunnelState.Active
    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote access") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val failed = state is TunnelState.Failed
            val (title, support) = when (val s = state) {
                is TunnelState.Active -> "Link is live" to "Read access for anyone with the link"
                TunnelState.Starting -> "Creating link" to "A few seconds"
                is TunnelState.Failed -> "Couldn’t start" to s.error
                TunnelState.Inactive -> "Link is off" to "For AI agents and scripts"
            }
            ListItem(
                headlineContent = { Text(title, style = MaterialTheme.typography.titleLarge) },
                supportingContent = {
                    Text(support, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (failed) colors.error else colors.onSurfaceVariant)
                },
                trailingContent = {
                    Switch(checked = on, onCheckedChange = { want ->
                        if (want) ApiServerController.start(context) else ApiServerController.stop(context)
                    })
                },
                modifier = Modifier.padding(top = 8.dp),
            )
            if (state is TunnelState.Starting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            else HorizontalDivider(Modifier.padding(horizontal = 16.dp))

            when (state) {
                is TunnelState.Active -> shareUrl?.let { url ->
                    Surface(
                        color = colors.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                url.removePrefix("https://").replaceFirst(".", "\n."),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            )
                            IconButton(onClick = { copy(context, url) }) { Icon(Icons.Default.ContentCopy, "Copy link") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { share(context, url) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                            Text("Share", Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = { open(context, "$url/docs") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                            Text("API docs", Modifier.padding(start = 8.dp))
                        }
                    }
                }
                is TunnelState.Failed -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(onClick = { ApiServerController.start(context) }) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Text("Try again", Modifier.padding(start = 8.dp))
                    }
                }
                else -> Unit
            }
        }
    }
}

private fun open(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copy(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Artha link", url))
    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, null))
}
