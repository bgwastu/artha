package net.wastu.artha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.PieValueFormatter
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatMoney

/** Month/day spending aggregated for the chart screen, testable independently of Vico. */
object SpendingChartLogic {
    /** One entry per day of the month up to today for the current month, spending minor total. */
    fun dailyTotals(month: YearMonth, analysis: MonthSpendingAnalysis, today: java.time.LocalDate): List<Long> {
        val last = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
        val totals = LongArray(last)
        analysis.categories.forEach { category ->
            category.transactions.forEach { tx ->
                if (BudgetAnalyzer.isSpending(tx)) {
                    val day = tx.occurredAt.atZone(AccountingTime.zone).toLocalDate()
                    if (YearMonth.from(day) == month && day.dayOfMonth <= last) {
                        totals[day.dayOfMonth - 1] += tx.money.amountMinor
                    }
                }
            }
        }
        return totals.toList()
    }

    /** Top six categories by spend, with the rest merged into "Others". */
    fun categorySlices(analysis: MonthSpendingAnalysis): List<Pair<String, Long>> {
        val sorted = analysis.categories.sortedByDescending { it.amountMinor }
        val kept = sorted.take(6)
        val rest = sorted.drop(6).sumOf { it.amountMinor }
        return if (rest > 0) kept.map { it.category to it.amountMinor } + ("Others" to rest)
        else kept.map { it.category to it.amountMinor }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingChartScreen(viewModel: ArthaViewModel, onBack: () -> Unit) {
    val analysis by viewModel.monthAnalysis.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val today = remember { java.time.LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone)) }
    val canGoNext = selectedMonth < YearMonth.now(AccountingTime.clock)
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

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
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selectedMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("Daily") },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("Categories") },
                )
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val empty = analysis.categories.isEmpty()
                when {
                    empty -> EmptyChart()
                    page == 1 -> CategoryPieSection(analysis, snapshot.categoryIcons)
                    else -> DailyChartContent(selectedMonth, analysis, today)
                }
            }
        }
    }
}

@Composable
private fun DailyChartContent(month: YearMonth, analysis: MonthSpendingAnalysis, today: java.time.LocalDate) {
    val daily = remember(month, analysis, today) { SpendingChartLogic.dailyTotals(month, analysis, today) }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(daily) {
        producer.runTransaction {
            columnModel { series(daily) }
        }
    }
    val colors = MaterialTheme.colorScheme
    val indicatorComponent = rememberShapeComponent(
        fill = Fill(colors.primary),
        shape = androidx.compose.foundation.shape.CircleShape,
    )
    val marker = rememberDefaultCartesianMarker(
        label = rememberTextComponent(
            androidx.compose.ui.text.TextStyle(color = colors.onSurface, fontSize = 12.sp),
            padding = Insets(all = 8.dp),
            margins = Insets(all = 4.dp),
            background = rememberShapeComponent(
                fill = Fill(colors.surfaceContainerHigh),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ),
        ),
        valueFormatter = { _, targets ->
            val target = targets.first() as com.patrykandpatrick.vico.compose.cartesian.marker.ColumnCartesianLayerMarkerTarget
            formatMoney(net.wastu.artha.core.model.Money(target.columns.first().entry.y.toLong()))
        },
        indicator = { indicatorComponent },
        indicatorSize = 12.dp,
        guideline = com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent(),
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(valueFormatter = compactRupiahFormatter()),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = dayFormatter(month),
                    itemPlacer = remember {
                        com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis.ItemPlacer.aligned(
                            spacing = { 4 },
                        )
                    },
                ),
                marker = marker,
                markerController = com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController.rememberToggleOnTap(),
            ),
            modelProducer = producer,
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CategoryPieSection(analysis: MonthSpendingAnalysis, categoryIcons: Map<String, String>) {
    val slices = remember(analysis) { SpendingChartLogic.categorySlices(analysis) }
    val producer = remember { PieChartModelProducer() }
    val values = slices.map { (_, amount) -> amount.toDouble() }
    LaunchedEffect(values) {
        producer.runTransaction { pieSeries { series(values) } }
    }
    val total = slices.sumOf { it.second }.coerceAtLeast(1)
    var selected by remember(analysis) { mutableStateOf<Int?>(null) }
    val themeColors = MaterialTheme.colorScheme
    val sliceColors = listOf(
        themeColors.primary,
        themeColors.tertiary,
        themeColors.secondary,
        themeColors.primaryContainer,
        themeColors.tertiaryContainer,
        themeColors.secondaryContainer,
        themeColors.outline,
    )
    val theme = rememberM3VicoTheme().copy(
        pieChartColors = sliceColors,
        textColor = themeColors.onSurface,
    )
    val percentLabel = rememberTextComponent(
        androidx.compose.ui.text.TextStyle(color = themeColors.onSurface, fontSize = 11.sp),
        margins = Insets(horizontal = 4.dp),
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            ProvideVicoTheme(theme) {
                PieChartHost(
                    chart = rememberPieChart(
                        sliceProvider = PieChart.SliceProvider.series(sliceColors.mapIndexed { index, color ->
                            PieChart.Slice(
                                fill = Fill(color),
                                offsetFromCenter = if (selected == index) 8.dp else 0.dp,
                                label = PieChart.SliceLabel.Outside(
                                    textComponent = percentLabel,
                                    lineColor = themeColors.onSurfaceVariant,
                                ),
                            )
                        }),
                        innerSize = com.patrykandpatrick.vico.compose.pie.PieSize.Inner.fixed(120.dp),
                        spacing = 4.dp,
                        valueFormatter = PieValueFormatter { _, value, _ ->
                            "${(value / values.sum().coerceAtLeast(1.0) * 100).toInt()}%"
                        },
                    ),
                    modelProducer = producer,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            }
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 140.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val picked = selected?.let { slices.getOrNull(it) }
                if (picked != null) {
                    Text(
                        picked.first,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1,
                    )
                    Text(
                        formatMoney(net.wastu.artha.core.model.Money(picked.second)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        "Total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(net.wastu.artha.core.model.Money(total)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            slices.forEachIndexed { index, (name, amount) ->
                val rowSelected = selected == index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (rowSelected) null else index
                        }
                        .background(
                            if (rowSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surface,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    androidx.compose.material3.Icon(
                        categoryIconFor(name, categoryIcons),
                        contentDescription = null,
                        tint = if (rowSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        name, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (rowSelected) FontWeight.SemiBold else null,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${amount * 100 / total}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(formatMoney(net.wastu.artha.core.model.Money(amount)), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun EmptyChart() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No confirmed spending this month", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun compactRupiahFormatter(): CartesianValueFormatter = CartesianValueFormatter { _, value, _ ->
    val million = value / 1_000_000.0
    when {
        kotlin.math.abs(value) >= 1_000_000 -> "Rp${String.format(Locale.US, "%.1f", million).replace('.', ',')}jt"
        kotlin.math.abs(value) >= 1_000 -> "Rp${String.format(Locale.US, "%.0f", value / 1_000)}rb"
        else -> "Rp${NumberFormat.getIntegerInstance(Locale.US).format(value)}"
    }
}

private fun dayFormatter(month: YearMonth): CartesianValueFormatter = CartesianValueFormatter { _, x, _ ->
    (x.toInt().coerceIn(1, month.lengthOfMonth())).toString()
}
