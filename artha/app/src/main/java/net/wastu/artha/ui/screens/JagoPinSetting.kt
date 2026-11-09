package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import net.wastu.artha.ArthaApplication
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.providers.JagoPinStatus

/**
 * Bank Jago's background PIN. Artha types it into Bank Jago on a hidden display during
 * syncs, and the result of the last attempt shows here. The dialog state is hoisted
 * so the page's attention card can open it too.
 */
@Composable
internal fun JagoPinSetting(dialogOpen: Boolean, onDialogOpenChange: (Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext as? ArthaApplication ?: return
    val pinSet by app.providerPreferences.jagoPinSet.collectAsStateWithLifecycle()
    val pinCheck by app.providerPreferences.jagoPinCheck.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme

    SettingsHeading("Background sync")

    val isWrong = pinSet && pinCheck.status == JagoPinStatus.WRONG
    val isVerified = pinSet && pinCheck.status == JagoPinStatus.VERIFIED

    val supportingText = when {
        !pinSet -> "Not set · Bank Jago only syncs when opened"
        isWrong -> "Incorrect PIN · Bank Jago rejected it. Tap to fix."
        isVerified -> "Verified · Bank Jago syncs in background"
        pinCheck.detail != null -> pinCheck.detail!!
        else -> "Set · checked on the next sync"
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
        shape = MaterialTheme.shapes.medium,
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
                    shape = MaterialTheme.shapes.small,
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

@Composable
private fun JagoPinDialog(
    app: ArthaApplication,
    initial: String,
    isWrongPin: Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf(initial) }
    var rejected by remember { mutableStateOf(isWrongPin) }
    val colors = MaterialTheme.colorScheme

    fun save() {
        app.providerPreferences.setJagoPin(pin.ifBlank { null })
        // A new PIN is checked by the very next Bank Jago sync.
        if (pin.isNotBlank()) app.syncInBackground(Provider.JAGO, force = true)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Bank Jago PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Artha types this PIN into Bank Jago on a hidden display to fetch new transactions. " +
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
        },
        confirmButton = {
            Button(onClick = ::save, enabled = pin.isEmpty() || pin.length == 6) {
                Text(if (pin.isEmpty()) "Turn off" else "Save and verify")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
