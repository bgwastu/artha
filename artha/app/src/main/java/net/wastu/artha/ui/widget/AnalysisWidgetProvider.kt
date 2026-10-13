package net.wastu.artha.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.core.model.Money
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.CategoryClassifier
import net.wastu.artha.data.ledger.LedgerDatabase
import net.wastu.artha.data.ledger.RoomLedgerRepository
import net.wastu.artha.data.reconciliation.LedgerReconciler
import net.wastu.artha.ui.formatMoney
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class AnalysisWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, AnalysisWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateWidgets(context, appWidgetManager, ids)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val targetIds = if (appWidgetIds.isNotEmpty()) appWidgetIds else {
            appWidgetManager.getAppWidgetIds(ComponentName(context, AnalysisWidgetProvider::class.java))
        }
        if (targetIds.isNotEmpty()) {
            updateWidgets(context, appWidgetManager, targetIds)
        }
    }

    private fun updateWidgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("extra_destination", "ANALYSIS")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nowMonth = YearMonth.now()
                val monthTitle = nowMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))

                val database = runCatching { LedgerDatabase.create(context) }.getOrNull()
                val ledger = database?.let { RoomLedgerRepository(it.transactions()) }
                val transactions = ledger?.observeTransactions()?.firstOrNull().orEmpty()
                val snapshot = runCatching { net.wastu.artha.data.analysis.TransactionMetadata.migrate(AnalysisStore(context).snapshot.value, transactions) }.getOrNull()

                val reconciled = LedgerReconciler.reconcileLedger(transactions).map { tx ->
                    val key = tx.key
                    val cat = snapshot?.categories?.get(key)
                        ?: CategoryClassifier.classify(tx, snapshot?.userNotes?.get(key)).category
                    tx.copy(category = cat)
                }
                val analysis = BudgetAnalyzer.analyzeMonth(nowMonth, reconciled)
                val totalCurrent = analysis.totalPersonalSpendingMinor
                val delta = analysis.totalDeltaPercent
                val deltaText = if (delta != null && analysis.previousMonthTotalMinor > 0L) {
                    val symbol = if (delta < 0) "▼" else "▲"
                    "$symbol ${kotlin.math.abs(delta).toInt()}% vs last month"
                } else {
                    null
                }

                val topCats = analysis.categories.take(3)

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_analysis)
                    views.setTextViewText(R.id.widget_month_title, monthTitle)
                    views.setTextViewText(
                        R.id.widget_total_amount,
                        if (totalCurrent <= 0L) "Rp0" else formatMoney(Money(totalCurrent)),
                    )

                    if (deltaText != null) {
                        views.setViewVisibility(R.id.widget_delta_text, View.VISIBLE)
                        views.setTextViewText(R.id.widget_delta_text, deltaText)
                    } else {
                        views.setViewVisibility(R.id.widget_delta_text, View.GONE)
                    }

                    if (topCats.isEmpty()) {
                        views.setViewVisibility(R.id.widget_categories_container, View.GONE)
                        views.setViewVisibility(R.id.widget_empty_text, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_categories_container, View.VISIBLE)
                        views.setViewVisibility(R.id.widget_empty_text, View.GONE)

                        val rowIds = listOf(R.id.widget_row_cat1, R.id.widget_row_cat2, R.id.widget_row_cat3)
                        val nameIds = listOf(R.id.widget_cat1_name, R.id.widget_cat2_name, R.id.widget_cat3_name)
                        val amtIds = listOf(R.id.widget_cat1_amount, R.id.widget_cat2_amount, R.id.widget_cat3_amount)

                        for (i in 0 until 3) {
                            val cat = topCats.getOrNull(i)
                            if (cat != null) {
                                views.setViewVisibility(rowIds[i], View.VISIBLE)
                                views.setTextViewText(nameIds[i], "${cat.category} (${cat.sharePercent.toInt()}%)")
                                views.setTextViewText(
                                    amtIds[i],
                                    if (cat.amountMinor <= 0L) "Rp0" else formatMoney(Money(cat.amountMinor)),
                                )
                            } else {
                                views.setViewVisibility(rowIds[i], View.GONE)
                            }
                        }
                    }

                    views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "net.wastu.artha.UPDATE_WIDGET"

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, AnalysisWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
            TransactionsWidgetProvider.triggerUpdate(context)
        }
    }
}
