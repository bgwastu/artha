package net.wastu.artha.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextOverflow
import io.github.koalaplot.core.pie.CircularLabelPositionProvider
import io.github.koalaplot.core.pie.DefaultSlice
import io.github.koalaplot.core.pie.PieChart
import io.github.koalaplot.core.pie.StraightLineConnector
import io.github.koalaplot.core.util.ExperimentalKoalaPlotApi
import net.wastu.artha.core.model.Money
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.data.analysis.label
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatMoney

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

/**
 * Categorical palette: distinct hues for at-a-glance slice identification, mid-tones so it
 * reads on both surfaces. "Others" gets a muted neutral tone.
 */
internal fun sliceColor(sliceName: String, isOther: Boolean = false): Color =
    net.wastu.artha.ui.categoryColorFor(sliceName, isOther)

internal fun sliceColor(index: Int, isOther: Boolean = false): Color =
    if (isOther) Color(0xFF6B7280) else net.wastu.artha.ui.categoryColorFor("")

/** One slice the donut draws; pixel math lives with the canvas. */
@OptIn(ExperimentalKoalaPlotApi::class)
@Composable
internal fun DonutChart(
    slices: List<SpendingChartLogic.Slice>,
    icons: Map<String, String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onOpenCategory: (SpendingChartLogic.Slice) -> Unit,
    total: Long,
    modifier: Modifier = Modifier,
    totalDeltaPercent: Float? = null,
    canGoNext: Boolean = true,
) {
    if (slices.isEmpty()) return

    val values = remember(slices) { slices.map { it.amountMinor.toFloat() } }
    val picked = selected?.let { slices.getOrNull(it) }

    Box(modifier, contentAlignment = Alignment.Center) {
        PieChart(
            values = values,
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp),
            holeSize = 0.58f,
            minPieDiameter = 200.dp,
            maxPieDiameter = 240.dp,
            forceCenteredPie = true,
            labelPositionProvider = CircularLabelPositionProvider(1.10f),
            labelConnector = { index ->
                val slice = slices.getOrNull(index)
                if (slice != null && (slice.percent >= 4 || selected == index)) {
                    StraightLineConnector(
                        connectorColor = sliceColor(slice.name, slice.isOther),
                        connectorStroke = Stroke(width = if (selected == index) 1.5f else 1f),
                    )
                }
            },
            label = { index ->
                val slice = slices.getOrNull(index)
                if (slice != null && (slice.percent >= 4 || selected == index)) {
                    val color = sliceColor(slice.name, slice.isOther)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        Text(
                            text = slice.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${slice.percent}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold,
                            color = color,
                            maxLines = 1,
                        )
                    }
                }
            },
            slice = { index ->
                val slice = slices.getOrNull(index)
                val isSelected = selected == index
                if (slice != null) {
                    DefaultSlice(
                        color = sliceColor(slice.name, slice.isOther),
                        hoverExpandFactor = 1.06f,
                        antiAlias = true,
                        gap = if (slices.size > 1) 2.5f else 0f,
                        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
                        clickable = true,
                        onClick = { onSelect(if (isSelected) null else index) },
                    )
                }
            },
            holeContent = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 140.dp)
                            .clip(MaterialTheme.shapes.small)
                            .then(
                                if (picked != null) {
                                    Modifier.clickable { onOpenCategory(picked) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Crossfade(targetState = picked, label = "hole_content") { state ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (state != null) {
                                    Icon(
                                        categoryIconFor(state.name, icons),
                                        contentDescription = null,
                                        tint = sliceColor(state.name, state.isOther),
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        state.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${state.percent}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                    )
                                    Text(
                                        formatMoney(Money(state.amountMinor)),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                } else {
                                    Text(
                                        if (canGoNext) "Spending" else "Spending to date",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Text(
                                        formatMoney(Money(total)),
                                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                    )
                                    if (totalDeltaPercent != null) {
                                        val delta = totalDeltaPercent.toInt()
                                        val deltaText = "${kotlin.math.abs(delta)}% ${if (delta < 0) "less" else "more"}"
                                        Text(
                                            deltaText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
