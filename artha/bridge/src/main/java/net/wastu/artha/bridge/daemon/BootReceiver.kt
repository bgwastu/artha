package net.wastu.artha.bridge.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the daemon after a reboot, and again after an update replaced the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            (context.applicationContext as BridgeApplication).startDaemon()
        }
    }
}
