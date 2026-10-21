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
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.transactionHumanLine
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
                (context.applicationContext as? net.wastu.artha.ArthaApplication)?.syncInBackgroundIfStale()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val targetIds = if (appWidgetIds.isNotEmpty()) appWidgetIds else {
            appWidgetManager.getAppWidgetIds(ComponentName(context, TransactionsWidgetProvider::class.java))
        }
        if (targetIds.isNotEmpty()) {
            updateWidgets(context, appWidgetManager, targetIds)
            (context.applicationContext as? net.wastu.artha.ArthaApplication)?.syncInBackgroundIfStale()
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
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        }
        status()
        WidgetRefresh.schedule("transactions", pendingResult, onFailure = { status(failed = true) }) {
                val app = context.applicationContext as net.wastu.artha.ArthaApplication
                val enabled = app.providerPreferences.enabled.value
                val allPayments = widgetSpending(context).transactions.filter { it.isConfirmed && it.provider in enabled }
                val rows = groupedWidgetPayments(allPayments, enabled)
                val updatedAt = System.currentTimeMillis()

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_transactions)
                    views.setTextViewText(R.id.widget_transactions_status, widgetFreshness(updatedAt, updatedAt))
                    views.setViewVisibility(R.id.widget_transactions_list, View.VISIBLE)
                    views.setTextViewText(R.id.widget_transactions_empty, "No saved payments")
                    views.setOnClickPendingIntent(R.id.widget_transactions_header, openAppIntent)
                    views.setPendingIntentTemplate(R.id.widget_transactions_list, itemTemplatePendingIntent)
                    views.setEmptyView(R.id.widget_transactions_list, R.id.widget_transactions_empty)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val builder = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2).setHasStableIds(true)
                        for (row in rows) {
                            builder.addItem(widgetRowId(row), buildWidgetRowView(context, row))
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
                    }
                    @Suppress("DEPRECATION")
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_transactions_list)
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

internal fun buildWidgetRowView(context: Context, row: WidgetPaymentRow): RemoteViews = when (row) {
    is WidgetPaymentRow.Day -> RemoteViews(context.packageName, R.layout.widget_transaction_day).apply {
        setTextViewText(R.id.widget_day_heading, widgetDayLabel(row.date))
    }
    is WidgetPaymentRow.Payment -> buildTransactionItemView(context, row.transaction)
}

internal fun buildTransactionItemView(
    context: Context,
    tx: UnifiedTransaction,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_transaction_item)

    views.setImageViewResource(R.id.widget_item_icon, tx.provider.iconRes)
    val line = transactionHumanLine(tx)
    val title = if (tx.isQris && !line.contains("QRIS", ignoreCase = true)) {
        "[QRIS] $line"
    } else {
        line
    }
    views.setTextViewText(R.id.widget_item_description, title)

    val zone = net.wastu.artha.core.model.AccountingTime.zone
    val localDate = tx.occurredAt.atZone(zone).toLocalDate()
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    val timeStr = tx.occurredAt.atZone(zone).format(timeFormatter)
    val dateStr = "${widgetDayLabel(localDate)}, $timeStr"
    val isCredit = tx.direction == Direction.CREDIT
    val pocketName = tx.pocket?.name
    val subtitle = listOfNotNull(tx.provider.displayName, pocketName, timeStr).joinToString(" • ")
    views.setTextViewText(R.id.widget_item_subtitle, subtitle)

    val isZero = !isCredit && (tx.isReversal || tx.description.contains("(Reversed)", ignoreCase = true))
    val amountText = paymentRowAmount(tx)
    val amountColorRes = when {
        isZero -> R.color.widget_amount_supporting
        isCredit -> R.color.widget_amount_incoming
        else -> R.color.widget_amount_outgoing
    }
    val amountColor = androidx.core.content.ContextCompat.getColor(context, amountColorRes)
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

    private var rows: List<WidgetPaymentRow> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking(Dispatchers.IO) {
            try {
                val app = context.applicationContext as net.wastu.artha.ArthaApplication
                val enabled = app.providerPreferences.enabled.value
                rows = groupedWidgetPayments(widgetSpending(context).transactions, enabled)
            } catch (_: Exception) { /* Keep the last valid collection. */ }
        }
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        val row = rows.getOrNull(position) ?: return null
        return buildWidgetRowView(context, row)
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.let(::widgetRowId) ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
