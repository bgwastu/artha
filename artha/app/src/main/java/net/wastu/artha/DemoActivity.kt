package net.wastu.artha

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.wastu.artha.ui.LedgerState
import net.wastu.artha.ui.screens.HomeScreen
import net.wastu.artha.ui.theme.ArthaTheme

/** A separate in-memory session. Neither normal app services nor personal storage are used. */
open class DemoActivity : ComponentActivity() {
    protected open val screenshotMode = false
    protected open fun onDemoReady() = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model = viewModel<net.wastu.artha.ui.ArthaViewModel>(factory = viewModelFactory {
                initializer { demoModel().also {
                    if (screenshotMode) it.setTargetDestination(intent.getStringExtra("destination"))
                    it.retryLedger()
                } }
            })
            val ledger by model.ledgerState.collectAsStateWithLifecycle()
            val dark = if (screenshotMode) intent.getBooleanExtra("dark", false) else isSystemInDarkTheme()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (screenshotMode) Density(density.density, 1f) else density) {
                ArthaTheme(darkTheme = dark, dynamicColor = !screenshotMode) {
                    val insets = if (screenshotMode) Modifier.consumeWindowInsets(WindowInsets.displayCutout) else Modifier
                    Box(Modifier.fillMaxSize().then(insets)) {
                        Surface {
                            HomeScreen(ledger, onRetryLedger = model::retryLedger, viewModel = model,
                                onExitDemo = if (screenshotMode) null else ({
                                    if (DemoMode.setEnabled(this@DemoActivity, false)) {
                                        startActivity(android.content.Intent(this@DemoActivity, MainActivity::class.java)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
                                        finish()
                                    } else android.widget.Toast.makeText(this@DemoActivity, "Couldn't exit demo mode", android.widget.Toast.LENGTH_SHORT).show()
                                }))
                        }
                    }
                }
            }
            LaunchedEffect(ledger is LedgerState.Ready) { if (ledger is LedgerState.Ready) onDemoReady() }
        }
    }
}
