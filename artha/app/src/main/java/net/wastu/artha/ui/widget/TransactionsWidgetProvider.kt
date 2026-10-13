package net.wastu.artha.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.analysis.CategoryClassifier
import net.wastu.artha.data.ledger.LedgerDatabase
import net.wastu.artha.data.ledger.RoomLedgerRepository
import net.wastu.artha.data.providers.ProviderPreferences
import net.wastu.artha.data.reconciliation.LedgerReconciler
import net.wastu.artha.ui.formatMoney
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = runCatching { LedgerDatabase.create(context) }.getOrNull()
                val ledger = database?.let { RoomLedgerRepository(it.transactions()) }
                val rawTxs = ledger?.observeTransactions()?.firstOrNull().orEmpty()
                val snapshot = runCatching { AnalysisStore(context).snapshot.value }.getOrNull()
                val enabled = runCatching { ProviderPreferences(context).enabled.value }.getOrNull()

                val enriched = rawTxs.map { tx ->
                    val id = tx.providerTransactionId.substringAfter(":")
                    val note = snapshot?.userNotes?.get(id) ?: tx.userNote
                    val isCredit = tx.direction == Direction.CREDIT
                    val cat = if (isCredit) null else {
                        snapshot?.categories?.get(id) ?: CategoryClassifier.classify(tx, note).category
                    }
                    tx.copy(
                        userNote = note,
                        category = cat,
                        isReversal = tx.rawPayload.contains("\"isReversal\":true") || tx.description.contains("(Reversed)"),
                    )
                }

                val reconciled = LedgerReconciler.reconcileLedger(enriched)
                    .filter { enabled == null || it.provider in enabled }
                    .take(40)

                val dateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_transactions)
                    views.setTextViewText(R.id.widget_transactions_count, "${reconciled.size} items")
                    views.setOnClickPendingIntent(R.id.widget_transactions_header, openAppIntent)
                    views.setPendingIntentTemplate(R.id.widget_transactions_list, itemTemplatePendingIntent)
                    views.setEmptyView(R.id.widget_transactions_list, R.id.widget_transactions_empty)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val builder = RemoteViews.RemoteCollectionItems.Builder()
                        for ((idx, tx) in reconciled.withIndex()) {
                            builder.addItem(idx.toLong(), buildTransactionItemView(context, tx, dateFormatter))
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
                }
            } finally {
                pendingResult?.finish()
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
    views.setTextViewText(R.id.widget_item_description, tx.description)

    val dateStr = tx.occurredAt.atZone(ZoneId.systemDefault()).format(dateFormatter)
    val isCredit = tx.direction == Direction.CREDIT
    val subtitleParts = mutableListOf(dateStr)
    if (!isCredit && !tx.category.isNullOrBlank()) {
        subtitleParts += tx.category
    }
    if (!tx.userNote.isNullOrBlank()) {
        subtitleParts += "#${tx.userNote}"
    }
    views.setTextViewText(R.id.widget_item_subtitle, subtitleParts.joinToString(" - "))

    val isZero = tx.isReversal || tx.money.amountMinor == 0L
    val amountText = if (isZero) {
        "Rp0"
    } else {
        "${if (isCredit) "+" else "−"}${formatMoney(tx.money)}"
    }
    val amountColor = when {
        isZero -> Color.parseColor("#8E9099")
        isCredit -> Color.parseColor("#81C784")
        else -> Color.parseColor("#E3E2E6")
    }
    views.setTextViewText(R.id.widget_item_amount, amountText)
    views.setTextColor(R.id.widget_item_amount, amountColor)

    val fillInIntent = Intent().apply {
        putExtra("extra_destination", "TRANSACTIONS")
    }
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
        runBlocking {
            val database = runCatching { LedgerDatabase.create(context) }.getOrNull()
            val ledger = database?.let { RoomLedgerRepository(it.transactions()) }
            val rawTxs = ledger?.observeTransactions()?.firstOrNull().orEmpty()
            val snapshot = runCatching { AnalysisStore(context).snapshot.value }.getOrNull()
            val enabled = runCatching { ProviderPreferences(context).enabled.value }.getOrNull()

            val enriched = rawTxs.map { tx ->
                val id = tx.providerTransactionId.substringAfter(":")
                val note = snapshot?.userNotes?.get(id) ?: tx.userNote
                val isCredit = tx.direction == Direction.CREDIT
                val cat = if (isCredit) null else {
                    snapshot?.categories?.get(id) ?: CategoryClassifier.classify(tx, note).category
                }
                tx.copy(
                    userNote = note,
                    category = cat,
                    isReversal = tx.rawPayload.contains("\"isReversal\":true") || tx.description.contains("(Reversed)"),
                )
            }

            transactions = LedgerReconciler.reconcileLedger(enriched)
                .filter { enabled == null || it.provider in enabled }
                .take(40)
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

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
