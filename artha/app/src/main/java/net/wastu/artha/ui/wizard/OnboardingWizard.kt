package net.wastu.artha.ui.wizard

import net.wastu.artha.ui.components.ShapeBadge
import net.wastu.artha.ui.components.ConnectedChoiceRow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.async
import net.wastu.artha.ArthaApplication
import net.wastu.artha.ui.bridge.CameraQrScanner
import net.wastu.artha.ui.bridge.QrGenerator

private const val BRIDGE_APK_URL = "https://air--8899.getbb.app/artha-bridge.apk"

private enum class WizardStep { WELCOME, INSTALL, PAIR, CONNECT }

/**
 * First-run setup: what Artha Bridge is, installing it on the second phone, pairing, and
 * waiting for the bridge's first answer so the user knows the pairing actually works.
 */
@Composable
fun OnboardingWizard(onFinished: () -> Unit) {
    val app = LocalContext.current.applicationContext as? ArthaApplication
    var step by rememberSaveable { mutableStateOf(WizardStep.WELCOME) }

    BackHandler(enabled = step == WizardStep.INSTALL || step == WizardStep.PAIR) {
        step = WizardStep.entries[step.ordinal - 1]
    }

    // A Surface rather than a background, so text picks up the theme's content colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f),
            label = "wizard_step",
        ) { current ->
            when (current) {
                WizardStep.WELCOME -> WelcomeStep(onNext = { step = WizardStep.INSTALL })
                WizardStep.INSTALL -> InstallStep(onNext = { step = WizardStep.PAIR })
                WizardStep.PAIR -> PairStep(
                    onCode = { code ->
                        val paired = app?.bridgePreferences?.pairWith(code) == true
                        if (paired) step = WizardStep.CONNECT
                        paired
                    },
                )
                WizardStep.CONNECT -> ConnectStep(app = app, onFinish = onFinished)
            }
        }

        if (step != WizardStep.CONNECT) {
            WizardBar(
                step = step,
                onBack = { step = WizardStep.entries[step.ordinal - 1] },
                onSkip = onFinished,
            )
        }
    } }
}

/** Back on the left, Skip on the right, and the step dots centred on the screen between them. */
@Composable
private fun WizardBar(step: WizardStep, onBack: () -> Unit, onSkip: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 48.dp),
    ) {
        if (step != WizardStep.WELCOME) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back")
            }
        }
        StepDots(current = step.ordinal, total = WizardStep.entries.size, modifier = Modifier.align(Alignment.Center))
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterEnd)) {
            Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            val width by animateDpAsState(if (index == current) 24.dp else 8.dp, label = "dot_width")
            val color by animateColorAsState(
                if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dot_color",
            )
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
        }
    }
}

/**
 * One layout for every step: a centred icon, title and explanation, the step's own content,
 * and the main action pinned under it. Keeping it shared keeps the steps aligned alike.
 */
@Composable
private fun StepLayout(
    icon: ImageVector,
    title: String,
    body: String,
    action: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepHeader(icon, title, body)
            content()
            Spacer(Modifier.height(24.dp))
        }
        Spacer(Modifier.height(16.dp))
        action()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        ShapeBadge(icon)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, shapes = ButtonDefaults.shapes(), enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepLayout(
        icon = Icons.Default.AccountBalanceWallet,
        title = "Welcome to Artha",
        body = "Artha collects your bank and e-wallet transactions into one ledger on this phone.",
        action = { PrimaryAction("Set up Artha Bridge", onNext) },
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                FeatureRow(
                    icon = Icons.Default.PhonelinkLock,
                    title = "This phone stays unrooted",
                    body = "Your banking apps keep working here as they always have.",
                )
                FeatureRow(
                    icon = Icons.Default.Devices,
                    title = "A second phone reads the apps",
                    body = "Artha Bridge runs on a rooted phone signed in to the same accounts and sends new transactions here.",
                )
                FeatureRow(
                    icon = Icons.Default.Lock,
                    title = "Encrypted between your phones",
                    body = "Only your paired phones hold the key, so the relays in between can't read anything.",
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InstallStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val qr: Bitmap? = remember { runCatching { QrGenerator.encode(BRIDGE_APK_URL, 512) }.getOrNull() }

    StepLayout(
        icon = Icons.Default.InstallMobile,
        title = "Install Artha Bridge",
        body = "Install it on a second Android phone rooted with Magisk, KernelSU or APatch, " +
            "and sign in to your bank and e-wallet apps there.",
        action = { PrimaryAction("It's installed", onNext) },
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Scan with the second phone", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(16.dp))
                if (qr != null) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "QR code for the Artha Bridge download",
                            modifier = Modifier.padding(12.dp).size(176.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, BRIDGE_APK_URL.toUri())) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Artha Bridge download", BRIDGE_APK_URL))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy link")
                    }
                }
            }
        }
    }
}

/** [onCode] pairs from a scanned or typed code and returns whether it was a pairing code. */
@Composable
private fun PairStep(onCode: (String) -> Boolean) {
    val context = LocalContext.current
    var useCamera by rememberSaveable { mutableStateOf(true) }
    var code by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    StepLayout(
        icon = Icons.Default.QrCodeScanner,
        title = "Pair with Artha Bridge",
        body = "Open Artha Bridge on the second phone. It shows a pairing code: scan it, or copy it over.",
        action = {
            if (!useCamera) {
                PrimaryAction(
                    text = "Pair",
                    enabled = code.isNotBlank(),
                    onClick = { invalid = !onCode(code) },
                )
            }
        },
    ) {
        ConnectedChoiceRow(
            options = listOf(true, false),
            selected = useCamera,
            onSelect = { useCamera = it },
            label = { if (it) "Scan code" else "Enter code" },
        )
        Spacer(Modifier.height(20.dp))

        if (useCamera) {
            CameraQrScanner(
                onQrScanned = { scanned ->
                    onCode(scanned).also { ok ->
                        if (!ok) Toast.makeText(context, "That is not an Artha Bridge pairing code", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    invalid = false
                },
                label = { Text("Pairing code") },
                placeholder = { Text("artha://pair?key=…") },
                isError = invalid,
                supportingText = {
                    Text(if (invalid) "That is not an Artha Bridge pairing code" else "Copy it from Artha Bridge and paste it here")
                },
                singleLine = true,
                // A key, not prose: no autocorrect rewriting its letters.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    if (clip.isNotBlank()) {
                        code = clip.trim()
                        invalid = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste")
            }
        }
    }
}

private enum class ConnectState { WAITING, CONNECTED, NO_ANSWER }

/** Asks the freshly paired bridge for its first sync, so a bad pairing shows up here. */
@Composable
private fun ConnectStep(app: ArthaApplication?, onFinish: () -> Unit) {
    var state by remember { mutableStateOf(ConnectState.WAITING) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        state = ConnectState.WAITING
        if (app == null) {
            state = ConnectState.NO_ANSWER
            return@LaunchedEffect
        }
        // The sync belongs to the app, so leaving this screen doesn't cancel it.
        val answered = app.applicationScope
            .async { runCatching { app.bridgeCoordinator.sync() }.getOrDefault(false) }
            .await()
        state = if (answered) ConnectState.CONNECTED else ConnectState.NO_ANSWER
    }

    val bridgeName = app?.bridgePreferences?.getBridgeDeviceName()
    when (state) {
        ConnectState.WAITING -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LoadingIndicator(Modifier.size(72.dp))
            Spacer(Modifier.height(24.dp))
            Text("Waiting for Artha Bridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Paired. Artha Bridge usually answers within a minute while it fetches your transactions.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        ConnectState.CONNECTED -> StepLayout(
            icon = Icons.Default.CheckCircle,
            title = "Connected",
            body = "${bridgeName ?: "Artha Bridge"} answered and sent your transactions. " +
                "New ones keep arriving in the background.",
            action = { PrimaryAction("Open Artha", onFinish) },
        ) {}

        ConnectState.NO_ANSWER -> StepLayout(
            icon = Icons.Default.CloudOff,
            title = "No answer yet",
            body = "Pairing is saved, but Artha Bridge didn't answer. Check that it's open on the second " +
                "phone and online, then try again.",
            action = {
                PrimaryAction("Try again", onClick = { attempt++ })
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Continue anyway") }
            },
        ) {}
    }
}
