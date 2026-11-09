package net.wastu.artha.notifications

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import net.wastu.artha.data.ingestion.RootFileReader

/** Artha is a root app, so it grants itself notification access instead of sending the user to Settings. */
class NotificationAccess(private val context: Context, private val root: RootFileReader) {
    suspend fun ensureGranted() {
        val packageName = context.packageName
        if (packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) {
            val listener = ComponentName(context, TransactionNotificationListener::class.java).flattenToString()
            root.executeCommandWithOutput("cmd notification allow_listener $listener")
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            root.executeCommandWithOutput("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }
}
