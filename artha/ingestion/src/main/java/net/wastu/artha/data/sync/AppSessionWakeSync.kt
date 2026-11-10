package net.wastu.artha.data.sync

import android.os.Build
import net.wastu.artha.core.log.ArthaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.data.ingestion.RootFileReader

/**
 * Headless "Wake & Harvest" session refresher for apps that update their session tokens on launch
 * without requiring interactive PIN entry (Shopee, Grab).
 */
class AppSessionWakeSync(
    private val reader: RootFileReader,
    private val isInteractive: () -> Boolean,
    private val shouldAbort: () -> Boolean,
) {
    suspend fun wakeShopee(): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 33 || shouldAbort() || !isInteractive()) return@withContext false
        val installed = sh("pm path com.shopee.id 2>/dev/null")
        if (installed == null || installed.none { it.startsWith("package:") }) return@withContext false
        sh("settings put global force_resizable_activities 1")
        wake(
            pkg = "com.shopee.id",
            component = "com.shopee.id/com.shopee.app.ui.home.HomeActivity_",
            targetPath = "/data/data/com.shopee.id/app_webview_com.shopee.id/Default/Cookies",
            maxWaitSeconds = 8,
        )
    }

    suspend fun wakeGrab(): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 33 || shouldAbort() || !isInteractive()) return@withContext false
        val installed = sh("pm path com.grabtaxi.passenger 2>/dev/null")
        if (installed == null || installed.none { it.startsWith("package:") }) return@withContext false
        wake(
            pkg = "com.grabtaxi.passenger",
            component = "com.grabtaxi.passenger/com.grab.pax.newface.common.alias.DefaultLauncherAlias",
            targetPath = "/data/data/com.grabtaxi.passenger/shared_prefs/com.grabtaxi.passenger_preferences.xml",
            maxWaitSeconds = 6,
        )
    }

    private suspend fun wake(
        pkg: String,
        component: String,
        targetPath: String,
        maxWaitSeconds: Int,
    ): Boolean = RootScreenSession.exclusive {
        wakeOnVirtualDisplay(pkg, component, targetPath, maxWaitSeconds)
    }

    private suspend fun wakeOnVirtualDisplay(
        pkg: String,
        component: String,
        targetPath: String,
        maxWaitSeconds: Int,
    ): Boolean {
        if (isResumedOnDisplay0(pkg)) return false
        val mtimeBefore = fileMtime(targetPath)

        sh("am force-stop $pkg")
        val session = RootScreenSession.start(reader.context.applicationInfo.sourceDir) ?: return false

        try {
            if (!session.open()) return false
            if (!session.start(component)) return false

            val iterations = (maxWaitSeconds * 1000) / 400
            for (i in 0 until iterations) {
                if (shouldAbort() || !isInteractive() || isResumedOnDisplay0(pkg)) break
                delay(400)
                val mtimeAfter = fileMtime(targetPath)
                if (mtimeAfter != null && mtimeAfter != mtimeBefore) {
                    ArthaLog.i(TAG, "Session refreshed for $pkg (mtime $mtimeBefore -> $mtimeAfter)")
                    return true
                }
            }
            return false
        } finally {
            withContext(NonCancellable) {
                session.close()
                if (!isResumedOnDisplay0(pkg)) sh("am force-stop $pkg")
            }
        }
    }

    private suspend fun isResumedOnDisplay0(pkg: String): Boolean {
        val escapedPkg = pkg.replace(".", "\\.")
        return sh("dumpsys activity activities | awk '/^Display #/ { d = \$2 } /topResumedActivity=/ && /$escapedPkg/ && d == \"#0\" { n++ } END { print n + 0 }'")
            ?.firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } ?: false
    }

    private suspend fun fileMtime(path: String): Long? =
        sh("stat -c %Y $path 2>/dev/null")?.firstOrNull()?.trim()?.toLongOrNull()

    private suspend fun sh(command: String): List<String>? = reader.executeCommandWithOutput(command)

    companion object {
        private const val TAG = "ArthaSessionWake"
    }
}
