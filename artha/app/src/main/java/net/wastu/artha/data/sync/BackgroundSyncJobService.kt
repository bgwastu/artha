package net.wastu.artha.data.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.ui.widget.AnalysisWidgetProvider
import net.wastu.artha.ui.widget.TransactionsWidgetProvider

class BackgroundSyncJobService : JobService() {
    private var serviceJob = Job()

    private companion object {
        const val TAG = "BackgroundSync"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? ArthaApplication ?: return false
        val jobParams = params ?: return false
        val scope = CoroutineScope(Dispatchers.IO + serviceJob)
        scope.launch {
            var allProvidersHealthy = true
            try {
                if (app.rootAccess.verify()) {
                    val gojekImportEnabled = app.providerPreferences.importEnabled.value
                        .contains(net.wastu.artha.core.model.Provider.GOJEK)
                    app.gojekFlash.run(gojekImportEnabled)
                    val summaries = app.ingestion.ingestAll(force = false)
                    allProvidersHealthy = summaries.values.none { it.startsWith("Unavailable") }
                    AnalysisWidgetProvider.triggerUpdate(app)
                    TransactionsWidgetProvider.triggerUpdate(app)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                // Surface the failure instead of retrying silently, and reschedule with JobScheduler
                // backoff. Only the exception class and message are logged, never provider content.
                allProvidersHealthy = false
                val message = t.message?.takeIf { it.isNotBlank() } ?: "Background sync failed"
                for (provider in app.providerPreferences.importEnabled.value) {
                    app.providerPreferences.recordSyncError(provider, message)
                }
                android.util.Log.w(TAG, "background sync failed: ${t.javaClass.simpleName}: $message")
            } finally {
                jobFinished(jobParams, !allProvidersHealthy)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        (applicationContext as? ArthaApplication)?.gojekFlash?.abortIfRunning()
        serviceJob.cancel()
        serviceJob = Job()
        return true
    }
}

object BackgroundSyncScheduler {
    const val JOB_ID = 20261017

    private const val INTERVAL_MILLIS = 15 * 60 * 1000L
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.intervalMillis == INTERVAL_MILLIS) return

        val component = ComponentName(context, BackgroundSyncJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(INTERVAL_MILLIS)
            .setPersisted(true)
            .setBackoffCriteria(10 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setRequiresBatteryNotLow(true)
        scheduler.cancel(JOB_ID)
        scheduler.schedule(builder.build())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BackgroundSyncScheduler.schedule(context)
        }
    }
}
