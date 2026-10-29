package net.wastu.artha

import android.app.Application
import android.os.PowerManager
import androidx.core.content.edit
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
import net.wastu.artha.data.sync.GojekFlashController
import net.wastu.artha.data.sync.GojekFlashSync
import net.wastu.artha.data.sync.SleepFlashListener
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

    fun syncInBackground(provider: Provider? = null, force: Boolean = false): kotlinx.coroutines.Job =
        applicationScope.launch {
            if (BuildConfig.DEMO) return@launch
            try {
                if (!rootAccess.verify()) return@launch
                val result = if (provider != null) {
                    mapOf(provider to ingestion.syncProvider(provider))
                } else {
                    ingestion.ingestAll(force = force)
                }
                AnalysisWidgetProvider.triggerUpdate(this@ArthaApplication)
                TransactionsWidgetProvider.triggerUpdate(this@ArthaApplication)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Sync failed"
                for (p in provider?.let { listOf(it) } ?: providerPreferences.importEnabled.value) {
                    providerPreferences.recordSyncError(p, message)
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
            if (BuildConfig.DEMO) {
                onResult("GoPay up to date")
                return@launch
            }
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
            } catch (e: Exception) {
                android.util.Log.w("Artha", "GoPay refresh failed", e)
                onResult("GoPay refresh failed. Try again")
            }
        }
    }
    private suspend fun joinThenSummary(job: kotlinx.coroutines.Job): String? {
        job.join()
        return try {
            getSharedPreferences("provider_visibility", MODE_PRIVATE)
                .getString("GOJEK_last_summary", null)
        } catch (e: Exception) {
            android.util.Log.w("Artha", "Could not read the GoPay refresh summary", e)
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
        if (BuildConfig.DEMO) {
            resetDemoIfNewDay()
            applicationScope.launch { seedDemoLedger() }
        } else {
            BackgroundSyncScheduler.schedule(this)
            SleepFlashListener(this).register()
        }
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

    /** The demo ledger ends on the day it was made, so a new day or build starts from scratch. Runs before the database or store opens. */
    private fun resetDemoIfNewDay() {
        val state = getSharedPreferences("demo_state", MODE_PRIVATE)
        // A new day or a new build both regenerate, so a reinstalled demo never keeps old rows.
        val today = "${java.time.LocalDate.now(net.wastu.artha.core.model.AccountingTime.zone)}@${packageManager.getPackageInfo(packageName, 0).lastUpdateTime}"
        if (state.getString("seeded_on", null) == today) return
        deleteDatabase("artha-ledger.db")
        deleteSharedPreferences("artha_analysis_store")
        deleteSharedPreferences("provider_visibility")
        state.edit { putString("seeded_on", today) }
    }

    /** Fills an empty demo ledger once, through the same append and label path as a file import. */
    private suspend fun seedDemoLedger() {
        if (analysisStore.snapshot.value.aiConfig.apiKey.isBlank()) {
            analysisStore.setAiConfig(net.wastu.artha.data.analysis.AiConfig("https://ai.demo.example", "demo-key", "artha-demo-1"))
        }
        if (ledger.loadTransactions().isNotEmpty()) return
        val demo = net.wastu.artha.data.demo.DemoLedger.generate(java.time.Instant.now(), net.wastu.artha.core.model.AccountingTime.zone)
        analysisStore.mergeImportedAnalysis(null, demo.labels)
        ledger.append(demo.transactions)
        // Every source looks connected and recently synced, as it would on a rooted phone.
        val accounts = mapOf(Provider.JAGO to "Rina P. ••0482", Provider.GOJEK to "0812••••3391", Provider.GRAB to "0812••••5507", Provider.SHOPEE to "rina_demo")
        Provider.entries.forEach { provider ->
            val times = demo.transactions.filter { it.provider == provider }.map { it.occurredAt.toEpochMilli() }
            providerPreferences.recordSyncSuccess(
                provider, "${times.size} payments, up to date", accounts[provider],
                importWindow = net.wastu.artha.data.providers.ProviderImportWindow(times.size, times.minOrNull(), times.maxOrNull()),
            )
        }
    }
}
