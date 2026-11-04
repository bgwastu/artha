package net.wastu.artha.core.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import net.wastu.artha.ArthaApplication
import net.wastu.artha.R

class PackageInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_STATUS = "net.wastu.artha.action.INSTALL_STATUS"
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 8801
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ArthaApplication ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (app.isAppInForeground) {
                        try {
                            context.startActivity(confirmIntent)
                        } catch (e: Exception) {
                            showConfirmationNotification(context, confirmIntent)
                        }
                    } else {
                        showConfirmationNotification(context, confirmIntent)
                    }
                }
                app.updater.onInstallResult(UpdateState.WaitingForConfirmation(app.updater.installedVersionName))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                app.updater.onInstallResult(UpdateState.UpToDate(System.currentTimeMillis()))
            }
            else -> {
                val errorMsg = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation aborted"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by policy"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Package conflict"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Package incompatible"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Package invalid"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage space"
                    else -> message?.takeIf { it.isNotBlank() } ?: "Installation failed (code $status)"
                }
                app.updater.onInstallResult(UpdateState.Failed(errorMsg))
            }
        }
    }

    private fun showConfirmationNotification(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Artha update ready")
            .setContentText("Tap to complete installation")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
