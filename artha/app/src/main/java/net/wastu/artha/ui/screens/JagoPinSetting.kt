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
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.providers.JagoPinStatus

/** Where the background PIN stands, from "never set" to "Bank Jago rejected it". */
private enum class PinState { NOT_SET, SET, VERIFIED, WRONG }

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
    val state = when {
        !pinSet -> PinState.NOT_SET
        pinCheck.status == JagoPinStatus.WRONG -> PinState.WRONG
        pinCheck.status == JagoPinStatus.VERIFIED -> PinState.VERIFIED
        else -> PinState.SET
    }

    SettingsHeading("Background sync")
    JagoPinRow(state, detail = pinCheck.detail, onClick = { onDialogOpenChange(true) })

    if (dialogOpen) {
        JagoPinDialog(
            app = app,
            initial = app.providerPreferences.getJagoPin().orEmpty(),
            isWrongPin = state == PinState.WRONG,
            onDismiss = { onDialogOpenChange(false) },
        )
    }
}

@Composable
private fun JagoPinRow(state: PinState, detail: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val wrong = state == PinState.WRONG
    val supportingText = when (state) {
        PinState.NOT_SET -> "Bank Jago only syncs while it is open. Add the PIN to sync in the background."
        PinState.WRONG -> "Bank Jago rejected this PIN. Tap to fix it."
        PinState.VERIFIED -> "Verified. Bank Jago syncs in the background."
        PinState.SET -> detail ?: "Saved. It is checked on the next sync."
    }
    val icon = when (state) {
        PinState.WRONG -> Icons.Default.ErrorOutline
        PinState.VERIFIED -> Icons.Default.CheckCircle
        else -> Icons.Default.Lock
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (wrong) colors.errorContainer.copy(alpha = 0.4f) else colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    "Background PIN",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (wrong) colors.error else colors.onSurface,
                )
            },
            supportingContent = {
                Text(supportingText, style = MaterialTheme.typography.bodySmall, color = if (wrong) colors.error else colors.onSurfaceVariant)
            },
            leadingContent = {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (wrong) colors.error.copy(alpha = 0.15f) else colors.surfaceContainerHighest,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val tint = when (state) {
                            PinState.NOT_SET -> colors.onSurfaceVariant
                            PinState.WRONG -> colors.error
                            else -> colors.primary
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
            },
            trailingContent = {
                Text(
                    text = when (state) {
                        PinState.NOT_SET -> "Set"
                        PinState.WRONG -> "Fix PIN"
                        else -> "Change"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (wrong) colors.error else colors.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick),
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
    var storeFailed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    /** Saves the PIN, then tries it on Bank Jago right now and reports what happened here. */
    fun save() {
        // Sealing needs the Android Keystore; if it still refuses, say so rather than closing the app.
        if (runCatching { app.providerPreferences.setJagoPin(pin.ifBlank { null }) }.isFailure) {
            storeFailed = true
            return
        }
        if (pin.isBlank()) {
            onDismiss()
            return
        }
        checking = true
        notice = null
        scope.launch {
            // Bank Jago's answer to the PIN is known within seconds; its cache refresh carries on in the background.
            val sync = app.syncInBackground(Provider.JAGO, force = true, interactive = true)
            val answer = async { app.providerPreferences.jagoPinCheck.first { it.status != JagoPinStatus.UNVERIFIED } }
            sync.invokeOnCompletion { answer.cancel() }
            runCatching { answer.await() }
            checking = false
            val check = app.providerPreferences.jagoPinCheck.value
            when (check.status) {
                JagoPinStatus.VERIFIED -> onDismiss()
                JagoPinStatus.WRONG -> rejected = true
                else -> {
                    // No verdict recorded means the sync never reached Bank Jago; its own error says why.
                    val why = check.detail ?: app.providerPreferences.diagnostics.value[Provider.JAGO]?.lastError ?: "The sync did not reach Bank Jago"
                    notice = "${why.trimEnd('.')}. Saved; it is checked again on the next sync."
                }
            }
        }
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
                            storeFailed = false
                            notice = null
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    label = { Text("6-digit PIN") },
                    enabled = !checking,
                    isError = rejected || storeFailed,
                    supportingText = when {
                        checking -> { { Text("Checking with Bank Jago. This takes a few seconds.") } }
                        storeFailed -> { { Text("Couldn’t store the PIN securely on this device. Try again.") } }
                        rejected -> { { Text("Bank Jago rejected this PIN. Check it and try again.") } }
                        notice != null -> { { Text(notice.orEmpty()) } }
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = ::save, enabled = !checking && !rejected && (pin.isEmpty() || pin.length == 6)) {
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (pin.isEmpty()) "Turn off" else "Save and verify")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
