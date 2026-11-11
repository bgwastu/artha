package net.wastu.artha.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryAttention
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.categoryAttention
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.ui.AVAILABLE_CATEGORY_ICONS
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.LedgerState
import net.wastu.artha.ui.categoryColorFor
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.categoryIconLabel
import net.wastu.artha.ui.components.ConnectedChoiceRow
import net.wastu.artha.ui.components.ShapeBadge
import net.wastu.artha.ui.formatMoney

@Composable
fun AnalysisScreen(
    viewModel: ArthaViewModel,
    onOpenTransaction: (UnifiedTransaction) -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onOpenCategoryReview: () -> Unit = {},
    onOpenAiCategories: () -> Unit = {},
    onOpenChart: () -> Unit = {},
) {
    val analysis by viewModel.monthAnalysis.collectAsStateWithLifecycle()
    val ledgerState by viewModel.ledgerState.collectAsStateWithLifecycle()
    val savedTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val categoryAutomation by viewModel.categoryAutomation.collectAsStateWithLifecycle()
    val categoryProgress by viewModel.categoryProgress.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()

    var showRecategorizeMonth by remember { mutableStateOf(false) }
    val monthPendingScope = remember(selectedMonth) { RecategorizeScope.MonthPending(selectedMonth) }
    val pendingCount by produceState(0, savedTransactions, snapshot, selectedMonth) {
        value = withContext(Dispatchers.Default) { viewModel.recategorizeCount(monthPendingScope) }
    }
    val attention by produceState(CategoryAttention(), savedTransactions, snapshot, selectedMonth) {
        value = withContext(Dispatchers.Default) { categoryAttention(savedTransactions, snapshot, selectedMonth) }
    }
    val aiConfigured = snapshot.aiConfig.let { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() && it.model.isNotBlank() }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }

    if (ledgerState is LedgerState.Loading && savedTransactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(64.dp).semantics { contentDescription = "Loading saved spending" })
        }
        return
    }
    if (ledgerState is LedgerState.Failed && savedTransactions.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Unable to load saved spending")
            TextButton(onClick = { viewModel.setTargetDestination("TRANSACTIONS") }) { Text("Open transactions") }
        }
        return
    }
    val working = categoryProgress.state == CategoryAutomationState.WORKING
    val slices = remember(analysis) { SpendingChartLogic.categorySlices(analysis) }
    var selectedSliceIndex by rememberSaveable(analysis.month, analysis.type) { mutableStateOf<Int?>(null) }
    val total = slices.sumOf { it.amountMinor }.coerceAtLeast(1)
    val canGoNext = selectedMonth < YearMonth.now(AccountingTime.clock)
    // Pages run Expenses, Income, then Expenses of the next month, and so on. Swipe left for the next page, right for the previous.
    val swipeType = Modifier.swipeBetween(
        onLeft = {
            if (analysis.type == TransactionType.EXPENSE) viewModel.setAnalysisType(TransactionType.INCOME)
            else if (canGoNext) {
                viewModel.setAnalysisMonth(selectedMonth.plusMonths(1))
                viewModel.setAnalysisType(TransactionType.EXPENSE)
            }
        },
        onRight = {
            if (analysis.type == TransactionType.INCOME) viewModel.setAnalysisType(TransactionType.EXPENSE)
            else {
                viewModel.setAnalysisMonth(selectedMonth.minusMonths(1))
                viewModel.setAnalysisType(TransactionType.INCOME)
            }
        },
    )

    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "month_header") {
            MonthHeader(
                month = selectedMonth,
                editable = viewModel.analysisStore != null,
                onOpenCategories = onOpenCategories,
                onOpenReview = onOpenCategoryReview,
                onPreviousMonth = { viewModel.setAnalysisMonth(selectedMonth.minusMonths(1)) },
                onNextMonth = { viewModel.setAnalysisMonth(selectedMonth.plusMonths(1)) },
            )
        }
        item(key = "type_toggle") {
            ConnectedChoiceRow(
                options = listOf(TransactionType.EXPENSE, TransactionType.INCOME),
                selected = analysis.type,
                onSelect = viewModel::setAnalysisType,
                label = { if (it == TransactionType.INCOME) "Income" else "Expenses" },
                modifier = Modifier.padding(vertical = 4.dp).then(swipeType),
            )
        }
        item(key = "chart") {
            // A new type slides in from the side it was swiped from. Same-type updates (categorizing) don't re-slide.
            AnimatedContent(
                targetState = ChartPage(analysis.month, analysis.type, slices, total, analysis.totalDeltaPercent),
                contentKey = { it.month to it.type },
                transitionSpec = {
                    val forward = targetState.position > initialState.position
                    (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it else it } + fadeOut())
                },
                modifier = Modifier.fillMaxWidth().then(swipeType),
                label = "analysis_type",
            ) { page ->
                if (page.slices.isEmpty()) {
                    EmptySpendingCard(page.type, canGoNext)
                } else {
                    DonutChart(
                        slices = page.slices,
                        icons = snapshot.categoryIcons,
                        selected = selectedSliceIndex,
                        onSelect = { index -> selectedSliceIndex = if (selectedSliceIndex == index) null else index },
                        onOpenCategory = { slice ->
                            viewModel.openCategoryTransactions(slice.name, slice.categories)
                        },
                        total = page.total,
                        totalDeltaPercent = page.deltaPercent,
                        canGoNext = canGoNext,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .height(300.dp),
                    )
                }
            }
        }
        item(key = "ai_section") {
            AiCategorizationSection(
                progress = categoryProgress,
                attention = attention,
                actionLabel = when {
                    working -> "Stop"
                    !aiConfigured -> "Set up AI"
                    pendingCount > 0 -> "Categorize $pendingCount"
                    else -> "Re-categorize"
                },
                onAction = {
                    when {
                        working -> viewModel.stopCategorization()
                        !aiConfigured -> onOpenAiCategories()
                        pendingCount > 0 -> viewModel.recategorize(monthPendingScope)
                        else -> showRecategorizeMonth = true
                    }
                },
                onOpenReview = onOpenCategoryReview,
            )
        }
        item(key = "category_header") {
            Text(
                text = "Categories",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
            )
        }
        if (slices.isEmpty()) {
            item(key = "empty_categories") { EmptyCategories(analysis.type == TransactionType.INCOME) }
        } else {
            items(slices.size, key = { slices[it].name }) { index ->
                val slice = slices[index]
                val isSelected = selectedSliceIndex == index
                val color = categoryColorFor(slice.name, slice.isOther)
                CategorySliceRow(
                    slice = slice,
                    color = color,
                    categoryIcons = snapshot.categoryIcons,
                    isSelected = isSelected,
                    onSelect = {
                        if (selectedSliceIndex == index) {
                            viewModel.openCategoryTransactions(slice.name, slice.categories)
                        } else {
                            selectedSliceIndex = index
                        }
                    },
                    onOpenCategory = {
                        viewModel.openCategoryTransactions(slice.name, slice.categories)
                    },
                )
            }
        }
    }
    if (showRecategorizeMonth) {
        val monthScope = RecategorizeScope.Month(selectedMonth)
        val label = selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
        recategorizeConfirmation(monthScope, viewModel.recategorizeCount(monthScope), label)?.let { text ->
            AlertDialog(
                onDismissRequest = { showRecategorizeMonth = false },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { showRecategorizeMonth = false; viewModel.recategorize(monthScope) }) { Text("Re-categorize") } },
                dismissButton = { TextButton(onClick = { showRecategorizeMonth = false }) { Text("Cancel") } },
            )
        }
    }
}

private data class ChartPage(
    val month: YearMonth,
    val type: TransactionType,
    val slices: List<SpendingChartLogic.Slice>,
    val total: Long,
    val deltaPercent: Float?,
) {
    /** Where this page sits in the swipe order: Expenses then Income, month after month. */
    val position: Int get() = (month.year * 12 + month.monthValue) * 2 + if (type == TransactionType.INCOME) 1 else 0
}

/** A horizontal swipe of more than 60 px calls [onLeft] or [onRight]; taps and vertical scrolling are untouched. */
private fun Modifier.swipeBetween(onLeft: () -> Unit, onRight: () -> Unit): Modifier = pointerInput(onLeft, onRight) {
    var drag = 0f
    detectHorizontalDragGestures(
        onHorizontalDrag = { _, amount -> drag += amount },
        onDragEnd = {
            if (drag < -60f) onLeft() else if (drag > 60f) onRight()
            drag = 0f
        },
        onDragCancel = { drag = 0f },
    )
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    editable: Boolean = true,
    onOpenCategories: () -> Unit,
    onOpenReview: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val monthTitle = remember(month) { month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)) }
    val canGoNext = month < YearMonth.now(AccountingTime.clock)
    var showMenu by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(month, canGoNext) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> dragOffset += amount },
                    onDragEnd = {
                        if (dragOffset < -60f && canGoNext) onNextMonth() else if (dragOffset > 60f) onPreviousMonth()
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousMonth) { Icon(Icons.Default.ChevronLeft, "Previous month") }
        Text(monthTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        IconButton(onClick = onNextMonth, enabled = canGoNext) { Icon(Icons.Default.ChevronRight, "Next month") }
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Month options") }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(enabled = editable, text = { Text("Review month") }, onClick = { showMenu = false; onOpenReview() })
                DropdownMenuItem(enabled = editable, text = { Text("Manage categories") }, onClick = { showMenu = false; onOpenCategories() })
            }
        }
    }
}

internal object AiCardLogic {
    fun categoryProgressFraction(labeled: Int, total: Int): Float =
        if (total > 0) (labeled.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategorySliceRow(
    slice: SpendingChartLogic.Slice,
    color: Color,
    categoryIcons: Map<String, String>,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpenCategory: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onOpenCategory,
                onClickLabel = "Select category",
                onLongClickLabel = "View expenses",
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(categoryIconFor(slice.name, categoryIcons), contentDescription = null, tint = color)
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
            formatMoney(Money(slice.amountMinor)),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun IconPickerDialog(currentKey: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Category icon") },
        text = {
            LazyVerticalGrid(columns = GridCells.Adaptive(56.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 360.dp).selectableGroup(),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AVAILABLE_CATEGORY_ICONS, key = { it.first }) { (key, vector) ->
                    val selected = (currentKey ?: "default") == key
                    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = { onSelect(key) })) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(vector, categoryIconLabel(key), modifier = Modifier.size(24.dp),
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect("default") }) { Text("Use default") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}


@Composable
private fun EmptySpendingCard(type: TransactionType, canGoNext: Boolean) {
    val income = type == TransactionType.INCOME
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    income -> if (canGoNext) "Income" else "Income to date"
                    else -> if (canGoNext) "Spending" else "Spending to date"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Text("Rp0", style = MaterialTheme.typography.headlineMediumEmphasized)
            Text(
                if (income) "No income this month" else "No confirmed spending this month",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiCategorizationSection(
    progress: CategoryProgress,
    attention: CategoryAttention,
    actionLabel: String,
    onAction: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val working = progress.state == CategoryAutomationState.WORKING
    val problem = progress.error.takeIf { progress.state == CategoryAutomationState.ERROR } ?: progress.issue
    val showReview = !working && attention.total > 0
    val failed = showReview && attention.failed > 0
    val status = problem ?: when {
        working -> categoryProgressLabel(progress)
        showReview -> buildList {
            if (attention.review > 0) add("${attention.review} to review")
            if (attention.failed > 0) add("${attention.failed} failed")
        }.joinToString(" · ")
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(
                text = "AI categorization",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (problem != null || failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (working) {
            val fraction = if (progress.runTotal > 0) AiCardLogic.categoryProgressFraction(progress.runDone, progress.runTotal)
                else AiCardLogic.categoryProgressFraction(progress.labeled, progress.total)
            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showReview) {
                Button(onClick = onOpenReview) {
                    Icon(Icons.Outlined.RateReview, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Review")
                }
            }
            OutlinedButton(onClick = onAction) {
                Icon(if (working) Icons.Default.Stop else Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun EmptyCategories(income: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShapeBadge(
            icon = Icons.Default.PieChart,
            polygon = MaterialShapes.Cookie12Sided,
            size = 80.dp,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (income) "No income to break down" else "Nothing to break down yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (income) "Categories appear here once income for this month is confirmed."
            else "Categories appear here once this month's spending is categorized.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
