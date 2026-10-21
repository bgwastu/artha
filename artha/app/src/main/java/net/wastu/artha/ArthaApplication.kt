package net.wastu.artha

import android.app.Application
import net.wastu.artha.core.root.LibsuRootAccess
import net.wastu.artha.data.ingestion.IngestionCoordinator
import net.wastu.artha.data.ledger.LedgerDatabase
import net.wastu.artha.data.ledger.RoomLedgerRepository
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.analysis.SpendingState
import net.wastu.artha.data.sync.BackgroundSyncScheduler
import net.wastu.artha.ui.widget.AnalysisWidgetProvider
import net.wastu.artha.ui.widget.TransactionsWidgetProvider

class ArthaApplication : Application() {
    val rootAccess by lazy { LibsuRootAccess(applicationContext) }

    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    // Stored history is readable independently of root; imports still require root.
    private val database by lazy { LedgerDatabase.create(applicationContext) }
    val ledger by lazy { RoomLedgerRepository(database.transactions()) }
    val spending by lazy { net.wastu.artha.data.analysis.SpendingRepository(ledger, analysisStore, applicationScope) }
    val providerPreferences by lazy { net.wastu.artha.data.providers.ProviderPreferences(applicationContext) }
    val analysisStore by lazy { net.wastu.artha.data.analysis.AnalysisStore(applicationContext) }
    val ingestion by lazy {
        val files = net.wastu.artha.data.ingestion.RootFileReader(applicationContext)
        val reader = net.wastu.artha.data.ingestion.JagoTransactionReader(files)
        val adapters = listOf(
            net.wastu.artha.data.ingestion.JagoProviderAdapter(reader),
            net.wastu.artha.data.ingestion.ShopeeProviderAdapter(files),
            net.wastu.artha.data.ingestion.GojekProviderAdapter(files),
            net.wastu.artha.data.ingestion.GrabProviderAdapter(files),
        )
        IngestionCoordinator(rootAccess, { ledger }, adapters, reader::invalidate, preferences = providerPreferences)
    }

    fun syncInBackground(provider: Provider? = null, force: Boolean = false): kotlinx.coroutines.Job =
        applicationScope.launch {
            try {
                if (!rootAccess.verify()) return@launch
                val result = if (provider != null) {
                    mapOf(provider to ingestion.syncProvider(provider))
                } else {
                    ingestion.ingestAll(force = force)
                }
                AnalysisWidgetProvider.triggerUpdate(this@ArthaApplication)
                TransactionsWidgetProvider.triggerUpdate(this@ArthaApplication)
            } catch (_: Exception) {
            }
        }

    fun syncInBackgroundIfStale() {
        val anyStale = providerPreferences.importEnabled.value.any { providerPreferences.isStale(it) }
        if (anyStale) {
            syncInBackground(force = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        BackgroundSyncScheduler.schedule(this)
        applicationScope.launch {
            spending.state
                .filterIsInstance<SpendingState.Ready>()
                .distinctUntilChangedBy { ready ->
                    ready.transactions.size to (ready.transactions.firstOrNull()?.key ?: "")
                }
                .drop(1)
                .collect {
                    AnalysisWidgetProvider.triggerUpdate(this@ArthaApplication)
                    TransactionsWidgetProvider.triggerUpdate(this@ArthaApplication)
                }
        }
    }
}
