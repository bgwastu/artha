package net.wastu.artha.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.YearMonth
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.ui.ArthaViewModel
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
            sorted.map { Triple(it.category, it.amountMinor, setOf(it.category)) }
        } else {
            val kept = sorted.take(7)
            val restCategories = sorted.drop(7)
            val rest = restCategories.sumOf { it.amountMinor }
            if (rest > 0) {
                kept.map { Triple(it.category, it.amountMinor, setOf(it.category)) } +
                    Triple("Others", rest, restCategories.map { it.category }.toSet())
            } else {
                kept.map { Triple(it.category, it.amountMinor, setOf(it.category)) }
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
private fun sliceColor(index: Int, isOther: Boolean = false) = Color(
    if (isOther) {
        0xFF9CA3AF
    } else {
        when (index % 8) {
            0 -> 0xFF34D399
            1 -> 0xFF60A5FA
            2 -> 0xFFFBBF24
            3 -> 0xFFF87171
            4 -> 0xFFA78BFA
            5 -> 0xFFF472B6
            6 -> 0xFF22D3EE
            else -> 0xFF9CA3AF
        }
    }
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpendingChartScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
    onOpenCategory: (SpendingChartLogic.Slice) -> Unit = { slice ->
        viewModel.openCategoryTransactions(slice.name, slice.categories)
        onBack()
    },
) {
    val analysis by viewModel.monthAnalysis.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val canGoNext = selectedMonth < YearMonth.now(AccountingTime.clock)
    val slices = remember(analysis) { SpendingChartLogic.categorySlices(analysis) }
    var selected by rememberSaveable(analysis.month) { mutableStateOf<Int?>(null) }
    val total = slices.sumOf { it.amountMinor }.coerceAtLeast(1)
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (slices.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No confirmed spending this month",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
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
                    onOpenCategory = onOpenCategory,
                    total = total,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(280.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(slices.size) { index ->
                        val slice = slices[index]
                        val isSelected = selected == index
                        val color = sliceColor(index, slice.isOther)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { selected = if (isSelected) null else index },
                                    onLongClick = { onOpenCategory(slice) },
                                    onClickLabel = "Select category",
                                    onLongClickLabel = "Filter transactions",
                                )
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(color, CircleShape),
                            )
                            Icon(categoryIconFor(slice.name, snapshot.categoryIcons), contentDescription = null)
                            Text(
                                slice.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${slice.percent}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatMoney(net.wastu.artha.core.model.Money(slice.amountMinor)),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One slice the donut draws; pixel math lives with the canvas. */
private data class DonutSlice(
    val name: String,
    val amountMinor: Long,
    val percent: Int,
    val color: Color,
)

/**
 * Hand-drawn donut with exact geometry: the ring stroke is centered between inner and outer radii,
 * so slices and gaps are pixel-perfect. Selected slice explodes outward smoothly while other slices dim,
 * and the center hole displays category breakdown on selection or total when idle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DonutChart(
    slices: List<SpendingChartLogic.Slice>,
    icons: Map<String, String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onOpenCategory: (SpendingChartLogic.Slice) -> Unit,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val items = remember(slices) {
        slices.mapIndexed { index, slice ->
            DonutSlice(slice.name, slice.amountMinor, slice.percent, sliceColor(index, slice.isOther))
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
    val currentSelection by rememberUpdatedState(selected)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(items, total) {
                    detectTapGestures(
                        onTap = { tap ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val maxRadius = min(size.width, size.height) / 2f
                            val outerRadius = maxRadius * 0.82f
                            val ringThickness = 28.dp.toPx()
                            val innerRadius = outerRadius - ringThickness

                            val dx = tap.x - center.x
                            val dy = tap.y - center.y
                            val radius = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            val selection = currentSelection

                            if (radius < innerRadius) {
                                // Tapping the hole clears the selection.
                                if (selection != null) onSelect(null)
                            } else if (radius <= outerRadius + 16.dp.toPx()) {
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
                        },
                        onLongPress = { tap ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val maxRadius = min(size.width, size.height) / 2f
                            val outerRadius = maxRadius * 0.82f
                            val ringThickness = 28.dp.toPx()
                            val innerRadius = outerRadius - ringThickness

                            val dx = tap.x - center.x
                            val dy = tap.y - center.y
                            val radius = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                            if (radius in (innerRadius - 8.dp.toPx())..(outerRadius + 16.dp.toPx())) {
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
                                slices.getOrNull(picked)?.let(onOpenCategory)
                            }
                        },
                    )
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = min(size.width, size.height) / 2f
            val outerRadius = maxRadius * 0.82f
            val ringThickness = 28.dp.toPx()
            val innerRadius = outerRadius - ringThickness
            val strokeCenterRadius = (outerRadius + innerRadius) / 2f

            val gapDegrees = if (items.size > 1) 2.5f else 0f
            var startDegrees = -90f

            items.forEachIndexed { index, slice ->
                val rawSweep = slice.amountMinor * 360f / total
                val sweep = (rawSweep - gapDegrees).coerceAtLeast(0.5f)
                val push = explode[index] * 8.dp.toPx()
                val mid = startDegrees + rawSweep / 2f
                val dir = Offset(
                    cos(Math.toRadians(mid.toDouble())).toFloat(),
                    sin(Math.toRadians(mid.toDouble())).toFloat(),
                )
                val offsetCenter = center + dir * push
                val arcRect = Rect(center = offsetCenter, radius = strokeCenterRadius)

                drawArc(
                    color = slice.color.copy(alpha = alpha[index]),
                    startAngle = startDegrees + gapDegrees / 2f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcRect.topLeft,
                    size = arcRect.size,
                    style = Stroke(width = ringThickness, cap = StrokeCap.Butt),
                )
                startDegrees += rawSweep
            }
        }

        val picked = selected?.let { items.getOrNull(it) }
        Crossfade(targetState = picked, label = "hole") { state ->
            Column(
                Modifier
                    .widthIn(max = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (state != null) {
                            Modifier.combinedClickable(
                                onClick = {
                                    val sel = selected
                                    if (sel != null) slices.getOrNull(sel)?.let(onOpenCategory)
                                },
                                onLongClick = {
                                    val sel = selected
                                    if (sel != null) slices.getOrNull(sel)?.let(onOpenCategory)
                                },
                                onClickLabel = "Filter transactions",
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state != null) {
                    Icon(
                        categoryIconFor(state.name, icons),
                        contentDescription = null,
                        tint = state.color,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        state.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        "${state.percent}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        formatMoney(net.wastu.artha.core.model.Money(state.amountMinor)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        "Total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(net.wastu.artha.core.model.Money(total)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
