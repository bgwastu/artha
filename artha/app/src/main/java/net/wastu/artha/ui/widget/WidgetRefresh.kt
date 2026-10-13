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
    fun schedule(key: String, pending: BroadcastReceiver.PendingResult?, update: suspend () -> Unit) {
        jobs.remove(key)?.cancel()
        val job = scope.launch {
            try { delay(150); withTimeout(8_000) { update() } }
            catch (cancelled: CancellationException) { /* Keep last valid widget contents. */ }
            catch (_: Exception) { /* Stored widget contents remain useful when projection is unavailable. */ }
            finally {
                synchronized(this@WidgetRefresh) { if (jobs[key] === coroutineContext[Job]) jobs.remove(key) }
                pending?.finish()
            }
        }
        jobs[key] = job
    }
}

internal suspend fun widgetSpending(context: Context): SpendingState.Ready = withTimeout(8_000) {
    val app = context.applicationContext as ArthaApplication
    val state = app.spending.state.first { it != SpendingState.Loading }
    state as? SpendingState.Ready ?: error("Stored spending projection unavailable")
}
