package net.wastu.artha.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.wastu.artha.ArthaApplication
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.iconRes
import net.wastu.artha.ui.TransactionTag
import net.wastu.artha.ui.TransactionTagKind
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.transactionHumanLine
import net.wastu.artha.ui.transactionTags

class TransactionsWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        WidgetRefresh.bind(context)
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, TransactionsWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateWidgets(context, appWidgetManager, ids)
                (context.applicationContext as? ArthaApplication)?.syncInBackgroundIfStale()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRefresh.bind(context)
        val targetIds = if (appWidgetIds.isNotEmpty()) appWidgetIds else {
            appWidgetManager.getAppWidgetIds(ComponentName(context, TransactionsWidgetProvider::class.java))
        }
        if (targetIds.isNotEmpty()) {
            updateWidgets(context, appWidgetManager, targetIds)
            (context.applicationContext as? ArthaApplication)?.syncInBackgroundIfStale()
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
        val openApp = PendingIntent.getActivity(
            context, 100, openAppIntent(context, "TRANSACTIONS"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val itemTemplate = PendingIntent.getActivity(
            context,
            101,
            openAppIntent(context),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun status(failed: Boolean = false) {
            for (id in appWidgetIds) {
                val previous = WidgetUpdatedAt.read(context, id)
                val views = RemoteViews(context.packageName, R.layout.widget_transactions)
                views.setTextViewText(R.id.widget_transactions_status, widgetFreshness(previous, System.currentTimeMillis(), updating = !failed, failed = failed))
                views.setOnClickPendingIntent(R.id.widget_transactions_header, openApp)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        }
        status()
        WidgetRefresh.schedule("transactions", pendingResult, onFailure = { status(failed = true) }) {
            val app = context.applicationContext as ArthaApplication
            val enabled = app.providerPreferences.enabled.value
            val allPayments = widgetSpending(context).transactions.filter { it.isConfirmed && it.provider in enabled && it.direction == Direction.DEBIT }
            val rows = groupedWidgetPayments(allPayments, enabled)
            val updatedAt = System.currentTimeMillis()

            for (id in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_transactions).apply {
                    setTextViewText(R.id.widget_transactions_status, widgetFreshness(updatedAt, updatedAt))
                    setViewVisibility(R.id.widget_transactions_list, View.VISIBLE)
                    setTextViewText(R.id.widget_transactions_empty, "No saved payments")
                    setOnClickPendingIntent(R.id.widget_transactions_header, openApp)
                    setPendingIntentTemplate(R.id.widget_transactions_list, itemTemplate)
                    setEmptyView(R.id.widget_transactions_list, R.id.widget_transactions_empty)
                }
                setPaymentList(context, views, id, rows)
                appWidgetManager.updateAppWidget(id, views)
                @Suppress("DEPRECATION")
                appWidgetManager.notifyAppWidgetViewDataChanged(id, R.id.widget_transactions_list)
                WidgetUpdatedAt.save(context, id, updatedAt)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "net.wastu.artha.UPDATE_TRANSACTIONS_WIDGET"

        fun triggerUpdate(context: Context) {
            WidgetRefresh.bind(context)
            val intent = Intent(context, TransactionsWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }
}

private fun openAppIntent(context: Context, destination: String? = null) = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    if (destination != null) putExtra("extra_destination", destination)
}

/** Android 12+ ships the rows inline; older versions go through [TransactionsWidgetService]. */
private fun setPaymentList(context: Context, views: RemoteViews, widgetId: Int, rows: List<WidgetPaymentRow>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val builder = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2).setHasStableIds(true)
        for (row in rows) builder.addItem(widgetRowId(row), buildWidgetRowView(context, row))
        views.setRemoteAdapter(R.id.widget_transactions_list, builder.build())
    } else {
        val serviceIntent = Intent(context, TransactionsWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = toUri(Intent.URI_INTENT_SCHEME).toUri()
        }
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(R.id.widget_transactions_list, serviceIntent)
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
    val title = transactionHumanLine(tx)
    views.setTextViewText(R.id.widget_item_description, title)
    val tags = transactionTags(tx)
    bindWidgetTags(views, tags)

    val zone = AccountingTime.zone
    val localDate = tx.occurredAt.atZone(zone).toLocalDate()
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    val timeStr = tx.occurredAt.atZone(zone).format(timeFormatter)
    val dateStr = "${widgetDayLabel(localDate)}, $timeStr"
    val isCredit = tx.direction == Direction.CREDIT
    // The provider icon already names the source; time and pocket sit in separate views.
    views.setTextViewText(R.id.widget_item_subtitle, timeStr)
    val pocketName = tx.pocket?.name
    views.setTextViewText(R.id.widget_item_pocket, pocketName.orEmpty())
    views.setViewVisibility(R.id.widget_item_pocket, if (pocketName.isNullOrBlank()) View.GONE else View.VISIBLE)

    val isZero = !isCredit && tx.isReversedRecord
    val amountText = paymentRowAmount(tx)
    val amountColorRes = when {
        isZero -> R.color.widget_amount_supporting
        isCredit -> R.color.widget_amount_incoming
        else -> R.color.widget_amount_outgoing
    }
    val amountColor = ContextCompat.getColor(context, amountColorRes)
    views.setTextViewText(R.id.widget_item_amount, amountText)
    views.setTextColor(R.id.widget_item_amount, amountColor)

    val fillInIntent = Intent().apply {
        putExtra("extra_destination", "TRANSACTIONS")
    }
    val tagLabels = tags.map { it.description }
    views.setContentDescription(R.id.widget_item_root, "${tx.provider.displayName}, ${(tagLabels + title).joinToString(", ")}, ${if (isCredit) "Incoming" else "Outgoing"}, $amountText, $dateStr. Open Expenses")
    views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)

    return views
}

/** RemoteViews cannot draw inline pills, so a fixed set of tag views is shown or hidden. */
private fun bindWidgetTags(views: RemoteViews, tags: List<TransactionTag>) {
    val status = tags.firstOrNull { it.kind != TransactionTagKind.CARD }
    val returned = status?.kind in setOf(TransactionTagKind.REVERSED, TransactionTagKind.REFUND, TransactionTagKind.PARTIAL_REFUND)
    fun bind(id: Int, text: String?) {
        views.setViewVisibility(id, if (text == null) View.GONE else View.VISIBLE)
        if (text != null) views.setTextViewText(id, text)
    }
    // The Grouped chip carries its own text, separate from the status chips.
    views.setViewVisibility(R.id.widget_item_tag_grouped, if (status?.kind == TransactionTagKind.GROUPED) View.VISIBLE else View.GONE)
    bind(R.id.widget_item_tag_status_secondary, status?.label?.takeIf { !returned && status?.kind != TransactionTagKind.GROUPED })
    bind(R.id.widget_item_tag_status_tertiary, status?.label?.takeIf { returned })
    bind(R.id.widget_item_tag_card, tags.firstOrNull { it.kind == TransactionTagKind.CARD }?.label)
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
                val app = context.applicationContext as ArthaApplication
                val enabled = app.providerPreferences.enabled.value
                rows = groupedWidgetPayments(widgetSpending(context).transactions, enabled)
            } catch (e: Exception) { Log.w("ArthaWidget", "Keeping the last widget rows", e) }
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
