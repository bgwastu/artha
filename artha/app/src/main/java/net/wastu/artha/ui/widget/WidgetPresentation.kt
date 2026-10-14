package net.wastu.artha.ui.widget

import java.math.BigInteger
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.ui.formatMinorAmount
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction

internal const val RECENT_PAYMENT_LIMIT = 40

internal fun recentWidgetPayments(transactions: List<UnifiedTransaction>, enabled: Set<net.wastu.artha.core.model.Provider>): List<UnifiedTransaction> =
    transactions.asSequence().filter { it.isConfirmed && it.provider in enabled }
        .sortedByDescending { it.occurredAt }.take(RECENT_PAYMENT_LIMIT).toList()

internal fun widgetPaymentScope(totalCount: Int): String =
    if (totalCount > RECENT_PAYMENT_LIMIT) "Latest $RECENT_PAYMENT_LIMIT payments" else "$totalCount payments"

internal fun showAnalysisDetails(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
    widthDp >= 240 * fontScale && heightDp >= 240 * fontScale

internal fun widgetFreshness(updatedAt: Long?, now: Long, updating: Boolean = false, failed: Boolean = false): String {
    if (updatedAt == null) return when {
        failed -> "Saved data unavailable"
        else -> "Loading saved payments"
    }
    val time = Instant.ofEpochMilli(updatedAt).atZone(AccountingTime.zone)
        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US))
    val state = when {
        failed -> "Update failed"
        updating -> "Updating"
        now - updatedAt >= 60 * 60 * 1000L -> "May be out of date"
        else -> "Updated"
    }
    return if (state == "Updated") "$state $time" else "$state\nUpdated $time"
}

/** Fixed dark widget palette, independent of the host launcher's theme. */
internal object WidgetPalette {
    const val background = 0xFF1A1C1E.toInt()
    const val text = 0xFFF1F0F4.toInt()
    const val supporting = 0xFFC4C6CF.toInt()
    const val income = 0xFFAACAB2.toInt()
    const val expense = 0xFFD6ADAD.toInt()
}

/** Aggregates can be zero even though individual recorded payments must be positive. */
internal data class AnalysisWidgetAmounts(val total: String, val previous: String?)
internal fun analysisWidgetAmounts(analysis: MonthSpendingAnalysis): AnalysisWidgetAmounts {
    require(analysis.totalPersonalSpendingMinor >= 0 && analysis.previousMonthTotalMinor >= 0)
    return AnalysisWidgetAmounts(
        formatMinorAmount(BigInteger.valueOf(analysis.totalPersonalSpendingMinor)),
        analysis.previousMonthTotalMinor.takeIf { it > 0 }?.let { formatMinorAmount(BigInteger.valueOf(it)) },
    )
}
