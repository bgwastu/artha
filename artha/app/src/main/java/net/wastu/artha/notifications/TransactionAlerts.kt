package net.wastu.artha.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.Instant
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.data.ledger.LedgerRepository

/** Tells the user about payments an import just found, so a new transaction is never a surprise on opening Artha. */
class TransactionAlerts(
    private val context: Context,
    private val ledger: LedgerRepository,
    private val isInForeground: () -> Boolean,
) {
    /** Runs [import], then alerts for any payments it added. */
    suspend fun <T> around(import: suspend () -> T): T {
        val known = ledger.loadTransactions().mapTo(HashSet()) { it.key }
        return import().also { runCatching { announce(known) } }
    }

    private suspend fun announce(known: Set<String>) {
        if (isInForeground()) return
        val payments = ProviderNotificationRules.freshPayments(known, ledger.loadTransactions(), Instant.now())
        val alert = ProviderNotificationRules.alertFor(payments) ?: return
        post(alert, if (payments.size == 1) payments.first().key.hashCode() else SUMMARY_ID)
    }

    private fun post(alert: ProviderNotificationRules.Alert, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New transactions", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(alert.title)
                .setContentText(alert.text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "new-transactions"
        const val SUMMARY_ID = 7001
    }
}
