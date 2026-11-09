package net.wastu.artha.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.koalaplot.core.pie.CircularLabelPositionProvider
import io.github.koalaplot.core.pie.DefaultSlice
import io.github.koalaplot.core.pie.PieChart
import io.github.koalaplot.core.pie.StraightLineConnector
import io.github.koalaplot.core.util.ExperimentalKoalaPlotApi
import kotlin.math.abs
import net.wastu.artha.core.model.Money
import net.wastu.artha.ui.categoryColorFor
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatMoney

/** A slice is labelled when it is big enough to read, or when it is the picked one. */
private fun SpendingChartLogic.Slice.showsLabel(index: Int, selected: Int?) = percent >= 4 || selected == index

/** Category donut: tap a slice to pick it, tap the hole to open the picked category. */
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
                if (slice != null && slice.showsLabel(index, selected)) {
                    StraightLineConnector(
                        connectorColor = categoryColorFor(slice.name, slice.isOther),
                        connectorStroke = Stroke(width = if (selected == index) 1.5f else 1f),
                    )
                }
            },
            label = { index ->
                val slice = slices.getOrNull(index)
                if (slice != null && slice.showsLabel(index, selected)) SliceLabel(slice, bold = selected == index)
            },
            slice = { index ->
                val slice = slices.getOrNull(index)
                val isSelected = selected == index
                if (slice != null) {
                    DefaultSlice(
                        color = categoryColorFor(slice.name, slice.isOther),
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
                DonutHole(picked, icons, total, totalDeltaPercent, canGoNext, onOpenCategory)
            },
        )
    }
}

@Composable
private fun SliceLabel(slice: SpendingChartLogic.Slice, bold: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = slice.name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${slice.percent}%",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Bold,
            color = categoryColorFor(slice.name, slice.isOther),
            maxLines = 1,
        )
    }
}

/** The centre of the donut: the picked category when there is one, otherwise the month total. */
@Composable
private fun DonutHole(
    picked: SpendingChartLogic.Slice?,
    icons: Map<String, String>,
    total: Long,
    totalDeltaPercent: Float?,
    canGoNext: Boolean,
    onOpenCategory: (SpendingChartLogic.Slice) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 140.dp)
                .clip(MaterialTheme.shapes.small)
                .then(if (picked != null) Modifier.clickable { onOpenCategory(picked) } else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = picked, label = "hole_content") { state ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (state != null) PickedSliceDetail(state, icons) else SpendingTotal(total, totalDeltaPercent, canGoNext)
                }
            }
        }
    }
}

@Composable
private fun PickedSliceDetail(slice: SpendingChartLogic.Slice, icons: Map<String, String>) {
    Icon(
        categoryIconFor(slice.name, icons),
        contentDescription = null,
        tint = categoryColorFor(slice.name, slice.isOther),
        modifier = Modifier.size(20.dp),
    )
    Text(
        slice.name,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        "${slice.percent}%",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
    Text(
        formatMoney(Money(slice.amountMinor)),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun SpendingTotal(total: Long, deltaPercent: Float?, canGoNext: Boolean) {
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
    if (deltaPercent != null) {
        val delta = deltaPercent.toInt()
        Text(
            "${abs(delta)}% ${if (delta < 0) "less" else "more"}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
