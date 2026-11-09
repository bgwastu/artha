package net.wastu.artha.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import net.wastu.artha.ArthaApplication

/**
 * Watches the provider apps' own push notifications. A payment notification means new history is
 * about to exist, so Artha imports that source right away instead of waiting for the next job.
 */
class TransactionNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return
        val provider = ProviderNotificationRules.providerFor(sbn.packageName) ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
        if (!ProviderNotificationRules.worthSyncing(provider, title, text)) return
        (applicationContext as? ArthaApplication)?.syncAfterProviderNotification(provider)
    }
}
