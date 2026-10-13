package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction
import java.time.YearMonth

data class CategorySpending(
    val category: String,
    val amountMinor: Long,
    val sharePercent: Float,
    val previousMonthAmountMinor: Long,
    val deltaPercent: Float?,
    val deltaAmountMinor: Long,
    val transactions: List<UnifiedTransaction>,
)

data class MonthSpendingAnalysis(
    val month: YearMonth,
    val totalPersonalSpendingMinor: Long,
    val previousMonthTotalMinor: Long,
    val totalDeltaPercent: Float?,
    val categories: List<CategorySpending>,
    val monthlyAiSummary: String?,
    val unsupportedCurrencyCount: Int = 0,
    val localSummary: String = "",
)

