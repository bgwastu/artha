package net.wastu.artha.ui.screens

import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.data.analysis.label

/** Month spending aggregated for the chart screen, testable independently of drawing. */
object SpendingChartLogic {
    data class Slice(
        val name: String,
        val amountMinor: Long,
        val percent: Int,
        val isOther: Boolean = false,
        val categories: Set<String> = setOf(name),
    )

    /** Top categories by spend, with remaining categories merged into "Others". */
    fun categorySlices(analysis: MonthSpendingAnalysis): List<Slice> {
        val nonZero = analysis.categories.filter { it.amountMinor > 0 }
        if (nonZero.isEmpty()) return emptyList()

        val sorted = nonZero.sortedByDescending { it.amountMinor }
        val rawList: List<Triple<String, Long, Set<String>>> = if (sorted.size <= 7) {
            sorted.map { Triple(it.label, it.amountMinor, setOf(it.label)) }
        } else {
            val kept = sorted.take(7)
            val restCategories = sorted.drop(7)
            val rest = restCategories.sumOf { it.amountMinor }
            if (rest > 0) {
                kept.map { Triple(it.label, it.amountMinor, setOf(it.label)) } +
                    Triple("Others", rest, restCategories.map { it.label }.toSet())
            } else {
                kept.map { Triple(it.label, it.amountMinor, setOf(it.label)) }
            }
        }

        val amounts = rawList.map { it.second }
        val percentages = calculatePercentages(amounts)
        return rawList.mapIndexed { index, (name, amount, cats) ->
            Slice(
                name = name,
                amountMinor = amount,
                percent = percentages[index],
                isOther = name == "Others",
                categories = cats,
            )
        }
    }

    /**
     * Largest Remainder Method (Hare-Niemeyer) guaranteeing integer percentages
     * sum to exactly 100 whenever total > 0.
     */
    fun calculatePercentages(amounts: List<Long>): List<Int> {
        val total = amounts.sumOf { it }
        if (total <= 0L || amounts.isEmpty()) return amounts.map { 0 }

        val exactShares = amounts.map { it.toDouble() * 100.0 / total.toDouble() }
        val floors = exactShares.map { it.toInt() }
        val sumFloors = floors.sum()
        val remainderToDistribute = (100 - sumFloors).coerceIn(0, amounts.size)

        val remainderIndices = exactShares.indices
            .map { i -> i to (exactShares[i] - floors[i]) }
            .sortedWith(
                compareByDescending<Pair<Int, Double>> { it.second }
                    .thenByDescending { amounts[it.first] }
            )
            .take(remainderToDistribute)
            .map { it.first }
            .toSet()

        return floors.mapIndexed { i, floor ->
            if (i in remainderIndices) floor + 1 else floor
        }
    }
}
