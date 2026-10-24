package net.wastu.artha.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.YearMonth
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatMoney

/** Month spending aggregated for the chart screen, testable independently of drawing. */
object SpendingChartLogic {
    /** Top six categories by spend, with the rest merged into "Others". */
    fun categorySlices(analysis: MonthSpendingAnalysis): List<Pair<String, Long>> {
        val sorted = analysis.categories.sortedByDescending { it.amountMinor }
        val kept = sorted.take(6)
        val rest = sorted.drop(6).sumOf { it.amountMinor }
        return if (rest > 0) kept.map { it.category to it.amountMinor } + ("Others" to rest)
        else kept.map { it.category to it.amountMinor }
    }
}

/**
 * Categorical palette: distinct hues for at-a-glance slice identification, kept mid-tone so it
 * reads on both surfaces. A deliberate M3 departure — the tonal color roles are too close
 * in hue to tell chart slices apart.
 */
private val slicePalette = listOf(
    0xFF34D399, 0xFF60A5FA, 0xFFFBBF24, 0xFFF87171, 0xFFA78BFA, 0xFFF472B6, 0xFF22D3EE,
).map { Color(it) } + listOf(0xFF9CA3AF).map { Color(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingChartScreen(viewModel: ArthaViewModel, onBack: () -> Unit) {
    val analysis by viewModel.monthAnalysis.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val canGoNext = selectedMonth < YearMonth.now(AccountingTime.clock)
    val slices = remember(analysis) { SpendingChartLogic.categorySlices(analysis) }
    var selected by remember(analysis) { mutableStateOf<Int?>(null) }
    val total = slices.sumOf { it.second }.coerceAtLeast(1)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Chart") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (slices.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No confirmed spending this month", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { viewModel.setAnalysisMonth(selectedMonth.minusMonths(1)) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    IconButton(onClick = { viewModel.setAnalysisMonth(selectedMonth.plusMonths(1)) }, enabled = canGoNext) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                }
                DonutChart(
                    slices = slices,
                    icons = snapshot.categoryIcons,
                    selected = selected,
                    onSelect = { index -> selected = if (selected == index) null else index },
                    total = total,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(280.dp),
                )
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    slices.forEachIndexed { index, (name, amount) ->
                        val isSelected = selected == index
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (isSelected) null else index }
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(slicePalette[index % slicePalette.size], androidx.compose.foundation.shape.CircleShape),
                            )
                            androidx.compose.material3.Icon(categoryIconFor(name, snapshot.categoryIcons), contentDescription = null)
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                formatMoney(net.wastu.artha.core.model.Money(amount)),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One slice the donut draws; pixel math lives with the canvas. */
private data class DonutSlice(val name: String, val amountMinor: Long, val color: Color)

/**
 * Hand-drawn donut so slice taps are exact: tap angle maps to the slice, the selected slice
 * animates out from the center, and the hole names what it shows. Icons appear on slices wide
 * enough to hold them.
 */
@Composable
private fun DonutChart(
    slices: List<Pair<String, Long>>,
    icons: Map<String, String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val items = remember(slices) {
        slices.mapIndexed { index, (name, amount) ->
            DonutSlice(name, amount, slicePalette[index % slicePalette.size])
        }
    }
    val explode = items.mapIndexed { index, _ ->
        animateFloatAsState(
            targetValue = if (selected == index) 1f else 0f,
            animationSpec = tween(250),
            label = "explode$index",
        ).value
    }
    val selection = selected
    val surfaceColor = MaterialTheme.colorScheme.surface

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(items) {
                        detectTapGestures { tap ->
                            val size = this.size
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val dx = tap.x - center.x
                            val dy = tap.y - center.y
                            val radius = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            val maxRadius = min(size.width, size.height) / 2f
                            if (radius < maxRadius * 0.58f) {
                                // Tapping the hole clears the selection.
                                onSelect(null)
                            } else if (radius <= maxRadius) {
                                var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                                degrees = ((degrees + 90) + 360) % 360
                                var running = 0.0
                                var picked = -1
                                items.forEachIndexed { index, item ->
                                    val sweep = item.amountMinor.toDouble() / total * 360.0
                                    if (picked < 0 && degrees < running + sweep) picked = index
                                    running += sweep
                                }
                                if (picked < 0) picked = items.lastIndex
                                if (picked == selection) onSelect(null) else onSelect(picked)
                            }
                        }
                    },
            ) {
                val strokeWidthWhitespace = 2.dp.toPx()
                val outerRadius = min(size.width, size.height) / 2f - 4f
                val holeRadius = outerRadius * 0.58f
                val center = Offset(size.width / 2f, size.height / 2f)
                var startDegrees = -90f
                items.forEachIndexed { index, slice ->
                    val sweep = slice.amountMinor * 360f / total
                    val push = explode[index] * 10.dp.toPx()
                    val mid = startDegrees + sweep / 2f
                    val dir = Offset(
                        cos(Math.toRadians(mid.toDouble())).toFloat(),
                        sin(Math.toRadians(mid.toDouble())).toFloat(),
                    )
                    val offsetCenter = center + dir * push
                    val arcRect = Rect(
                        center = offsetCenter,
                        radius = outerRadius,
                    )
                    drawArc(
                        color = slice.color,
                        startAngle = startDegrees,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = arcRect.topLeft,
                        size = arcRect.size,
                        style = Stroke(width = outerRadius - holeRadius, cap = StrokeCap.Butt),
                    )
                    startDegrees += sweep
                }
                // The hole drawn on top so exploded slices never overflow into it.
                drawCircle(color = surfaceColor, radius = holeRadius - strokeWidthWhitespace / 2f, center = center)
            }
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 140.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val picked = selected?.let { items.getOrNull(it) }
                if (picked != null) {
                    androidx.compose.material3.Icon(
                        categoryIconFor(picked.name, icons),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        picked.name,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1,
                    )
                    Text(
                        formatMoney(net.wastu.artha.core.model.Money(picked.amountMinor)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text("Total", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatMoney(net.wastu.artha.core.model.Money(total)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

