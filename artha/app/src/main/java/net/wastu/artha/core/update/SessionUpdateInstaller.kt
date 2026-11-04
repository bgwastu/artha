package net.wastu.artha.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Hands a verified APK to Android's [PackageInstaller] session. On Android 12+ (API 31+),
 * user action is not required if the app has the appropriate permission and is updating itself.
 * If user confirmation is required, the system returns [PackageInstaller.STATUS_PENDING_USER_ACTION]
 * to [PackageInstallReceiver], which prompts the user.
 */
class SessionUpdateInstaller(private val context: Context) : UpdateInstaller {
    override suspend fun install(apk: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (e: Exception) {
            throw IOException("Could not create install session: ${e.message}", e)
        }

        val session = packageInstaller.openSession(sessionId)
        session.use { activeSession ->
            try {
                activeSession.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                activeSession.abandon()
                throw IOException("Could not stream APK to installer: ${e.message}", e)
            }

            val statusIntent = Intent(context, PackageInstallReceiver::class.java).apply {
                action = PackageInstallReceiver.ACTION_INSTALL_STATUS
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            activeSession.commit(pendingIntent.intentSender)
        }
    }
}
