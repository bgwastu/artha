package net.wastu.artha.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Category
import androidx.compose.ui.graphics.Color
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.recategorizeLabel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import net.wastu.artha.ui.categoryIconLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import net.wastu.artha.core.model.Money
import net.wastu.artha.ui.formatMoney
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.ui.AVAILABLE_CATEGORY_ICONS
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategorySpending
import net.wastu.artha.ui.MarkdownText
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import kotlinx.coroutines.delay
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.data.analysis.CategoryAttention
import net.wastu.artha.data.analysis.categoryAttention
import net.wastu.artha.ui.formatDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AnalysisScreen(
    viewModel: ArthaViewModel,
    onOpenTransaction: (net.wastu.artha.core.model.UnifiedTransaction) -> Unit = {},
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
    val analysisEnabled = snapshot.analysisEnabled

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

    if (!analysisEnabled) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Analysis is turned off",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Enable it in Settings to see categories and monthly insights",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextButton(onClick = { viewModel.setTargetDestination("SETTINGS") }, modifier = Modifier.padding(top = 12.dp)) {
                Text("Open Settings")
            }
        }
        return
    }
    if (ledgerState is net.wastu.artha.ui.LedgerState.Loading && savedTransactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading saved spending" })
        }
        return
    }
    if (ledgerState is net.wastu.artha.ui.LedgerState.Failed && savedTransactions.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Unable to load saved spending")
            TextButton(onClick = { viewModel.setTargetDestination("TRANSACTIONS") }) { Text("Open expenses") }
        }
        return
    }
    val working = categoryProgress.state == CategoryAutomationState.WORKING
    val slices = remember(analysis) { SpendingChartLogic.categorySlices(analysis) }
    var selectedSliceIndex by rememberSaveable(analysis.month) { mutableStateOf<Int?>(null) }
    val total = slices.sumOf { it.amountMinor }.coerceAtLeast(1)
    val canGoNext = selectedMonth < YearMonth.now(net.wastu.artha.core.model.AccountingTime.clock)

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
                onPreviousMonth = { viewModel.setAnalysisMonth(selectedMonth.minusMonths(1)) },
                onNextMonth = { viewModel.setAnalysisMonth(selectedMonth.plusMonths(1)) },
            )
        }
        if (slices.isNotEmpty()) {
            item(key = "chart") {
                DonutChart(
                    slices = slices,
                    icons = snapshot.categoryIcons,
                    selected = selectedSliceIndex,
                    onSelect = { index -> selectedSliceIndex = if (selectedSliceIndex == index) null else index },
                    onOpenCategory = { slice ->
                        viewModel.openCategoryTransactions(slice.name, slice.categories)
                    },
                    total = total,
                    totalDeltaPercent = analysis.totalDeltaPercent,
                    canGoNext = canGoNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(300.dp),
                )
            }
        } else {
            item(key = "empty_spending") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (canGoNext) "Spending" else "Spending to date", style = MaterialTheme.typography.labelMedium)
                        Text("Rp0", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("No confirmed spending this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "category_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Categories",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.weight(1f))
                    AssistChip(
                        onClick = {
                            when {
                                working -> viewModel.stopCategorization()
                                !aiConfigured -> onOpenAiCategories()
                                pendingCount > 0 -> viewModel.recategorize(monthPendingScope)
                                else -> showRecategorizeMonth = true
                            }
                        },
                        label = {
                            Text(when {
                                working -> "Stop"
                                !aiConfigured || pendingCount > 0 -> if (pendingCount > 0) "Categorize $pendingCount" else "Categorize"
                                else -> "Re-categorize"
                            })
                        },
                        leadingIcon = {
                            Icon(if (working) Icons.Default.Stop else Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        },
                    )
                }
                if (categoryProgress.state == CategoryAutomationState.WORKING) {
                    val run = categoryProgress.runTotal > 0
                    val fraction = if (run) AiCardLogic.categoryProgressFraction(categoryProgress.runDone, categoryProgress.runTotal)
                        else AiCardLogic.categoryProgressFraction(categoryProgress.labeled, categoryProgress.total)
                    Text(
                        categoryProgressLabel(categoryProgress) ?: "Categorizing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else if (attention.total > 0) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildList {
                                if (attention.review > 0) add("${attention.review} need review")
                                if (attention.failed > 0) add("${attention.failed} failed")
                            }.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (attention.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenCategoryReview) { Text("Review") }
                    }
                }
            }
        }
        if (slices.isEmpty()) {
            item(key = "empty_categories") {
                Text(
                    "No confirmed spending this month",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(slices.size, key = { slices[it].name }) { index ->
                val slice = slices[index]
                val isSelected = selectedSliceIndex == index
                val color = sliceColor(index, slice.isOther)
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

@Composable
private fun MonthHeader(
    month: YearMonth,
    editable: Boolean = true,
    onOpenCategories: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val monthTitle = remember(month) { month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)) }
    val canGoNext = month < YearMonth.now(net.wastu.artha.core.model.AccountingTime.clock)
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
            .clip(RoundedCornerShape(12.dp))
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 280.dp).selectableGroup(),
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

private fun formatAmountMinor(amountMinor: Long): String =
    net.wastu.artha.ui.formatMinorAmount(java.math.BigInteger.valueOf(amountMinor).abs())
