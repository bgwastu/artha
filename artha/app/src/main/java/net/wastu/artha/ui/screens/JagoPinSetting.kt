package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.data.providers.JagoPinStatus

/**
 * Bank Jago's background PIN. It is stored here and sent to Artha Bridge with every
 * sync, so saving it also asks the bridge to sync and verify. The dialog state is hoisted
 * so the page's attention card can open it too.
 */
@Composable
internal fun JagoPinSetting(dialogOpen: Boolean, onDialogOpenChange: (Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext as? ArthaApplication ?: return
    val pinSet by app.providerPreferences.jagoPinSet.collectAsStateWithLifecycle()
    val pinCheck by app.providerPreferences.jagoPinCheck.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme

    SettingsHeading("Artha Bridge")

    val isWrong = pinSet && pinCheck.status == JagoPinStatus.WRONG
    val isVerified = pinSet && pinCheck.status == JagoPinStatus.VERIFIED

    val supportingText = when {
        !pinSet -> "Not set · Bank Jago only syncs when opened"
        isWrong -> "Incorrect PIN · Bank Jago rejected it. Tap to fix."
        isVerified -> "Verified · Bank Jago syncs in background"
        pinCheck.detail != null -> pinCheck.detail!!
        else -> "Set · waiting for bridge verification"
    }

    val iconColor = when {
        !pinSet -> colors.onSurfaceVariant
        isWrong -> colors.error
        isVerified -> colors.primary
        else -> colors.primary
    }

    val actionColor = when {
        isWrong -> colors.error
        else -> colors.primary
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isWrong) colors.errorContainer.copy(alpha = 0.4f) else colors.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    "Background PIN",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isWrong) colors.error else colors.onSurface,
                )
            },
            supportingContent = {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isWrong) colors.error else colors.onSurfaceVariant,
                )
            },
            leadingContent = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isWrong) colors.error.copy(alpha = 0.15f) else colors.surfaceContainerHighest,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val icon = when {
                            isWrong -> Icons.Default.ErrorOutline
                            isVerified -> Icons.Default.CheckCircle
                            pinSet -> Icons.Default.Lock
                            else -> Icons.Default.Lock
                        }
                        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                    }
                }
            },
            trailingContent = {
                Text(
                    text = when {
                        !pinSet -> "Set"
                        isWrong -> "Fix PIN"
                        else -> "Change"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = actionColor,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onDialogOpenChange(true) },
        )
    }

    if (dialogOpen) {
        JagoPinDialog(
            app = app,
            initial = app.providerPreferences.getJagoPin().orEmpty(),
            isWrongPin = isWrong,
            onDismiss = { onDialogOpenChange(false) },
        )
    }
}

/** Where the dialog is: typing, waiting on the bridge, or showing what Jago said. */
private sealed interface PinPhase {
    data object Editing : PinPhase
    data object Verifying : PinPhase
    data class Done(val verified: Boolean, val message: String) : PinPhase
}

/**
 * Saving sends the PIN to Artha Bridge, which opens Bank Jago and types it. The dialog
 * waits for that walk: a wrong PIN sends the user back to the field with Jago's verdict.
 */
@Composable
private fun JagoPinDialog(
    app: ArthaApplication,
    initial: String,
    isWrongPin: Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf(initial) }
    var phase by remember { mutableStateOf<PinPhase>(PinPhase.Editing) }
    var rejected by remember { mutableStateOf(isWrongPin) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val bridgeName = remember { app.bridgePreferences.getBridgeDeviceName() ?: "the bridge phone" }

    fun save() {
        val newPin = pin.ifBlank { null }
        app.providerPreferences.setJagoPin(newPin)
        if (newPin == null) {
            app.applicationScope.launch { runCatching { app.bridgeCoordinator.sync() } }
            onDismiss()
            return
        }
        phase = PinPhase.Verifying
        // The sync outlives the dialog, so closing it early still delivers the PIN.
        val request = app.applicationScope.async { runCatching { app.bridgeCoordinator.sync() }.getOrDefault(false) }
        scope.launch {
            val answered = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                request.await()
            } ?: false
            val check = app.providerPreferences.jagoPinCheck.value
            phase = when {
                check.status == JagoPinStatus.WRONG -> {
                    rejected = true
                    pin = ""
                    PinPhase.Editing
                }
                check.status == JagoPinStatus.VERIFIED ->
                    PinPhase.Done(true, "Bank Jago accepted the PIN. New transactions now sync in the background.")
                check.status == JagoPinStatus.UNVERIFIED ->
                    PinPhase.Done(false, "PIN saved, but it could not be checked: ${check.detail ?: "Bank Jago did not open"}.")
                !answered ->
                    PinPhase.Done(true, "PIN saved and sent to $bridgeName. It will verify on the next background sync.")
                else ->
                    PinPhase.Done(true, "PIN saved. It gets checked the next time Artha Bridge opens Bank Jago.")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (phase != PinPhase.Verifying) onDismiss() },
        icon = {
            when (val current = phase) {
                PinPhase.Editing -> Icon(Icons.Default.Lock, contentDescription = null)
                PinPhase.Verifying -> LoadingIndicator(Modifier.size(40.dp))
                is PinPhase.Done -> Icon(
                    if (current.verified) Icons.Default.CheckCircle else Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (current.verified) colors.primary else colors.onSurfaceVariant,
                )
            }
        },
        title = {
            Text(
                when (val current = phase) {
                    PinPhase.Editing -> "Bank Jago PIN"
                    PinPhase.Verifying -> "Checking the PIN"
                    is PinPhase.Done -> if (current.verified) "PIN verified" else "PIN saved"
                },
            )
        },
        text = {
            when (val current = phase) {
                PinPhase.Editing -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Artha Bridge types this PIN into Bank Jago on $bridgeName to fetch new transactions. " +
                            "Leave it empty to turn background sync off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 6 && it.all(Char::isDigit)) {
                                pin = it
                                rejected = false
                            }
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        label = { Text("6-digit PIN") },
                        isError = rejected,
                        supportingText = if (rejected) {
                            { Text("Bank Jago rejected this PIN. Check it and try again.") }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PinPhase.Verifying -> Text(
                    "Artha Bridge is opening Bank Jago on $bridgeName and typing the PIN. This can take a minute.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                is PinPhase.Done -> Text(
                    current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            when (phase) {
                PinPhase.Editing -> Button(
                    onClick = ::save,
                    enabled = pin.isEmpty() || pin.length == 6,
                ) { Text(if (pin.isEmpty()) "Turn off" else "Save and verify") }
                PinPhase.Verifying -> Unit
                is PinPhase.Done -> TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            when (phase) {
                PinPhase.Editing -> TextButton(onClick = onDismiss) { Text("Cancel") }
                PinPhase.Verifying -> TextButton(onClick = onDismiss) { Text("Close") }
                is PinPhase.Done -> Unit
            }
        },
    )
}
