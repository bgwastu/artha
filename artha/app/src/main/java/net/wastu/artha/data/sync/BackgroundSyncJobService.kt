package net.wastu.artha.data.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.ui.widget.AnalysisWidgetProvider
import net.wastu.artha.ui.widget.TransactionsWidgetProvider

class BackgroundSyncJobService : JobService() {
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? ArthaApplication ?: return false
        scope.launch {
            try {
                if (app.rootAccess.verify()) {
                    val result = app.ingestion.ingestAll(force = false)
                    if (result.isNotEmpty() && app.driveSyncManager.syncState.value.isEnabled) {
                        app.driveSyncManager.triggerAutoBackup()
                    }
                    AnalysisWidgetProvider.triggerUpdate(app)
                    TransactionsWidgetProvider.triggerUpdate(app)
                }
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        serviceJob.cancel()
        return true
    }
}

object BackgroundSyncScheduler {
    const val JOB_ID = 20261017
    private const val INTERVAL_MILLIS = 60 * 60 * 1000L // 1 hour

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null) return

        val component = ComponentName(context, BackgroundSyncJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(INTERVAL_MILLIS)
            .setPersisted(true)
            .setRequiresBatteryNotLow(true)
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
