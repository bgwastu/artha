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
import net.wastu.artha.ui.ArthaApp
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.theme.ArthaTheme

class MainActivity : ComponentActivity() {
    private var viewModelInstance: ArthaViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val initialDest = intent?.getStringExtra("extra_destination")
        val application = application as ArthaApplication
        setContent {
            ArthaTheme {
                val model: ArthaViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        ArthaViewModel(
                            application.rootAccess,
                            { application.ledger },
                            ingest = {
                                application.ingestion.ingestAll().also {
                                    net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                }
                            },
                            syncSingle = {
                                application.ingestion.syncProvider(it).also {
                                    net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(application)
                                }
                            },
                            preferences = application.providerPreferences,
                            syncingProvider = application.ingestion.syncingProvider,
                            analysisStore = application.analysisStore,
                            driveSyncManager = application.driveSyncManager,
                            spendingState = application.spending.state,
                        ).also {
                            if (initialDest != null) it.setTargetDestination(initialDest)
                        }
                    }
                })
                viewModelInstance = model
                ArthaApp(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("extra_destination")?.let {
            viewModelInstance?.setTargetDestination(it)
        }
    }

    override fun onResume() {
        super.onResume()
        net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(this)
    }
}
