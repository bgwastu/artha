package net.wastu.artha

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.PowerManager
import net.wastu.artha.core.log.ArthaLog
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.root.LibsuRootAccess
import net.wastu.artha.core.update.AppUpdateScheduler
import net.wastu.artha.core.update.AppUpdater
import net.wastu.artha.core.update.SessionUpdateInstaller
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.analysis.SpendingRepository
import net.wastu.artha.data.analysis.SpendingState
import net.wastu.artha.data.attention.AttentionRepository
import net.wastu.artha.data.demo.DemoLedger
import net.wastu.artha.data.ingestion.GojekProviderAdapter
import net.wastu.artha.data.ingestion.GrabProviderAdapter
import net.wastu.artha.data.ingestion.IngestionCoordinator
import net.wastu.artha.data.ingestion.JagoProviderAdapter
import net.wastu.artha.data.ingestion.JagoTransactionReader
import net.wastu.artha.data.ingestion.RootFileReader
import net.wastu.artha.data.ingestion.ShopeeProviderAdapter
import net.wastu.artha.data.ledger.LedgerDatabase
import net.wastu.artha.data.ledger.RoomLedgerRepository
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.JagoPinStatus
import net.wastu.artha.data.providers.ProviderImportWindow
import net.wastu.artha.data.providers.ProviderPreferences
import net.wastu.artha.data.sync.AppSessionWakeSync
import net.wastu.artha.data.sync.BackgroundSyncScheduler
import net.wastu.artha.data.sync.GojekFlashController
import net.wastu.artha.data.sync.GojekScreenSync
import net.wastu.artha.data.sync.JagoScreenSync
import net.wastu.artha.notifications.NotificationAccess
import net.wastu.artha.notifications.TransactionAlerts
import net.wastu.artha.ui.widget.AnalysisWidgetProvider
import net.wastu.artha.ui.widget.TransactionsWidgetProvider

class ArthaApplication : Application() {
    val rootAccess by lazy { LibsuRootAccess(applicationContext) }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Stored history is readable independently of root; imports still require root.
    private val database by lazy { LedgerDatabase.create(applicationContext) }
    val ledger by lazy { RoomLedgerRepository(database.transactions()) }
    val spending by lazy { SpendingRepository(ledger, analysisStore, applicationScope) }
    val providerPreferences by lazy { ProviderPreferences(applicationContext) }
    val attention by lazy { AttentionRepository(providerPreferences, applicationScope) }
    val analysisStore by lazy { AnalysisStore(applicationContext) }
    private val rootFiles by lazy { RootFileReader(applicationContext) }
    val ingestion by lazy {
        val files = rootFiles
        val reader = JagoTransactionReader(files)
        val isInteractive = { getSystemService(PowerManager::class.java)?.isInteractive == true }
        val jagoSync = JagoScreenSync(
            files,
            isInteractive,
            { false },
            // A PIN Bank Jago already rejected is not typed again: repeated wrong tries can lock the account.
            { providerPreferences.getJagoPin()?.takeUnless { providerPreferences.jagoPinCheck.value.status == JagoPinStatus.WRONG } },
        )
        val sessionWake = AppSessionWakeSync(files, isInteractive, { false })
        val adapters = listOf(
            JagoProviderAdapter(reader, jagoSync, onScreenSync = { result ->
                when (result) {
                    JagoScreenSync.Result.SYNCED, JagoScreenSync.Result.QUIET ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.VERIFIED, "Bank Jago accepted the PIN")
                    JagoScreenSync.Result.WRONG_PIN ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.WRONG, "Bank Jago rejected the PIN")
                    JagoScreenSync.Result.UNLOCK_FAILED ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.UNVERIFIED, "Could not reach Bank Jago's PIN screen")
                    JagoScreenSync.Result.ABORTED ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.UNVERIFIED, "The last unlock stopped before Bank Jago's PIN screen appeared")
                    JagoScreenSync.Result.JAGO_EXITED ->
                        providerPreferences.recordJagoPinCheck(JagoPinStatus.UNVERIFIED, "Bank Jago closed itself while unlocking")
                    else -> Unit
                }
            },
            verificationRequestedAt = {
                providerPreferences.diagnostics.value[Provider.JAGO]
                    ?.takeIf { it.accountStatus == AccountStatus.NEEDS_LOGIN }?.lastAttemptEpochMillis
            }),
            ShopeeProviderAdapter(files, sessionWake),
            GojekProviderAdapter(files),
            GrabProviderAdapter(files, sessionWake),
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
        AppUpdater(
            this,
            SessionUpdateInstaller(this),
        )
    }

    private var startedActivities = 0
    val isAppInForeground: Boolean get() = startedActivities > 0

    /**
     * [interactive] is for a sync a person just asked for (it may unlock Bank Jago on a hidden display);
     * the API, notifications, widgets and the periodic job leave it off and only read saved history.
     */
    fun syncInBackground(provider: Provider? = null, force: Boolean = false, interactive: Boolean = false): Job =
        applicationScope.launch {
            if (BuildConfig.DEMO) return@launch
            try {
                if (!rootAccess.verify()) return@launch
                transactionAlerts.around {
                    if (provider != null) ingestion.syncProvider(provider, interactive) else ingestion.ingestAll(force = force)
                }
                AnalysisWidgetProvider.triggerUpdate(this@ArthaApplication)
                TransactionsWidgetProvider.triggerUpdate(this@ArthaApplication)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Sync failed"
                for (p in provider?.let { listOf(it) } ?: providerPreferences.importEnabled.value) {
                    providerPreferences.recordSyncError(p, message)
                }
            }
        }

    private val pendingNotificationSyncs = ConcurrentHashMap<Provider, Job>()

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArthaLog.w("Artha", "GoPay refresh failed", e)
            "GoPay refresh failed. Try again"
        }
    }
    private suspend fun joinThenSummary(job: Job): String? {
        job.join()
        return try {
            getSharedPreferences("provider_visibility", MODE_PRIVATE)
                .getString("GOJEK_last_summary", null)
        } catch (e: Exception) {
            ArthaLog.w("Artha", "Could not read the GoPay refresh summary", e)
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
        ArthaLog.install(java.io.File(filesDir, "logs"))
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        if (BuildConfig.DEMO) {
            resetDemoIfNewDay()
            applicationScope.launch { seedDemoLedger() }
        } else {
            BackgroundSyncScheduler.schedule(this)
            applicationScope.launch { notificationAccess.ensureGranted() }
            AppUpdateScheduler.schedule(this)
            applicationScope.launch {
                if (updater.isCheckDue(AppUpdateScheduler.INTERVAL_MILLIS)) {
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
        val today = "${LocalDate.now(AccountingTime.zone)}@${packageManager.getPackageInfo(packageName, 0).lastUpdateTime}"
        if (state.getString("seeded_on", null) == today) return
        deleteDatabase("artha-ledger.db")
        deleteSharedPreferences("artha_analysis_store")
        deleteSharedPreferences("provider_visibility")
        state.edit { putString("seeded_on", today) }
    }

    /** Fills an empty demo ledger once, through the same append and label path as a file import. */
    private suspend fun seedDemoLedger() {
        if (analysisStore.snapshot.value.aiConfig.apiKey.isBlank()) {
            analysisStore.setAiConfig(AiConfig("https://ai.demo.example", "demo-key", "artha-demo-1"))
        }
        if (ledger.loadTransactions().isNotEmpty()) return
        val demo = DemoLedger.generate(Instant.now(), AccountingTime.zone)
        analysisStore.mergeImportedAnalysis(null, demo.labels)
        ledger.append(demo.transactions)
        // Every source looks connected and recently synced, as it would on a rooted phone.
        val accounts = mapOf(Provider.JAGO to "Rina P. ••0482", Provider.GOJEK to "0812••••3391", Provider.GRAB to "0812••••5507", Provider.SHOPEE to "rina_demo")
        Provider.entries.forEach { provider ->
            val times = demo.transactions.filter { it.provider == provider }.map { it.occurredAt.toEpochMilli() }
            providerPreferences.recordSyncSuccess(
                provider, "${times.size} payments, up to date", accounts[provider],
                importWindow = ProviderImportWindow(times.size, times.minOrNull(), times.maxOrNull()),
            )
        }
    }

    private companion object {
        const val NOTIFICATION_SYNC_DELAY_MILLIS = 4_000L
        const val NOTIFICATION_SYNC_MIN_GAP_MILLIS = 2 * 60_000L
    }
}
