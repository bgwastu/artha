package net.wastu.artha.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.api.service.ApiServerController
import net.wastu.artha.api.tunnel.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiServerSheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val isRunning by ApiServerController.isRunning.collectAsStateWithLifecycle()
    val tunnelState by ApiServerController.tunnelState.collectAsStateWithLifecycle()
    val publicUrl by ApiServerController.publicUrl.collectAsStateWithLifecycle()


    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "API & Agent Access",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Cloudflare Quick Tunnel (Free & Open)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = isRunning,
                    onCheckedChange = { start ->
                        if (start) {
                            ApiServerController.start(context)
                        } else {
                            ApiServerController.stop(context)
                        }
                    },
                )
            }

            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (tunnelState) {
                        is TunnelState.Active -> MaterialTheme.colorScheme.primaryContainer
                        is TunnelState.Failed -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (val state = tunnelState) {
                        is TunnelState.Active -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Online via Cloudflare",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }

                            Text(
                                text = state.url,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Artha URL", state.url))
                                        Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy URL")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.url))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open Docs")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val toolsUrl = "${state.url}/api/v1/agent/tools"
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Agent Tools URL", toolsUrl))
                                    Toast.makeText(context, "Agent Tools URL copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy AI Agent Tools Link")
                            }

                            OutlinedButton(
                                onClick = {
                                    ApiServerController.disconnect(context)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Disconnect Server")
                            }
                        }

                        is TunnelState.Downloading -> {
                            Text(
                                text = "Downloading Cloudflare tunnel connector (${(state.progress * 100).toInt()}%)...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is TunnelState.Starting -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Connecting to Cloudflare edge network...")
                            }
                        }

                        is TunnelState.Failed -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Tunnel failed: ${state.error}",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Button(
                                onClick = {
                                    ApiServerController.stop(context)
                                    ApiServerController.start(context)
                                },
                            ) {
                                Text("Retry")
                            }
                        }

                        is TunnelState.Inactive -> {
                            Text(
                                text = "Server is stopped. Toggle switch above to start the API and generate an online Cloudflare Quick Tunnel link for AI agents or scripts.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }


            // Available Endpoints Reference
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Available Endpoints",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    EndpointRow("GET", "/docs", "Interactive Scalar UI playground")
                    EndpointRow("GET", "/api/v1/openapi.json", "Full OpenAPI 3.1 schema")
                    EndpointRow("GET", "/api/v1/agent/tools", "AI agent tools definition")
                    EndpointRow("GET", "/api/v1/transactions", "List transactions with filters")
                    EndpointRow("GET", "/api/v1/transactions/{key}", "Transaction details & receipts")
                    EndpointRow("PATCH", "/api/v1/transactions/{key}", "Edit category / user note")
                    EndpointRow("POST", "/api/v1/transactions/batch", "Batch update transactions")
                    EndpointRow("GET", "/api/v1/summary", "Month spending & category shares")
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(method: String, path: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = when (method) {
                "GET" -> MaterialTheme.colorScheme.primaryContainer
                "POST" -> MaterialTheme.colorScheme.tertiaryContainer
                "PATCH" -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = method,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
