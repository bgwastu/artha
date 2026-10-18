package net.wastu.artha.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.core.model.Money
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.ui.formatMoney
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class AnalysisWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, AnalysisWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateWidgets(context, appWidgetManager, ids)
                (context.applicationContext as? net.wastu.artha.ArthaApplication)?.syncInBackgroundIfStale()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val targetIds = if (appWidgetIds.isNotEmpty()) appWidgetIds else {
            appWidgetManager.getAppWidgetIds(ComponentName(context, AnalysisWidgetProvider::class.java))
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

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("extra_destination", "ANALYSIS")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun status(failed: Boolean = false) {
            for (id in appWidgetIds) {
                val previous = WidgetUpdatedAt.read(context, id)
                val views = RemoteViews(context.packageName, R.layout.widget_analysis)
                views.setTextViewText(R.id.widget_status, widgetFreshness(previous, System.currentTimeMillis(), updating = !failed, failed = failed))
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        }
        status()
        WidgetRefresh.schedule("analysis", pendingResult, onFailure = { status(failed = true) }) {
            val month = YearMonth.now(net.wastu.artha.core.model.AccountingTime.clock)
            val analysis = BudgetAnalyzer.analyzeMonth(month, widgetSpending(context).transactions)
            val amounts = analysisWidgetAmounts(analysis)
            val updatedAt = System.currentTimeMillis()
            for (id in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(id)
                val fontScale = context.resources.configuration.fontScale
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                val detailed = showAnalysisDetails(width, height, fontScale)
                val views = RemoteViews(context.packageName, R.layout.widget_analysis)
                val monthTitle = month.format(DateTimeFormatter.ofPattern(if (width < 240 * fontScale) "MMM yyyy" else "MMMM yyyy", Locale.US))
                views.setTextViewText(R.id.widget_month_title, monthTitle)
                views.setTextViewText(R.id.widget_total_amount, amounts.total)
                views.setTextViewText(R.id.widget_status, widgetFreshness(updatedAt, updatedAt))
                views.setViewVisibility(R.id.widget_details, if (detailed) View.VISIBLE else View.GONE)
                // The current month is incomplete. Compare amounts with an explicitly full prior month.
                views.setViewVisibility(R.id.widget_delta_text, if (amounts.previous == null) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.widget_delta_text, amounts.previous?.let { "Last month $it" }.orEmpty())
                val topCats = analysis.categories.take(3)
                views.setViewVisibility(R.id.widget_categories_container, if (topCats.isEmpty()) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_empty_text, if (topCats.isEmpty() && detailed) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.widget_empty_text, "No spending this month")
                val rowIds = listOf(R.id.widget_row_cat1, R.id.widget_row_cat2, R.id.widget_row_cat3)
                val nameIds = listOf(R.id.widget_cat1_name, R.id.widget_cat2_name, R.id.widget_cat3_name)
                val amtIds = listOf(R.id.widget_cat1_amount, R.id.widget_cat2_amount, R.id.widget_cat3_amount)
                for (i in 0..2) {
                    val cat = topCats.getOrNull(i)
                    views.setViewVisibility(rowIds[i], if (cat == null) View.GONE else View.VISIBLE)
                    if (cat != null) {
                        views.setTextViewText(nameIds[i], cat.category)
                        views.setTextViewText(amtIds[i], formatMoney(Money(cat.amountMinor)))
                    }
                }
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                appWidgetManager.updateAppWidget(id, views)
                WidgetUpdatedAt.save(context, id, updatedAt)
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
