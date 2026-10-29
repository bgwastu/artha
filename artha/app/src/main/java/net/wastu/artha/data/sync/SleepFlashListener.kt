package net.wastu.artha.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.core.model.Provider

/**
 * Screen-off waits two seconds without a wakelock; a flick wakes the phone and the generation
 * counter drops the pending work. An in-flight launch is aborted and force-stopped.
 */
class SleepFlashListener(private val app: ArthaApplication) {
    private val generation = AtomicLong(0)

    fun register() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        generation.incrementAndGet()
                        app.gojekFlash.abortIfRunning()
                    }
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
        }
    }

    private fun onScreenOff() {
        val prefs = app.providerPreferences
        val last = prefs.gojekFlash.value
        if (GojekFlashPolicy.cooldownActive(last.result, last.millis, System.currentTimeMillis())) return
        if (Provider.GOJEK !in prefs.importEnabled.value) return
        val mine = generation.incrementAndGet()
        app.applicationScope.launch {
            delay(GojekFlashPolicy.SLEEP_CONFIRM_MILLIS)
            if (mine != generation.get()) return@launch
            if (!app.rootAccess.verify()) return@launch
            val flash = app.gojekFlash.run(Provider.GOJEK in prefs.importEnabled.value)
            if (flash == GojekFlashSync.Result.FLASHED) app.syncInBackground(Provider.GOJEK, force = true)
        }
    }
}
