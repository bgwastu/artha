package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.YearMonth

object BudgetAnalyzer {
    fun analyzeMonth(
        month: YearMonth,
        allTransactions: List<UnifiedTransaction>,
        type: TransactionType = TransactionType.EXPENSE,
    ): MonthSpendingAnalysis {
        require(type != TransactionType.TRANSFER) { "Transfers are not analysed" }
        val zone = net.wastu.artha.core.model.AccountingTime.zone
        val currentMonthTxs = allTransactions.filter { YearMonth.from(it.occurredAt.atZone(zone)) == month }
        val prevMonth = month.minusMonths(1)
        val prevMonthTxs = allTransactions.filter { YearMonth.from(it.occurredAt.atZone(zone)) == prevMonth }

        fun counts(tx: UnifiedTransaction) = tx.type == type && tx.money.currencyCode == "IDR"
        val currentSpendingTxs = currentMonthTxs.filter(::counts)
        val prevSpendingTxs = prevMonthTxs.filter(::counts)

        val currentByCategory = currentSpendingTxs.groupBy { it.category }
        val prevByCategory = prevSpendingTxs.groupBy { it.category }

        fun sumCategory(txs: List<UnifiedTransaction>): Long =
            txs.sumOf { it.money.amountMinor }

        val prevTotal = prevSpendingTxs.sumOf { it.money.amountMinor }
        val categories = currentByCategory.map { (cat, txs) ->
            val catCurrent = sumCategory(txs)
            val catPrev = sumCategory(prevByCategory[cat].orEmpty())
            val deltaPercent = if (catPrev > 0L) ((catCurrent - catPrev).toFloat() / catPrev.toFloat()) * 100f else null
            CategorySpending(
                category = cat,
                amountMinor = catCurrent,
                sharePercent = 0f,
                previousMonthAmountMinor = catPrev,
                deltaPercent = deltaPercent,
                deltaAmountMinor = catCurrent - catPrev,
                transactions = txs.sortedByDescending { it.occurredAt },
            )
        }.filter { it.amountMinor > 0L }.sortedByDescending { it.amountMinor }

        val totalCurrent = categories.sumOf { it.amountMinor }
        val categoriesWithShare = categories.map {
            val share = if (totalCurrent > 0L) (it.amountMinor.toFloat() / totalCurrent.toFloat()) * 100f else 0f
            it.copy(sharePercent = share)
        }
        val totalDelta = if (prevTotal > 0L) ((totalCurrent - prevTotal).toFloat() / prevTotal.toFloat()) * 100f else null

        return MonthSpendingAnalysis(
            month = month,
            type = type,
            totalMinor = totalCurrent,
            previousMonthTotalMinor = prevTotal,
            totalDeltaPercent = totalDelta,
            categories = categoriesWithShare,
            unsupportedCurrencyCount = currentMonthTxs.count { it.money.currencyCode != "IDR" },
        ).let { it.copy(localSummary = localSummary(it)) }
    }

    fun localSummary(analysis: MonthSpendingAnalysis): String {
        fun rupiah(amount: Long) = net.wastu.artha.core.model.formatMinorAmount(java.math.BigInteger.valueOf(amount))
        if (analysis.totalMinor == 0L) return if (analysis.type == TransactionType.INCOME) "- No income recorded for this month." else "- No spending recorded for this month."
        return buildString {
            append("- ${if (analysis.type == TransactionType.INCOME) "Income" else "Spending"}: ${rupiah(analysis.totalMinor)}.\n")
            analysis.categories.firstOrNull()?.let { append("- Largest category: ${it.category}, ${rupiah(it.amountMinor)} (${it.sharePercent.toInt()}%).\n") }
            if (analysis.previousMonthTotalMinor > 0) append("- Previous recorded month: ${rupiah(analysis.previousMonthTotalMinor)}.\n")
            val largest = analysis.categories.flatMap { it.transactions }.maxByOrNull { it.money.amountMinor }
            largest?.let { append("- Largest payment: ${it.description}, ${rupiah(it.money.amountMinor)}.") }
        }.trim()
    }
}
