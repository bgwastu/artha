package net.wastu.artha

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.wastu.artha.data.demo.DemoAi
import net.wastu.artha.ui.ArthaApp
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.DemoRibbon
import net.wastu.artha.ui.theme.ArthaTheme
import net.wastu.artha.ui.widget.AnalysisWidgetProvider
import net.wastu.artha.ui.widget.TransactionsWidgetProvider

class MainActivity : ComponentActivity() {
    private var viewModelInstance: ArthaViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (BuildConfig.DEMO) keepAnimationsOn()
        val initialDest = intent?.getStringExtra("extra_destination")
        val application = application as ArthaApplication
        // The view model has already verified root; each import just refreshes the widgets afterwards.
        suspend fun <T> synced(block: suspend () -> T): T = block().also {
            AnalysisWidgetProvider.triggerUpdate(application)
            TransactionsWidgetProvider.triggerUpdate(application)
        }
        setContent {
            ArthaTheme {
                val model: ArthaViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        ArthaViewModel(
                            application.rootAccess,
                            { application.ledger },
                            ingest = { synced { application.ingestion.ingestAll() } },
                            ingestStale = { synced { application.ingestion.ingestAll(force = false) } },
                            syncSingle = { provider -> synced { application.ingestion.syncProvider(provider) } },
                            categorize = if (BuildConfig.DEMO) DemoAi::categorize else null,
                            preferences = application.providerPreferences,
                            syncingProvider = application.ingestion.syncingProvider,
                            analysisStore = application.analysisStore,
                            spendingState = application.spending.state,
                            retrySpending = application.spending::retry,
                            gojekFlashRefresh = application::refreshGojekNow,
                        ).also {
                            if (initialDest != null) it.setTargetDestination(initialDest)
                        }
                    }
                })
                viewModelInstance = model
                Box {
                    ArthaApp(model)
                    if (BuildConfig.DEMO) DemoRibbon()
                }
            }
        }
    }

    /** The demo always animates, even when the device has animations switched off. */
    @OptIn(InternalComposeUiApi::class)
    private fun keepAnimationsOn() {
        val fullSpeed = object : MotionDurationScale {
            override val scaleFactor = 1f
        }
        WindowRecomposerPolicy.setFactory { view ->
            view.createLifecycleAwareWindowRecomposer(fullSpeed)
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
        AnalysisWidgetProvider.triggerUpdate(this)
        TransactionsWidgetProvider.triggerUpdate(this)
    }
}
