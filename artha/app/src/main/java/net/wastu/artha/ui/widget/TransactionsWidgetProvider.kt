package net.wastu.artha.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.os.Bundle
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.paymentRowAmount
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class TransactionsWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, TransactionsWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateWidgets(context, appWidgetManager, ids)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val targetIds = if (appWidgetIds.isNotEmpty()) appWidgetIds else {
            appWidgetManager.getAppWidgetIds(ComponentName(context, TransactionsWidgetProvider::class.java))
        }
        if (targetIds.isNotEmpty()) {
            updateWidgets(context, appWidgetManager, targetIds)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateWidgets(context, manager, intArrayOf(id))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetUpdatedAt.remove(context, appWidgetIds)
    }

    private fun updateWidgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()

        val openAppIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("extra_destination", "TRANSACTIONS")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val itemTemplateFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val itemTemplatePendingIntent = PendingIntent.getActivity(
            context,
            101,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            itemTemplateFlags,
        )

        fun status(failed: Boolean = false) {
            for (id in appWidgetIds) {
                val previous = WidgetUpdatedAt.read(context, id)
                val views = RemoteViews(context.packageName, R.layout.widget_transactions)
                views.setTextViewText(R.id.widget_transactions_status, widgetFreshness(previous, System.currentTimeMillis(), updating = !failed, failed = failed))
                views.setOnClickPendingIntent(R.id.widget_transactions_header, openAppIntent)
                if (previous == null) appWidgetManager.updateAppWidget(id, views)
                else appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        }
        status()
        WidgetRefresh.schedule("transactions", pendingResult, onFailure = { status(failed = true) }) {
                val app = context.applicationContext as net.wastu.artha.ArthaApplication
                val enabled = app.providerPreferences.enabled.value
                val allPayments = widgetSpending(context).transactions.filter { it.isConfirmed && it.provider in enabled }
                val reconciled = recentWidgetPayments(allPayments, enabled)
                val updatedAt = System.currentTimeMillis()

                val dateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_transactions)
                    views.setTextViewText(R.id.widget_transactions_count, widgetPaymentScope(allPayments.size))
                    views.setTextViewText(R.id.widget_transactions_status, widgetFreshness(updatedAt, updatedAt))
                    views.setViewVisibility(R.id.widget_transactions_list, View.VISIBLE)
                    views.setTextViewText(R.id.widget_transactions_empty, "No saved payments")
                    views.setOnClickPendingIntent(R.id.widget_transactions_header, openAppIntent)
                    views.setPendingIntentTemplate(R.id.widget_transactions_list, itemTemplatePendingIntent)
                    views.setEmptyView(R.id.widget_transactions_list, R.id.widget_transactions_empty)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val builder = RemoteViews.RemoteCollectionItems.Builder()
                        for (tx in reconciled) {
                            builder.addItem(java.util.UUID.nameUUIDFromBytes(tx.key.toByteArray()).mostSignificantBits, buildTransactionItemView(context, tx, dateFormatter))
                        }
                        views.setRemoteAdapter(R.id.widget_transactions_list, builder.build())
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    } else {
                        val serviceIntent = Intent(context, TransactionsWidgetService::class.java).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                        }
                        @Suppress("DEPRECATION")
                        views.setRemoteAdapter(R.id.widget_transactions_list, serviceIntent)
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                        @Suppress("DEPRECATION")
                        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_transactions_list)
                    }
                    WidgetUpdatedAt.save(context, appWidgetId, updatedAt)
                }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "net.wastu.artha.UPDATE_TRANSACTIONS_WIDGET"

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, TransactionsWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }
}

internal fun buildTransactionItemView(
    context: Context,
    tx: UnifiedTransaction,
    dateFormatter: DateTimeFormatter,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_transaction_item)

    views.setImageViewResource(R.id.widget_item_icon, tx.provider.iconRes)
    val title = if (tx.isQris && !tx.description.contains("QRIS", ignoreCase = true)) {
        "[QRIS] ${tx.description}"
    } else {
        tx.description
    }
    views.setTextViewText(R.id.widget_item_description, title)

    val zone = net.wastu.artha.core.model.AccountingTime.zone
    val localDate = tx.occurredAt.atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    val timeStr = tx.occurredAt.atZone(zone).format(timeFormatter)
    val dayPrefix = when (localDate) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> tx.occurredAt.atZone(zone).format(dateFormatter)
    }
    val dateStr = "$dayPrefix, $timeStr"
    val isCredit = tx.direction == Direction.CREDIT
    views.setTextViewText(R.id.widget_item_subtitle, dateStr)

    val isZero = !isCredit && (tx.isReversal || tx.description.contains("(Reversed)", ignoreCase = true))
    val amountText = paymentRowAmount(tx)
    val amountColor = when {
        isZero -> WidgetPalette.supporting
        isCredit -> WidgetPalette.income
        else -> WidgetPalette.expense
    }
    views.setTextViewText(R.id.widget_item_amount, amountText)
    views.setTextColor(R.id.widget_item_amount, amountColor)

    val fillInIntent = Intent().apply {
        putExtra("extra_destination", "TRANSACTIONS")
    }
    views.setContentDescription(R.id.widget_item_root, "${tx.provider.displayName}, $title, ${if (isCredit) "Incoming" else "Outgoing"}, $amountText, $dateStr. Open Transactions")
    views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)

    return views
}

class TransactionsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TransactionsRemoteViewsFactory(applicationContext)
    }
}

private class TransactionsRemoteViewsFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {

    private var transactions: List<UnifiedTransaction> = emptyList()
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking(Dispatchers.IO) {
            try {
                val app = context.applicationContext as net.wastu.artha.ArthaApplication
                val enabled = app.providerPreferences.enabled.value
                transactions = recentWidgetPayments(widgetSpending(context).transactions, enabled)
            } catch (_: Exception) { /* Keep the last valid collection. */ }
        }
    }

    override fun onDestroy() {
        transactions = emptyList()
    }

    override fun getCount(): Int = transactions.size

    override fun getViewAt(position: Int): RemoteViews? {
        val tx = transactions.getOrNull(position) ?: return null
        return buildTransactionItemView(context, tx, dateFormatter)
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = transactions.getOrNull(position)?.let { java.util.UUID.nameUUIDFromBytes(it.key.toByteArray()).mostSignificantBits } ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
