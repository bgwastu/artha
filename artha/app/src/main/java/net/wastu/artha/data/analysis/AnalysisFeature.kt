package net.wastu.artha.data.analysis

import android.content.Context

/** Widget-safe read of whether AI analysis is enabled; the default matches AnalysisStore. */
object AnalysisFeature {
    private const val PREFS = "artha_analysis_store"
    private const val KEY = "analysis_enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
}
