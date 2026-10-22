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

    /** Opens the visible Gojek app, walks its history, then imports what its own fetches produced. */
    private val gojekWalkRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun syncGojekWithHistoryWalk() {
        val walker = net.wastu.artha.data.sync.GojekHistoryWalk(this, rootAccess)
        if (!gojekWalkRunning.compareAndSet(false, true)) {
            toast("A history walk is already running")
            return
        }
        toast("Opening Gojek automatically. Leave the phone alone until the import finishes.")
        applicationScope.launch {
            try {
                // Only walk newer than data Artha already holds: the ledger's oldest
                // GoPay payment and any previous walk floor are already covered.
                val ledgerOldest = try { ledger.loadTransactions() } catch (_: Exception) { emptyList<net.wastu.artha.core.model.UnifiedTransaction>() }
                val gojekOldest = ledgerOldest.filter { it.provider == Provider.GOJEK }
                    .minOfOrNull { it.occurredAt.toEpochMilli() } ?: Long.MAX_VALUE
                val floor = minOf(gojekOldest, net.wastu.artha.data.sync.GojekHistoryWalk.walkFloor(this@ArthaApplication))
                val stopAt = if (floor == Long.MAX_VALUE) {
                    java.time.Instant.parse("2023-01-02T00:00:00Z").toEpochMilli()
                } else floor

                val result = try {
                    walker.walk(stopAt, { message -> toast(message) }) {
                        syncInBackground(Provider.GOJEK, force = true).join()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    net.wastu.artha.data.sync.GojekWalkResult.FAILED
                }
                when (result) {
                    net.wastu.artha.data.sync.GojekWalkResult.WALKED -> {
                        val summary = syncInBackground(Provider.GOJEK, force = true).let { job -> joinThenSummary(job) }
                        if (summary != null) toast("GoPay import finished: $summary")
                        else toast("GoPay history refresh finished")
                    }
                    net.wastu.artha.data.sync.GojekWalkResult.INTERRUPTED ->
                        toast("Walk paused: the screen left Gojek. Retry when the phone is free")
                    net.wastu.artha.data.sync.GojekWalkResult.GOJEK_MISSING ->
                        toast("Gojek is not installed on this phone")
                    net.wastu.artha.data.sync.GojekWalkResult.ROOT_UNAVAILABLE ->
                        toast("Root access unavailable. Allow root, then try again")
                    net.wastu.artha.data.sync.GojekWalkResult.HISTORY_SCREEN_NOT_FOUND ->
                        toast("Could not reach Gojek's history screen; open it once manually, then try again")
                    else -> toast("History refresh failed. Try again")
                }
            } finally {
                gojekWalkRunning.set(false)
            }
        }
    }

    private suspend fun joinThenSummary(job: kotlinx.coroutines.Job): String? {
        job.join()
        return try {
            getSharedPreferences("provider_visibility", MODE_PRIVATE)
                .getString("GOJEK_last_summary", null)
        } catch (_: Exception) {
            null
        }
    }

    private fun toast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
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
