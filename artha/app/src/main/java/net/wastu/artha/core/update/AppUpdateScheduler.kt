package net.wastu.artha.core.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication

class AppUpdateJobService : JobService() {
    private var job = Job()

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? ArthaApplication ?: return false
        val jobParams = params ?: return false
        val scope = CoroutineScope(Dispatchers.IO + job)
        scope.launch {
            try {
                val updater = app.updater
                if (updater.isCheckDue(AppUpdateScheduler.INTERVAL_MILLIS)) {
                    val shouldInstall = updater.autoUpdate.value && !app.isAppInForeground
                    updater.update(install = shouldInstall)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AppUpdateJob", "Update check failed", e)
            } finally {
                jobFinished(jobParams, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        job.cancel()
        job = Job()
        return true
    }
}

object AppUpdateScheduler {
    const val JOB_ID = 20261018
    const val INTERVAL_MILLIS = 6 * 60 * 60 * 1000L // 6 hours

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.intervalMillis == INTERVAL_MILLIS) return

        val component = ComponentName(context, AppUpdateJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(INTERVAL_MILLIS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
        scheduler.cancel(JOB_ID)
        scheduler.schedule(builder.build())
    }
}
