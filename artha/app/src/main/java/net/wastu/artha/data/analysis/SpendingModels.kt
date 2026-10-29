package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction
import java.time.YearMonth

data class CategorySpending(
    val category: String?,
    val amountMinor: Long,
    val sharePercent: Float,
    val previousMonthAmountMinor: Long,
    val deltaPercent: Float?,
    val deltaAmountMinor: Long,
    val transactions: List<UnifiedTransaction>,
)

data class MonthSpendingAnalysis(
    val month: YearMonth,
    val type: net.wastu.artha.core.model.TransactionType = net.wastu.artha.core.model.TransactionType.EXPENSE,
    val totalMinor: Long,
    val previousMonthTotalMinor: Long,
    val totalDeltaPercent: Float?,
    val categories: List<CategorySpending>,
    val unsupportedCurrencyCount: Int = 0,
    val localSummary: String = "",
)


/** Shown wherever a payment has no category; the data itself stays null. */
const val UNCATEGORIZED_LABEL = "Uncategorized"

val CategorySpending.label: String get() = category ?: UNCATEGORIZED_LABEL
