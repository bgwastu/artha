package net.wastu.artha.ui

import java.time.YearMonth
import net.wastu.artha.data.analysis.CategorySpending
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.ui.screens.SpendingChartLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendingChartLogicTest {

    @Test
    fun calculatePercentagesSumsTo100WithUserSampleData() {
        // Values from user's chart screenshot:
        // Taxes & Fees: 1,232,000 (23.88%)
        // Health & Fitness: 1,228,500 (23.81%)
        // Tech & Subscriptions: 724,647 (14.04%)
        // Dining Out: 592,850 (11.49%)
        // Utilities & Bills: 487,350 (9.45%)
        // Groceries: 384,500 (7.45%)
        // Others: 509,964 (9.88%)
        val amounts = listOf(
            1_232_000L,
            1_228_500L,
            724_647L,
            592_850L,
            487_350L,
            384_500L,
            509_964L,
        )
        val percentages = SpendingChartLogic.calculatePercentages(amounts)
        assertEquals(listOf(24, 24, 14, 12, 9, 7, 10), percentages)
        assertEquals(100, percentages.sum())
    }

    @Test
    fun calculatePercentagesHandlesEqualSplits() {
        val threeWay = SpendingChartLogic.calculatePercentages(listOf(100L, 100L, 100L))
        assertEquals(100, threeWay.sum())
        assertEquals(listOf(34, 33, 33), threeWay)
    }

    @Test
    fun calculatePercentagesHandlesEmptyAndZero() {
        assertEquals(emptyList<Int>(), SpendingChartLogic.calculatePercentages(emptyList()))
        assertEquals(listOf(0, 0), SpendingChartLogic.calculatePercentages(listOf(0L, 0L)))
    }

    @Test
    fun categorySlicesPreservesUpToSevenCategoriesWithoutOthers() {
        val categories = (1..7).map { i ->
            CategorySpending(
                category = "Cat $i",
                amountMinor = (10 - i) * 100_000L,
                sharePercent = 0f,
                previousMonthAmountMinor = 0L,
                deltaPercent = null,
                deltaAmountMinor = 0L,
                transactions = emptyList(),
            )
        }
        val analysis = MonthSpendingAnalysis(
            month = YearMonth.of(2026, 10),
            totalPersonalSpendingMinor = categories.sumOf { it.amountMinor },
            previousMonthTotalMinor = 0L,
            totalDeltaPercent = null,
            categories = categories,
        )

        val slices = SpendingChartLogic.categorySlices(analysis)
        assertEquals(7, slices.size)
        assertTrue(slices.none { it.isOther })
        assertEquals(100, slices.sumOf { it.percent })
    }

    @Test
    fun categorySlicesGroupsOverflowIntoOthers() {
        val categories = (1..10).map { i ->
            CategorySpending(
                category = "Cat $i",
                amountMinor = (11 - i) * 100_000L,
                sharePercent = 0f,
                previousMonthAmountMinor = 0L,
                deltaPercent = null,
                deltaAmountMinor = 0L,
                transactions = emptyList(),
            )
        }
        val analysis = MonthSpendingAnalysis(
            month = YearMonth.of(2026, 10),
            totalPersonalSpendingMinor = categories.sumOf { it.amountMinor },
            previousMonthTotalMinor = 0L,
            totalDeltaPercent = null,
            categories = categories,
        )

        val slices = SpendingChartLogic.categorySlices(analysis)
        assertEquals(8, slices.size)
        assertEquals(7, slices.count { !it.isOther })
        val others = slices.last()
        assertTrue(others.isOther)
        assertEquals("Others", others.name)
        // Cats 8, 9, 10 = (11-8)*100k + (11-9)*100k + (11-10)*100k = 300k + 200k + 100k = 600k
        assertEquals(600_000L, others.amountMinor)
        assertEquals(100, slices.sumOf { it.percent })
    }
}
