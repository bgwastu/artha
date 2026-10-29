package net.wastu.artha.ui.widget

import java.math.BigInteger
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.ui.formatMinorAmount
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.Direction

internal const val RECENT_PAYMENT_LIMIT = 40

internal fun recentWidgetPayments(transactions: List<UnifiedTransaction>, enabled: Set<net.wastu.artha.core.model.Provider>): List<UnifiedTransaction> =
    transactions.asSequence().filter { it.isConfirmed && it.provider in enabled && it.direction == Direction.DEBIT }
        .sortedByDescending { it.occurredAt }.take(RECENT_PAYMENT_LIMIT).toList()

internal sealed interface WidgetPaymentRow {
    data class Day(val date: java.time.LocalDate) : WidgetPaymentRow
    data class Payment(val transaction: UnifiedTransaction) : WidgetPaymentRow
}

internal fun groupedWidgetPayments(transactions: List<UnifiedTransaction>, enabled: Set<net.wastu.artha.core.model.Provider>): List<WidgetPaymentRow> = buildList {
    val grouped = recentWidgetPayments(transactions, enabled).groupBy { it.occurredAt.atZone(AccountingTime.zone).toLocalDate() }
    grouped.forEach { (day, payments) ->
        add(WidgetPaymentRow.Day(day))
        payments.forEach { add(WidgetPaymentRow.Payment(it)) }
    }
}

internal fun widgetRowId(row: WidgetPaymentRow): Long = java.util.UUID.nameUUIDFromBytes(when (row) {
    is WidgetPaymentRow.Day -> "day:${row.date}"
    is WidgetPaymentRow.Payment -> "payment:${row.transaction.key}"
}.toByteArray(Charsets.UTF_8)).mostSignificantBits

internal fun widgetDayLabel(day: java.time.LocalDate, today: java.time.LocalDate = java.time.LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone))): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", Locale.US))
}

internal fun showAnalysisDetails(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
    widthDp >= 130 * fontScale && heightDp >= 90 * fontScale

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
    require(analysis.totalMinor >= 0 && analysis.previousMonthTotalMinor >= 0)
    return AnalysisWidgetAmounts(
        formatMinorAmount(BigInteger.valueOf(analysis.totalMinor)),
        analysis.previousMonthTotalMinor.takeIf { it > 0 }?.let { formatMinorAmount(BigInteger.valueOf(it)) },
    )
}

