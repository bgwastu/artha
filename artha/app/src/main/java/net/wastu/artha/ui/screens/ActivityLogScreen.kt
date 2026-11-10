package net.wastu.artha.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.artha.core.log.ArthaLog
import net.wastu.artha.core.log.LogEntry
import net.wastu.artha.ui.components.PageScaffold

/** What Artha did and why, newest first, so a surprise (such as Bank Jago asking to verify) can be traced. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<LogEntry>?>(null) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(version) { entries = withContext(Dispatchers.IO) { ArthaLog.recent(400).asReversed() } }
    PageScaffold(
        title = "Activity log",
        onBack = onBack,
        actions = {
            IconButton(
                enabled = !entries.isNullOrEmpty(),
                onClick = {
                    val text = entries.orEmpty().asReversed().joinToString("\n", transform = ArthaLog::format)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share activity log"))
                },
            ) { Icon(Icons.Default.Share, contentDescription = "Share the log") }
            IconButton(
                enabled = !entries.isNullOrEmpty(),
                onClick = { ArthaLog.clear(); version++ },
            ) { Icon(Icons.Default.Delete, contentDescription = "Clear the log") }
        },
    ) { padding ->
        val rows = entries
        if (rows != null && rows.isEmpty()) {
            Text(
                "Nothing recorded yet. Syncs and background work show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(
                Modifier.widthIn(max = 600.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(rows.orEmpty()) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            ArthaLog.format(entry),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (entry.level == 'W' || entry.level == 'E') MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
