package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.YearMonth
import org.json.JSONObject

object BudgetAnalyzer {
    fun analyzeMonth(
        month: YearMonth,
        allTransactions: List<UnifiedTransaction>,
        aiSummary: String? = null,
    ): MonthSpendingAnalysis {
        val zone = net.wastu.artha.core.model.AccountingTime.zone
        val currentMonthTxs = allTransactions.filter { YearMonth.from(it.occurredAt.atZone(zone)) == month }
        val prevMonth = month.minusMonths(1)
        val prevMonthTxs = allTransactions.filter { YearMonth.from(it.occurredAt.atZone(zone)) == prevMonth }

        val currentSpendingTxs = currentMonthTxs.filter(::isSpending)
        val prevSpendingTxs = prevMonthTxs.filter(::isSpending)

        val currentByCategory = currentSpendingTxs.groupBy { it.category ?: CategoryConstants.UNCATEGORIZED }
        val prevByCategory = prevSpendingTxs.groupBy { it.category ?: CategoryConstants.UNCATEGORIZED }

        fun sumCategory(txs: List<UnifiedTransaction>): Long =
            txs.filter { it.direction == Direction.DEBIT }.sumOf { it.money.amountMinor }

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
            totalPersonalSpendingMinor = totalCurrent,
            previousMonthTotalMinor = prevTotal,
            totalDeltaPercent = totalDelta,
            categories = categoriesWithShare,
            monthlyAiSummary = aiSummary,
            unconfirmedSpendingMinor = currentMonthTxs.filter { !it.isConfirmed && it.direction == Direction.DEBIT && it.money.currencyCode == "IDR" }.sumOf { it.money.amountMinor },
            unsupportedCurrencyCount = currentMonthTxs.count { it.money.currencyCode != "IDR" },
        ).let { it.copy(localSummary = localSummary(it)) }
    }

    fun localSummary(analysis: MonthSpendingAnalysis): String {
        fun rupiah(amount: Long) = net.wastu.artha.core.model.formatMinorAmount(java.math.BigInteger.valueOf(amount))
        if (analysis.totalPersonalSpendingMinor == 0L) return "- No confirmed personal spending recorded for this month."
        return buildString {
            append("- Confirmed spending: ${rupiah(analysis.totalPersonalSpendingMinor)}.\n")
            analysis.categories.firstOrNull()?.let { append("- Largest category: ${it.category}, ${rupiah(it.amountMinor)} (${it.sharePercent.toInt()}%).\n") }
            if (analysis.previousMonthTotalMinor > 0) append("- Previous recorded month: ${rupiah(analysis.previousMonthTotalMinor)}.\n")
            val largest = analysis.categories.flatMap { it.transactions }.maxByOrNull { it.money.amountMinor }
            largest?.let { append("- Largest payment: ${it.description}, ${rupiah(it.money.amountMinor)}.") }
        }.trim()
    }

    fun isSpending(tx: UnifiedTransaction): Boolean {
        if (tx.direction != Direction.DEBIT || !tx.isConfirmed || tx.money.currencyCode != "IDR") return false
        if (tx.isReversal || tx.description.contains("(Reversed)", ignoreCase = true)) return false
        val p = runCatching { JSONObject(tx.rawPayload) }.getOrNull()
        if (p?.optBoolean("isWalletFunding") == true) return false
        if (p?.optBoolean("isPocketTransfer") == true || tx.description.startsWith("Pocket Transfer:", ignoreCase = true)) return false
        val code = p?.optString("code").orEmpty()
        if (code in setOf("TDD02", "TDC02", "TDC03", "TDC08", "TDC12", "SAD01", "SAD02", "SAC01", "SAC02")) return false
        if (p?.optJSONObject("fields")?.optString("f22") == "INTERNAL") return false
        return true // Accounting exclusion is source evidence, never an editable display category.
    }

    fun computeMonthFingerprint(
        monthTransactions: List<UnifiedTransaction>,
        monthlyNote: String,
        knowledge: String,
        customCategories: List<String>,
    ): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("commentary-v3\n".toByteArray(Charsets.UTF_8))
        md.update("note:${monthlyNote.trim()}\n".toByteArray(Charsets.UTF_8))
        md.update("knowledge:${knowledge.trim()}\n".toByteArray(Charsets.UTF_8))
        md.update("cats:${customCategories.sorted().joinToString(",")}\n".toByteArray(Charsets.UTF_8))
        for (tx in monthTransactions.sortedBy { it.key }) {
            md.update("${tx.key}:${tx.money.amountMinor}:${tx.money.currencyCode}:${tx.occurredAt}:${tx.description}:${tx.direction}:${tx.isConfirmed}:${tx.isReversal}:${tx.userNote.orEmpty()}:${tx.category.orEmpty()}\n".toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
