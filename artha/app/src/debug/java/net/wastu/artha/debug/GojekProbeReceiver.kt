package net.wastu.artha.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import net.wastu.artha.data.ingestion.GojekOrderReader
import net.wastu.artha.data.ingestion.RootFileReader

/** Debug-only: adb shell am broadcast -a net.wastu.artha.DEBUG_GOJEK_PROBE */
class GojekProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "probe started")
        val pending = goAsync()
        Thread {
            try {
                val report = runBlocking {
                    GojekOrderReader(RootFileReader(context.applicationContext)).diagnoseHistoryAccess()
                }
                Log.i(TAG, report)
            } catch (error: Exception) {
                Log.i(TAG, "failure=${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "GojekProbe"
    }
}
