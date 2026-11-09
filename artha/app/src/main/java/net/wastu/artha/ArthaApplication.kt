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
import net.wastu.artha.data.providers.JagoPinStatus
import net.wastu.artha.data.sync.GojekScreenSync
import net.wastu.artha.data.sync.JagoScreenSync
import net.wastu.artha.notifications.NotificationAccess
import net.wastu.artha.notifications.TransactionAlerts
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
    val attention by lazy { net.wastu.artha.data.attention.AttentionRepository(providerPreferences, applicationScope) }
    val analysisStore by lazy { net.wastu.artha.data.analysis.AnalysisStore(applicationContext) }
    private val rootFiles by lazy { net.wastu.artha.data.ingestion.RootFileReader(applicationContext) }
    val ingestion by lazy {
        val files = rootFiles
        val reader = net.wastu.artha.data.ingestion.JagoTransactionReader(files)
        val isInteractive = { getSystemService(PowerManager::class.java)?.isInteractive == true }
        val jagoSync = JagoScreenSync(
            files,
            isInteractive,
            { false },
            { providerPreferences.getJagoPin() },
        )
        val sessionWake = net.wastu.artha.data.sync.AppSessionWakeSync(files, isInteractive, { false })
        val adapters = listOf(
            net.wastu.artha.data.ingestion.JagoProviderAdapter(reader, jagoSync) { result ->
                when (result) {
                    JagoScreenSync.Result.SYNCED, JagoScreenSync.Result.QUIET ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.VERIFIED, "Bank Jago accepted the PIN")
                    JagoScreenSync.Result.WRONG_PIN ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.WRONG, "Bank Jago rejected the PIN")
                    JagoScreenSync.Result.UNLOCK_FAILED ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.UNVERIFIED, "Could not reach Bank Jago's PIN screen")
                    else -> Unit
                }
            },
            net.wastu.artha.data.ingestion.ShopeeProviderAdapter(files, sessionWake),
            net.wastu.artha.data.ingestion.GojekProviderAdapter(files),
            net.wastu.artha.data.ingestion.GrabProviderAdapter(files, sessionWake),
        )
        IngestionCoordinator(rootAccess, { ledger }, adapters, reader::invalidate, preferences = providerPreferences)
    }

    val gojekFlash by lazy {
        GojekFlashController(
            rootFiles,
            providerPreferences,
            { getSystemService(PowerManager::class.java)?.isInteractive == true },
            { ledger.loadTransactions() },
        )
    }
    private val notificationAccess by lazy { NotificationAccess(this, rootFiles) }
    val transactionAlerts by lazy { TransactionAlerts(this, ledger) { isAppInForeground } }

    val updater by lazy {
        net.wastu.artha.core.update.AppUpdater(
            this,
            net.wastu.artha.core.update.SessionUpdateInstaller(this),
        )
    }

    private var startedActivities = 0
    val isAppInForeground: Boolean get() = startedActivities > 0

    fun syncInBackground(provider: Provider? = null, force: Boolean = false): kotlinx.coroutines.Job =
        applicationScope.launch {
            if (BuildConfig.DEMO) return@launch
            try {
                if (!rootAccess.verify()) return@launch
                transactionAlerts.around {
                    if (provider != null) ingestion.syncProvider(provider) else ingestion.ingestAll(force = force)
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

    private val pendingNotificationSyncs = java.util.concurrent.ConcurrentHashMap<Provider, kotlinx.coroutines.Job>()

    /**
     * A provider app just posted a payment notification: import it after a short pause (so the app can
     * write its own history first), at most once every couple of minutes per source.
     */
    fun syncAfterProviderNotification(provider: Provider) {
        if (BuildConfig.DEMO || provider !in providerPreferences.importEnabled.value) return
        val lastAttempt = providerPreferences.diagnostics.value[provider]?.lastAttemptEpochMillis ?: 0L
        if (System.currentTimeMillis() - lastAttempt < NOTIFICATION_SYNC_MIN_GAP_MILLIS) return
        pendingNotificationSyncs.remove(provider)?.cancel()
        pendingNotificationSyncs[provider] = applicationScope.launch {
            kotlinx.coroutines.delay(NOTIFICATION_SYNC_DELAY_MILLIS)
            syncInBackground(provider, force = true).join()
        }
    }

    /**
     * Manual GOJEK refresh: reads new payments from GoPay's history screens on an invisible
     * display, imports right away, and returns the message to show. The caller owns the progress UI.
     */
    suspend fun refreshGojekNow(): String {
        if (BuildConfig.DEMO) return "GoPay up to date"
        return try {
            val goesEnabled = providerPreferences.importEnabled.value.contains(Provider.GOJEK)
            when (gojekFlash.run(goesEnabled, ignoreCooldown = true)) {
                GojekScreenSync.Result.FLASHED ->
                    joinThenSummary(syncInBackground(Provider.GOJEK, force = true)) ?: "GoPay refreshed"
                GojekScreenSync.Result.QUIET, GojekScreenSync.Result.ABORTED ->
                    joinThenSummary(syncInBackground(Provider.GOJEK, force = true))?.takeIf { it.isNotBlank() } ?: "GoPay up to date"
                GojekScreenSync.Result.SKIPPED_SCREEN_OFF -> "Turn the screen on, then try again"
                GojekScreenSync.Result.SKIPPED_GOJEK_IN_USE -> "Close Gojek, then try again"
                GojekScreenSync.Result.SKIPPED -> "Gojek import is disabled. Enable it in provider details"
                GojekScreenSync.Result.ROOT_UNAVAILABLE -> "Root access unavailable. Allow root, then try again"
                GojekScreenSync.Result.BUSY -> "A GoPay refresh is already running"
                GojekScreenSync.Result.SKIPPED_COOLDOWN -> "GoPay already refreshed recently"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Artha", "GoPay refresh failed", e)
            "GoPay refresh failed. Try again"
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


    fun syncInBackgroundIfStale() {
        val anyStale = providerPreferences.importEnabled.value.any { providerPreferences.isStale(it) }
        if (anyStale) {
            syncInBackground(force = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { startedActivities++ }
            override fun onActivityStopped(activity: android.app.Activity) { startedActivities-- }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        if (BuildConfig.DEMO) {
            resetDemoIfNewDay()
            applicationScope.launch { seedDemoLedger() }
        } else {
            BackgroundSyncScheduler.schedule(this)
            applicationScope.launch { notificationAccess.ensureGranted() }
            net.wastu.artha.core.update.AppUpdateScheduler.schedule(this)
            applicationScope.launch {
                if (updater.isCheckDue(net.wastu.artha.core.update.AppUpdateScheduler.INTERVAL_MILLIS)) {
                    updater.update(install = false)
                }
            }
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

    private companion object {
        const val NOTIFICATION_SYNC_DELAY_MILLIS = 4_000L
        const val NOTIFICATION_SYNC_MIN_GAP_MILLIS = 2 * 60_000L
    }
}
