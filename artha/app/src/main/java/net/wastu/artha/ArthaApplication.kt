package net.wastu.artha

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import net.wastu.artha.core.root.LibsuRootAccess
import net.wastu.artha.data.ingestion.IngestionCoordinator
import net.wastu.artha.data.ledger.LedgerDatabase
import net.wastu.artha.data.ledger.RoomLedgerRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.analysis.SpendingState
import net.wastu.artha.data.sync.BackgroundSyncScheduler
import net.wastu.artha.data.sync.GojekFlashController
import net.wastu.artha.data.sync.GojekFlashPolicy
import net.wastu.artha.data.sync.GojekFlashSync
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

    val gojekFlash by lazy {
        GojekFlashController(
            net.wastu.artha.data.ingestion.RootFileReader(this),
            providerPreferences,
            { getSystemService(PowerManager::class.java)?.isInteractive == true },
        )
    }
    private val sleepFlashGeneration = AtomicLong(0)

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

    /**
     * Manual GOJEK refresh: refreshes GoPay invisibly via the deep-link flash and imports right
     * away. When the screen is awake the flash refuses to launch, so the refresh answers with
     * the next idle window instead of opening Gojek visibly.
     */
    fun syncGojekFlashNow(onResult: (String) -> Unit) {
        applicationScope.launch {
            try {
                if (!rootAccess.verify()) {
                    onResult("Root access unavailable. Allow root, then try again")
                    return@launch
                }
                val goesEnabled = providerPreferences.importEnabled.value.contains(Provider.GOJEK)
                val flash = gojekFlash.run(goesEnabled, ignoreCooldown = true)
                onResult(when (flash) {
                    GojekFlashSync.Result.FLASHED ->
                        joinThenSummary(syncInBackground(Provider.GOJEK, force = true)) ?: "GoPay refreshed"
                    GojekFlashSync.Result.QUIET, GojekFlashSync.Result.ABORTED ->
                        (joinThenSummary(syncInBackground(Provider.GOJEK, force = true)) ?: "").let { if (it.isBlank()) "GoPay up to date" else it }
                    GojekFlashSync.Result.SKIPPED_SCREEN_ON ->
                        "GoPay syncs automatically in the background; the next refresh happens after the screen turns off"
                    GojekFlashSync.Result.SKIPPED ->
                        "Gojek import is disabled. Enable it in provider details"
                    GojekFlashSync.Result.ROOT_UNAVAILABLE ->
                        "Root access unavailable. Allow root, then try again"
                    GojekFlashSync.Result.BUSY ->
                        "A GoPay refresh is already running"
                    GojekFlashSync.Result.SKIPPED_COOLDOWN ->
                        "GoPay already refreshed recently"
                })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                onResult("GoPay refresh failed. Try again")
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

    /**
     * Screen-off waits two seconds without a wakelock; a flick wakes the phone and this
     * generation counter drops the pending work. An in-flight launch is aborted and force-stopped.
     */
    private fun registerDisplayListener() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        sleepFlashGeneration.incrementAndGet()
                        gojekFlash.abortIfRunning()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        val last = providerPreferences.gojekFlash.value
                        if (GojekFlashPolicy.cooldownActive(last.result, last.millis, System.currentTimeMillis())) return
                        if (!providerPreferences.importEnabled.value.contains(Provider.GOJEK)) return
                        val generation = sleepFlashGeneration.incrementAndGet()
                        applicationScope.launch {
                            delay(GojekFlashPolicy.SLEEP_CONFIRM_MILLIS)
                            if (generation != sleepFlashGeneration.get()) return@launch
                            if (!rootAccess.verify()) return@launch
                            val flash = gojekFlash.run(
                                providerPreferences.importEnabled.value.contains(Provider.GOJEK),
                            )
                            if (flash == GojekFlashSync.Result.FLASHED) {
                                syncInBackground(Provider.GOJEK, force = true)
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        BackgroundSyncScheduler.schedule(this)
        registerDisplayListener()
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
