package net.wastu.artha.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    val shown = rememberAnimatedSlices(slices)
    val values = shown.map { it.second }
    val picked = selected?.let { slices.getOrNull(it) }

    Box(modifier, contentAlignment = Alignment.Center) {
        PieChart(
            values = values,
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp),
            holeSize = 0.58f,
            pieAnimationSpec = snap(),
            labelAnimationSpec = snap(),
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
                // Slices past the end are ones that just left the chart and are shrinking away.
                val slice = shown.getOrNull(index)?.first
                val isSelected = selected == index
                if (slice != null && index >= slices.size) {
                    DefaultSlice(color = categoryColorFor(slice.name, slice.isOther), antiAlias = true, gap = 2.5f)
                } else if (slice != null) {
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

private val SliceMotion = tween<Float>(durationMillis = 450, easing = FastOutSlowInEasing)

/**
 * The slices with amounts that glide to each new value, so the donut moves smoothly while payments are being
 * categorized instead of jumping. Categories that disappear shrink away after the live ones.
 */
@Composable
private fun rememberAnimatedSlices(slices: List<SpendingChartLogic.Slice>): List<Pair<SpendingChartLogic.Slice, Float>> {
    val firstNames = remember { slices.map { it.name }.toSet() }
    val amounts = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val leaving = remember { mutableStateListOf<SpendingChartLogic.Slice>() }
    val previous = remember { arrayOf<List<SpendingChartLogic.Slice>>(emptyList()) }
    // Present on the first frame: shown at full size. Added later: grows in from nothing.
    fun startValue(slice: SpendingChartLogic.Slice) =
        if (slice.name in firstNames) slice.amountMinor.toFloat() else 1f
    LaunchedEffect(slices) {
        val names = slices.map { it.name }.toSet()
        leaving.removeAll { it.name in names }
        previous[0].filter { old -> old.name !in names && leaving.none { it.name == old.name } }.forEach(leaving::add)
        previous[0] = slices
        // Created here, not during composition: an Animatable is remembered state, and a new slice
        // would otherwise build a fresh one on every recomposition.
        for (slice in slices) amounts.getOrPut(slice.name) { Animatable(startValue(slice)) }
        coroutineScope {
            for (slice in slices) launch { amounts[slice.name]?.animateTo(slice.amountMinor.toFloat(), SliceMotion) }
            for (gone in leaving.toList()) launch {
                amounts[gone.name]?.animateTo(1f, SliceMotion)
                leaving.remove(gone)
                amounts.remove(gone.name)
            }
        }
    }
    return (slices + leaving).map { it to (amounts[it.name]?.value ?: startValue(it)).coerceAtLeast(1f) }
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
