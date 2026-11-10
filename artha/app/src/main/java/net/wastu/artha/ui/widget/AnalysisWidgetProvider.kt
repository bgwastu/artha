package net.wastu.artha.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import net.wastu.artha.core.log.ArthaLog
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.net.toUri
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.wastu.artha.ArthaApplication
import net.wastu.artha.MainActivity
import net.wastu.artha.R
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Money
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.CategorySpending
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.data.analysis.label
import net.wastu.artha.ui.formatMoney

class AnalysisWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        WidgetRefresh.bind(context)
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, AnalysisWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateWidgets(context, appWidgetManager, ids)
                (context.applicationContext as? ArthaApplication)?.syncInBackgroundIfStale()
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
        val openAnalysis = PendingIntent.getActivity(
            context, 0, openAnalysisIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun status(failed: Boolean = false) {
            for (id in appWidgetIds) {
                val previous = WidgetUpdatedAt.read(context, id)
                val views = RemoteViews(context.packageName, R.layout.widget_analysis)
                views.setTextViewText(R.id.widget_status, widgetFreshness(previous, System.currentTimeMillis(), updating = !failed, failed = failed))
                views.setOnClickPendingIntent(R.id.widget_root, openAnalysis)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        }
        status()
        WidgetRefresh.schedule("analysis", pendingResult, onFailure = { status(failed = true) }) {
            val month = YearMonth.now(AccountingTime.clock)
            val analysis = BudgetAnalyzer.analyzeMonth(month, widgetSpending(context).transactions)
            val amounts = analysisWidgetAmounts(analysis)
            val updatedAt = System.currentTimeMillis()
            for (id in appWidgetIds) {
                val views = analysisViews(context, appWidgetManager.getAppWidgetOptions(id), month, analysis, amounts, updatedAt)
                setCategoryList(context, views, id, analysis.categories)
                @Suppress("DEPRECATION")
                appWidgetManager.notifyAppWidgetViewDataChanged(id, R.id.widget_categories_list)
                views.setOnClickPendingIntent(R.id.widget_root, openAnalysis)
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

private fun openAnalysisIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    putExtra("extra_destination", "ANALYSIS")
}

/** The widget's size in dp, from the portrait or landscape bounds the launcher reports. */
private fun widgetSize(context: Context, options: Bundle): Pair<Int, Int> {
    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
    val width = options.getInt(
        if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180,
    )
    val height = if (landscape) minHeight else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
    return width to height
}

/** Title, total, freshness and the details section; the category list is attached by [setCategoryList]. */
private fun analysisViews(
    context: Context,
    options: Bundle,
    month: YearMonth,
    analysis: MonthSpendingAnalysis,
    amounts: AnalysisWidgetAmounts,
    updatedAt: Long,
): RemoteViews {
    val fontScale = context.resources.configuration.fontScale
    val (width, height) = widgetSize(context, options)
    val detailed = showAnalysisDetails(width, height, fontScale)
    val noCategories = analysis.categories.isEmpty()
    return RemoteViews(context.packageName, R.layout.widget_analysis).apply {
        setTextViewText(R.id.widget_month_title, month.format(DateTimeFormatter.ofPattern(if (width < 240 * fontScale) "MMM yyyy" else "MMMM yyyy", Locale.US)))
        setTextViewText(R.id.widget_total_amount, amounts.total)
        setTextViewText(R.id.widget_status, widgetFreshness(updatedAt, updatedAt))
        setViewVisibility(R.id.widget_details, if (detailed) View.VISIBLE else View.GONE)
        // The current month is incomplete. Compare amounts with an explicitly full prior month.
        setViewVisibility(R.id.widget_delta_text, if (amounts.previous == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_delta_text, amounts.previous?.let { "Last month $it" }.orEmpty())
        setViewVisibility(R.id.widget_categories_list, if (noCategories) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.widget_empty_text, if (noCategories && detailed) View.VISIBLE else View.GONE)
        setTextViewText(R.id.widget_empty_text, "No spending this month")
    }
}

/** Android 12+ ships the rows inline; older versions go through [AnalysisWidgetService]. */
private fun setCategoryList(context: Context, views: RemoteViews, widgetId: Int, categories: List<CategorySpending>) {
    val template = PendingIntent.getActivity(
        context,
        102,
        openAnalysisIntent(context),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT,
    )
    views.setPendingIntentTemplate(R.id.widget_categories_list, template)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val builder = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(true)
        for ((index, category) in categories.withIndex()) builder.addItem(index.toLong(), buildCategoryRowView(context, category))
        views.setRemoteAdapter(R.id.widget_categories_list, builder.build())
    } else {
        val serviceIntent = Intent(context, AnalysisWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = toUri(Intent.URI_INTENT_SCHEME).toUri()
        }
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(R.id.widget_categories_list, serviceIntent)
    }
}

internal fun buildCategoryRowView(context: Context, cat: CategorySpending): RemoteViews {
    val rowView = RemoteViews(context.packageName, R.layout.widget_category_item)
    rowView.setTextViewText(R.id.widget_category_name, cat.label)
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
                val month = YearMonth.now(AccountingTime.clock)
                val analysis = BudgetAnalyzer.analyzeMonth(month, widgetSpending(context).transactions)
                categories = analysis.categories
            } catch (e: Exception) { ArthaLog.w("ArthaWidget", "Analysis widget refresh failed", e) }
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
