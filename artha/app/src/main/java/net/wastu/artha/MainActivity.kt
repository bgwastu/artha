package net.wastu.artha

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import net.wastu.artha.ui.ArthaApp
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.theme.ArthaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var viewModelInstance: ArthaViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (BuildConfig.DEMO) keepAnimationsOn()
        val initialDest = intent?.getStringExtra("extra_destination")
        val application = application as ArthaApplication
        handlePairingIntent(intent)
        setContent {
            ArthaTheme {
                val model: ArthaViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        ArthaViewModel(
                            application.rootAccess,
                            { application.ledger },
                            ingest = {
                                if (application.bridgePreferences.hasPairing()) {
                                    if (!application.bridgeCoordinator.sync()) throw net.wastu.artha.data.sync.bridge.BridgeNoAnswerException()
                                    net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                    net.wastu.artha.ui.widget.TransactionsWidgetProvider.triggerUpdate(application)
                                    emptyMap()
                                } else if (application.rootAccess.verify()) {
                                    application.ingestion.ingestAll().also {
                                        net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                        net.wastu.artha.ui.widget.TransactionsWidgetProvider.triggerUpdate(application)
                                    }
                                } else {
                                    emptyMap()
                                }
                            },
                            ingestStale = {
                                if (application.bridgePreferences.hasPairing()) {
                                    if (!application.bridgeCoordinator.sync()) throw net.wastu.artha.data.sync.bridge.BridgeNoAnswerException()
                                    net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                    net.wastu.artha.ui.widget.TransactionsWidgetProvider.triggerUpdate(application)
                                    emptyMap()
                                } else if (application.rootAccess.verify()) {
                                    application.ingestion.ingestAll(force = false).also {
                                        net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                        net.wastu.artha.ui.widget.TransactionsWidgetProvider.triggerUpdate(application)
                                    }
                                } else {
                                    emptyMap()
                                }
                            },
                            syncSingle = {
                                if (application.bridgePreferences.hasPairing()) {
                                    if (!application.bridgeCoordinator.sync()) throw net.wastu.artha.data.sync.bridge.BridgeNoAnswerException()
                                    net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                    net.wastu.artha.ui.widget.TransactionsWidgetProvider.triggerUpdate(application)
                                    "Synced with Artha Bridge"
                                } else if (application.rootAccess.verify()) {
                                    application.ingestion.syncProvider(it).also {
                                        net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                        net.wastu.artha.ui.widget.TransactionsWidgetProvider.triggerUpdate(application)
                                    }
                                } else {
                                    "Connect to Artha Bridge in Settings to sync"
                                }
                            },
                            categorize = if (BuildConfig.DEMO) net.wastu.artha.data.demo.DemoAi::categorize else null,
                            preferences = application.providerPreferences,
                            syncingProvider = application.ingestion.syncingProvider,
                            analysisStore = application.analysisStore,
                            spendingState = application.spending.state,
                            retrySpending = application.spending::retry,
                            gojekFlashRefresh = application::refreshGojekNow,
                            hasBridgePairing = { application.bridgePreferences.hasPairing() },
                        ).also {
                            if (initialDest != null) it.setTargetDestination(initialDest)
                        }
                    }
                })
                viewModelInstance = model
                val prefs = application.bridgePreferences
                // Decided once per launch: pairing mid-wizard must not tear the wizard down
                // before it has shown whether the bridge answers.
                var showWizard by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!prefs.hasPairing()) }

                if (showWizard) {
                    net.wastu.artha.ui.wizard.OnboardingWizard(
                        onFinished = { showWizard = false }
                    )
                } else {
                    androidx.compose.foundation.layout.Box {
                        ArthaApp(model)
                        if (BuildConfig.DEMO) net.wastu.artha.ui.DemoRibbon()
                    }
                }
            }
        }
    }

    /** The demo always animates, even when the device has animations switched off. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun keepAnimationsOn() {
        val fullSpeed = object : androidx.compose.ui.MotionDurationScale {
            override val scaleFactor = 1f
        }
        androidx.compose.ui.platform.WindowRecomposerPolicy.setFactory { view ->
            view.createLifecycleAwareWindowRecomposer(fullSpeed)
        }
    }
    private fun handlePairingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "artha" && uri.host == "pair") {
            val app = application as? ArthaApplication ?: return
            if (app.bridgePreferences.pairWith(uri.toString())) {
                app.applicationScope.launch { app.bridgeCoordinator.sync() }
                android.widget.Toast.makeText(this, "Paired with Artha Bridge", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
        intent.getStringExtra("extra_destination")?.let {
            viewModelInstance?.setTargetDestination(it)
        }
    }

    override fun onResume() {
        super.onResume()
        net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(this)
        net.wastu.artha.ui.widget.TransactionsWidgetProvider.triggerUpdate(this)
    }
}
