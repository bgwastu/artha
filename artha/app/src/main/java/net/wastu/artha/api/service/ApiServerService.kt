package net.wastu.artha.api.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.wastu.artha.ArthaApplication
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.api.tunnel.CloudflareQuickTunnel
import net.wastu.artha.api.tunnel.TunnelState

class ApiServerService : Service() {

    companion object {
        const val ACTION_START = "net.wastu.artha.api.START"
        const val ACTION_STOP = "net.wastu.artha.api.STOP"
        const val ACTION_DISCONNECT = "net.wastu.artha.api.DISCONNECT"
        const val ACTION_COPY = "net.wastu.artha.api.COPY"
        private const val CHANNEL_ID = "artha_api_service"
        private const val NOTIFICATION_ID = 9080

        /** Wake locks are capped in duration by the framework, so renew them while the link stays up. */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60 * 1000
        private const val WAKE_LOCK_REFRESH_MS = 30L * 60 * 1000
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var engine: ApiServerEngine? = null
    private var tunnel: CloudflareQuickTunnel? = null
    private var currentUrl: String? = null
    private var lockRefreshJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "artha:api_server_wake_lock").apply {
            setReferenceCounted(false)
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(wifiMode, "artha:api_server_wifi_lock").apply {
            setReferenceCounted(false)
        }

        val app = application as ArthaApplication
        engine = ApiServerEngine(
            spendingRepository = app.spending,
            analysisStore = app.analysisStore,
            port = 8080,
            onTriggerSync = { provider -> app.syncInBackground(provider) },
        )

        tunnel = CloudflareQuickTunnel(applicationContext, serviceScope)

        serviceScope.launch {
            tunnel?.state?.collect { state ->
                currentUrl = (state as? TunnelState.Active)?.url
                syncLocks(state is TunnelState.Active)
                ApiServerController.updateTunnelState(state, currentUrl)
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_DISCONNECT -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COPY -> {
                currentUrl?.let { url ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Artha URL", url))
                    Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                }
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Creating link..."))
        ApiServerController.updateServiceRunning(true)

        try {
            engine?.start()
            tunnel?.start(localPort = 8080)
        } catch (e: Exception) {
            android.util.Log.e("ArthaApi", "Failed to start server", e)
            ApiServerController.updateTunnelState(TunnelState.Failed(e.message ?: "Server start failed"))
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tunnel?.stop()
        engine?.stop()
        lockRefreshJob?.cancel()
        serviceScope.cancel()

        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()

        ApiServerController.updateServiceRunning(false)
        super.onDestroy()
    }

    /** Holds the wake and Wi-Fi locks only while the tunnel can actually serve requests. */
    private fun syncLocks(active: Boolean) {
        if (!active) {
            lockRefreshJob?.cancel()
            lockRefreshJob = null
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            return
        }

        acquireWakeLock()
        wifiLock?.takeIf { !it.isHeld }?.acquire()

        if (lockRefreshJob?.isActive != true) {
            lockRefreshJob = serviceScope.launch {
                while (isActive) {
                    delay(WAKE_LOCK_REFRESH_MS)
                    acquireWakeLock()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        wakeLock?.takeIf { !it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote access",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while the Artha link is online"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification(state: TunnelState) {
        val statusText = when (state) {
            is TunnelState.Inactive -> "Server idle"
            is TunnelState.Starting -> "Creating link..."
            is TunnelState.Active -> state.url.removePrefix("https://")
            is TunnelState.Failed -> "Could not share: ${state.error}"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText, state is TunnelState.Active))
    }

    private fun buildNotification(statusText: String, isActive: Boolean = false): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ApiServerService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (isActive) "Remote access is on" else "Turning on remote access"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", disconnectIntent)

        if (isActive && currentUrl != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(statusText))

            val copyIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, ApiServerService::class.java).apply { action = ACTION_COPY },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Copy URL", copyIntent)

            val shareIntent = PendingIntent.getActivity(
                this,
                3,
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                    },
                    null,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Share", shareIntent)
        }

        return builder.build()
    }
}
