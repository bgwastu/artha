package net.wastu.artha.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.wastu.artha.ArthaApplication
import net.wastu.artha.data.analysis.SpendingState

internal object WidgetRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    @Synchronized
    fun schedule(key: String, pending: BroadcastReceiver.PendingResult?, onFailure: () -> Unit = {}, update: suspend () -> Unit) {
        jobs.remove(key)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { delay(150); withTimeout(8_000) { update() } }
            catch (_: TimeoutCancellationException) { onFailure() }
            catch (cancelled: CancellationException) { /* Keep last valid widget contents. */ }
            catch (_: Exception) { onFailure() }
        }
        jobs[key] = job
        // Also completes a superseded broadcast whose coroutine never started.
        job.invokeOnCompletion {
            synchronized(this@WidgetRefresh) { if (jobs[key] === job) jobs.remove(key) }
            pending?.finish()
        }
        job.start()
    }
}

internal suspend fun widgetSpending(context: Context): SpendingState.Ready = withTimeout(8_000) {
    val app = context.applicationContext as ArthaApplication
    val state = app.spending.state.first { it != SpendingState.Loading }
    state as? SpendingState.Ready ?: error("Stored spending projection unavailable")
}

internal object WidgetUpdatedAt {
    private const val STORE = "widget_freshness"
    fun read(context: Context, id: Int): Long? = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getLong(id.toString(), 0).takeIf { it > 0 }
    fun save(context: Context, id: Int, time: Long) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putLong(id.toString(), time).apply()
    }
    fun remove(context: Context, ids: IntArray) {
        val edit = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
        ids.forEach { edit.remove(it.toString()) }
        edit.apply()
    }
}
