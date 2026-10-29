package net.wastu.artha.ui.widget

import androidx.core.net.toUri
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
import android.os.Build
import android.net.Uri
import android.widget.RemoteViewsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.wastu.artha.data.analysis.CategorySpending

class AnalysisWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        WidgetRefresh.bind(context)
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
        WidgetRefresh.bind(context)
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
                val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val width = if (isLandscape) options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 180)
                    else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                val height = if (isLandscape) options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                    else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110))
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
                val categories = analysis.categories
                views.setViewVisibility(R.id.widget_categories_list, if (categories.isEmpty()) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_empty_text, if (categories.isEmpty() && detailed) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.widget_empty_text, "No spending this month")

                val openAnalysisTemplate = PendingIntent.getActivity(
                    context,
                    102,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("extra_destination", "ANALYSIS")
                    },
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    },
                )
                views.setPendingIntentTemplate(R.id.widget_categories_list, openAnalysisTemplate)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val builder = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(true)
                    for ((index, cat) in categories.withIndex()) {
                        builder.addItem(index.toLong(), buildCategoryRowView(context, cat))
                    }
                    views.setRemoteAdapter(R.id.widget_categories_list, builder.build())
                } else {
                    val serviceIntent = Intent(context, AnalysisWidgetService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        data = toUri(Intent.URI_INTENT_SCHEME).toUri()
                    }
                    @Suppress("DEPRECATION")
                    views.setRemoteAdapter(R.id.widget_categories_list, serviceIntent)
                }
                @Suppress("DEPRECATION")
                appWidgetManager.notifyAppWidgetViewDataChanged(id, R.id.widget_categories_list)
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                appWidgetManager.updateAppWidget(id, views)
                WidgetUpdatedAt.save(context, id, updatedAt)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "net.wastu.artha.UPDATE_WIDGET"

        fun triggerUpdate(context: Context) {
            WidgetRefresh.bind(context)
            val intent = Intent(context, AnalysisWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
            TransactionsWidgetProvider.triggerUpdate(context)
        }
    }
}

internal fun buildCategoryRowView(context: Context, cat: CategorySpending): RemoteViews {
    val rowView = RemoteViews(context.packageName, R.layout.widget_category_item)
    rowView.setTextViewText(R.id.widget_category_name, cat.category)
    rowView.setTextViewText(R.id.widget_category_amount, formatMoney(Money(cat.amountMinor)))
    val fillInIntent = Intent().apply { putExtra("extra_destination", "ANALYSIS") }
    rowView.setOnClickFillInIntent(R.id.widget_category_item_root, fillInIntent)
    return rowView
}

class AnalysisWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return AnalysisRemoteViewsFactory(applicationContext)
    }
}

private class AnalysisRemoteViewsFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {
    private var categories: List<CategorySpending> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking(Dispatchers.IO) {
            try {
                val month = YearMonth.now(net.wastu.artha.core.model.AccountingTime.clock)
                val analysis = BudgetAnalyzer.analyzeMonth(month, widgetSpending(context).transactions)
                categories = analysis.categories
            } catch (e: Exception) { android.util.Log.w("ArthaWidget", "Analysis widget refresh failed", e) }
        }
    }

    override fun onDestroy() {
        categories = emptyList()
    }

    override fun getCount(): Int = categories.size

    override fun getViewAt(position: Int): RemoteViews? {
        val cat = categories.getOrNull(position) ?: return null
        return buildCategoryRowView(context, cat)
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
