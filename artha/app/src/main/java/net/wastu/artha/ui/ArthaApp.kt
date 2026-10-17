package net.wastu.artha.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.ui.screens.HomeScreen

@Composable
fun ArthaApp(model: ArthaViewModel) {
    val ledger by model.ledgerState.collectAsStateWithLifecycle()
    val enabled by model.enabledProviders.collectAsStateWithLifecycle()
    val refreshing by model.refreshing.collectAsStateWithLifecycle()
    val syncingProvider by model.syncingProvider.collectAsStateWithLifecycle()
    val providerCounts by model.providerCounts.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.enterForeground() }

    Surface {
        HomeScreen(
            ledgerState = ledger,
            enabledProviders = enabled,
            refreshing = refreshing,
            syncingProvider = syncingProvider,
            providerCounts = providerCounts,
            onRefresh = model::refresh,
            onProviderEnabled = model::setProviderEnabled,
            onRetryLedger = model::retryLedger,
            viewModel = model,
        )
    }
}
