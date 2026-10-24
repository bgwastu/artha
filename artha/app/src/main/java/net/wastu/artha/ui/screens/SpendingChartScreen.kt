package net.wastu.artha.ui.screens

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Categorical palette: distinct hues for at-a-glance slice identification, mid-tones so it
 * reads on both surfaces. A deliberate M3 departure — the tonal roles are too close in hue
 * to tell chart slices apart.
 */

private fun sliceColor(index: Int) = Color(
    when (index % 8) {
        0 -> 0xFF34D399
        1 -> 0xFF60A5FA
        2 -> 0xFFFBBF24
        3 -> 0xFFF87171
        4 -> 0xFFA78BFA
        5 -> 0xFFF472B6
        6 -> 0xFF22D3EE
        else -> 0xFF9CA3AF
    },
)
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
    val listState = rememberLazyListState()

    LaunchedEffect(selected) {
        selected?.let { listState.animateScrollToItem(it) }
    }

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
                .fillMaxSize()
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(300.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(slices.size) { index ->
                        val (name, amount) = slices[index]
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
                                    .background(sliceColor(index), CircleShape),
                            )
                            Icon(categoryIconFor(name, snapshot.categoryIcons), contentDescription = null)
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
 * Hand-drawn donut so slice taps are exact: the ring sits at 0.68 of half-box so outside
 * percent labels never touch it, the selected slice explodes while every other slice dims,
 * and wide slices carry their category icon.
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
            DonutSlice(name, amount, sliceColor(index))
        }
    }
    val explode = items.mapIndexed { index, _ ->
        animateFloatAsState(
            targetValue = if (selected == index) 1f else 0f,
            animationSpec = tween(250),
            label = "explode$index",
        ).value
    }
    val alpha = items.mapIndexed { index, _ ->
        animateFloatAsState(
            targetValue = when (selected) {
                null -> 1f
                index -> 1f
                else -> 0.35f
            },
            animationSpec = tween(150),
            label = "alpha$index",
        ).value
    }
    val colors = MaterialTheme.colorScheme
    val surfaceColor = colors.surface
    val labelTextColor = colors.onSurfaceVariant
    val tickColor = colors.outlineVariant
    val percentPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val labelSp = 12.sp
    val labelPx = with(LocalDensity.current) { labelSp.toPx() }
    val tapGapPx = with(LocalDensity.current) { 12.dp.toPx() }
    // Always-current selection for tap handlers (avoids stale closures).
    val currentSelection by rememberUpdatedState(selected)
    val holeIconSize = 22.dp

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(items, total) {
                        detectTapGestures { tap ->
                            val size = this.size
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val outerRadius = min(size.width, size.height) / 2f * 0.68f
                            val holeRadius = outerRadius * 0.62f
                            val dx = tap.x - center.x
                            val dy = tap.y - center.y
                            val radius = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            val selection = currentSelection
                            if (radius < holeRadius) {
                                // The hole clears the selection.
                                if (selection != null) onSelect(null)
                            } else if (radius <= outerRadius + tapGapPx) {
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
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = min(size.width, size.height) / 2f * 0.68f
                val holeRadius = outerRadius * 0.62f
                val ringWidth = outerRadius - holeRadius
                val gapDegrees = if (items.size > 1) 2.5f else 0f
                var startDegrees = -90f
                items.forEachIndexed { index, slice ->
                    val rawSweep = slice.amountMinor * 360f / total
                    val sweep = (rawSweep - gapDegrees).coerceAtLeast(rawSweep * 0.5f)
                    val push = explode[index] * 10.dp.toPx()
                    val mid = startDegrees + rawSweep / 2f
                    val dir = Offset(
                        cos(Math.toRadians(mid.toDouble())).toFloat(),
                        sin(Math.toRadians(mid.toDouble())).toFloat(),
                    )
                    val offsetCenter = center + dir * push
                    val arcRect = Rect(center = offsetCenter, radius = outerRadius)
                    drawArc(
                        color = slice.color.copy(alpha = alpha[index]),
                        startAngle = startDegrees + gapDegrees / 2f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = arcRect.topLeft,
                        size = arcRect.size,
                        style = Stroke(width = ringWidth, cap = StrokeCap.Butt),
                    )
                    if (rawSweep >= 12f || selected == index) {
                        val percent = "${slice.amountMinor * 100 / total}%"
                        val label = if (selected == index) "${slice.name} $percent" else percent
                        val clearance = outerRadius + 22f.dp.toPx()
                        val anchor = Offset(
                            center.x + dir.x * clearance,
                            center.y + dir.y * clearance,
                        )
                        percentPaint.typeface = android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD,
                        )
                        // Label keeps the slice hue but flips lightness for the theme:
                        // dark ink on a light surface, light ink on a dark one.
                        val hsv = floatArrayOf(0f, 0f, 0f)
                        android.graphics.Color.colorToHSV(slice.color.toArgb(), hsv)
                        hsv[2] = if (colors.surface.luminance() > 0.5f) 0.40f else 0.78f
                        percentPaint.color = Color(android.graphics.Color.HSVToColor(hsv)).toArgb()
                        percentPaint.textSize = labelPx
                        val textWidth = percentPaint.measureText(label)
                        val drawX = (anchor.x - textWidth / 2f)
                            .coerceIn(8f, (size.width - textWidth - 8f).coerceAtLeast(8f))
                        val fm = percentPaint.fontMetrics
                        val baseline = anchor.y - (fm.ascent + fm.descent) / 2f
                        drawContext.canvas.nativeCanvas.drawText(label, drawX, baseline, percentPaint)
                    }
                    startDegrees += rawSweep
                }
                drawCircle(color = surfaceColor, radius = holeRadius, center = center)
            }
            val picked = selected?.let { items.getOrNull(it) }
            Crossfade(targetState = picked, label = "hole", modifier = Modifier.align(Alignment.Center)) { state ->
                Column(
                    Modifier.widthIn(max = 150.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (state != null) {
                        Icon(
                            categoryIconFor(state.name, icons),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            state.name,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, maxLines = 1,
                        )
                        Text(
                            formatMoney(net.wastu.artha.core.model.Money(state.amountMinor)),
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
}
