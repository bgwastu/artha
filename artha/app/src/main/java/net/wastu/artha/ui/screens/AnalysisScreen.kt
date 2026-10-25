package net.wastu.artha.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Category
import androidx.compose.ui.graphics.Color
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.recategorizeLabel
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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

    var showScopeDialog by remember { mutableStateOf(false) }
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
    if (ledgerState is net.wastu.artha.ui.LedgerState.Loading || analysis.month != selectedMonth) {
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
    val largest = remember(analysis.categories) { analysis.categories.flatMap { it.transactions }.maxByOrNull { it.money.amountMinor } }
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header_summary") {
            MonthSummaryCard(
                month = selectedMonth, analysis = analysis, editable = viewModel.analysisStore != null,
                onOpenCategories = onOpenCategories, onOpenScope = { showScopeDialog = true },
                onOpenChart = onOpenChart, onOpenAi = onOpenAiCategories,
                onOpenLargestPayment = largest?.let { { onOpenTransaction(it) } },
                onPreviousMonth = { viewModel.setAnalysisMonth(selectedMonth.minusMonths(1)) },
                onNextMonth = { viewModel.setAnalysisMonth(selectedMonth.plusMonths(1)) },
            )
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
                    // Badge on the icon inside the button, so it sits within the button's bounds.
                    IconButton(onClick = onOpenChart, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.DonutLarge, contentDescription = "Spending chart")
                    }
                    val working = categoryProgress.state == CategoryAutomationState.WORKING
                    AssistChip(
                        onClick = {
                            when {
                                working -> viewModel.stopCategorization()
                                !aiConfigured -> onOpenAiCategories()
                                pendingCount > 0 -> viewModel.recategorize(monthPendingScope)
                                else -> showRecategorizeMonth = true
                            }
                        },
                        label = { Text(if (working) "Stop" else if (pendingCount > 0) "Categorize $pendingCount" else "Categorize") },
                        leadingIcon = {
                            if (working) Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            else BadgedBox(badge = { if (attention.total > 0) Badge() }) {
                                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                            }
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
        if (analysis.categories.isEmpty()) item(key = "empty_categories") {
            Text("No confirmed spending this month", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else items(analysis.categories, key = { it.category }) { category ->
            CategorySpendingRow(category, snapshot.categoryIcons, selectedMonth, viewModel::openCategoryTransactions, viewModel::recategorize, viewModel::recategorizeCount)
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
    if (showScopeDialog) AlertDialog(
        onDismissRequest = { showScopeDialog = false }, title = { Text("Spending total") },
        text = { Text("Paid expenses in rupiah, counted once across saved sources. Transfers, wallet top-ups and refunded charges are excluded. History may be incomplete.") },
        confirmButton = { TextButton(onClick = { showScopeDialog = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { showScopeDialog = false; viewModel.setTargetDestination("SETTINGS") }) { Text("Sources") } },
    )
}

@Composable
private fun MonthSummaryCard(
    month: YearMonth, analysis: MonthSpendingAnalysis, editable: Boolean = true,
    onOpenCategories: () -> Unit, onOpenScope: () -> Unit,
    onOpenChart: () -> Unit, onOpenAi: () -> Unit, onOpenLargestPayment: (() -> Unit)?,
    onPreviousMonth: () -> Unit, onNextMonth: () -> Unit,
) {
    val monthTitle = remember(month) { month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)) }
    val canGoNext = month < YearMonth.now(net.wastu.artha.core.model.AccountingTime.clock)
    var showMenu by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().pointerInput(month, canGoNext) {
        detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> dragOffset += amount },
            onDragEnd = {
                if (dragOffset < -60f && canGoNext) onNextMonth() else if (dragOffset > 60f) onPreviousMonth()
                dragOffset = 0f
            }, onDragCancel = { dragOffset = 0f })
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPreviousMonth) { Icon(Icons.Default.ChevronLeft, "Previous month") }
            Text(monthTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = onNextMonth, enabled = canGoNext) { Icon(Icons.Default.ChevronRight, "Next month") }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Month options") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Chart") }, onClick = { showMenu = false; onOpenChart() })
                    onOpenLargestPayment?.let { open ->
                        DropdownMenuItem(text = { Text("Largest payment") }, onClick = { showMenu = false; open() })
                    }
                    DropdownMenuItem(text = { Text("AI categorization") }, onClick = { showMenu = false; onOpenAi() })
                    DropdownMenuItem(enabled = editable, text = { Text("Manage categories") }, onClick = { showMenu = false; onOpenCategories() })
                    DropdownMenuItem(text = { Text("Spending scope") }, onClick = { showMenu = false; onOpenScope() })
                }
            }
        }
        Surface(onClick = onOpenScope, color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Spending total. What counts in spending" }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (canGoNext) "Spending" else "Spending to date", style = MaterialTheme.typography.labelMedium)
                }
                Text(formatAmountMinor(analysis.totalPersonalSpendingMinor), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (!canGoNext && analysis.previousMonthTotalMinor > 0) {
                    Text("Previous full month ${formatAmountMinor(analysis.previousMonthTotalMinor)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (canGoNext) analysis.totalDeltaPercent?.let { delta ->
                    Text("${kotlin.math.abs(delta).toInt()}% ${if (delta < 0) "less" else "more"} than previous month",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal object AiCardLogic {
    fun categoryProgressFraction(labeled: Int, total: Int): Float =
        if (total > 0) (labeled.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CategorySpendingRow(
    category: CategorySpending,
    categoryIcons: Map<String, String>,
    selectedMonth: YearMonth,
    onOpenCategory: (String) -> Unit,
    onRecategorize: (RecategorizeScope) -> Unit,
    onRecategorizeCount: (RecategorizeScope) -> Int = { 0 },
) {
    val fontScale = LocalDensity.current.fontScale
    var showMenu by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    val scope = remember(category.category, selectedMonth) {
        RecategorizeScope.CategoryInMonth(category.category, selectedMonth)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackAmount = fontScale > 1.3f || maxWidth < 280.dp
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onOpenCategory(category.category) },
                        onLongClick = { showMenu = true },
                        onClickLabel = "Open category payments",
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = if (stackAmount) Alignment.Top else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(categoryIconFor(category.category, categoryIcons), null,
                    Modifier.padding(top = if (stackAmount) 2.dp else 0.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(category.category)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${category.sharePercent.toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Text("${category.transactions.size} ${if (category.transactions.size == 1) "payment" else "payments"}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (stackAmount) Text(formatAmountMinor(category.amountMinor), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
                if (!stackAmount) {
                    Text(formatAmountMinor(category.amountMinor), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(recategorizeLabel(scope)) },
                    onClick = {
                        showMenu = false
                        showConfirmDialog = true
                    },
                )
            }
        }
    }
    if (showConfirmDialog) {
        val count = onRecategorizeCount(scope)
        val confirmText = recategorizeConfirmation(scope, count)
        if (confirmText != null) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                text = { Text(confirmText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirmDialog = false
                            onRecategorize(scope)
                        },
                    ) {
                        Text("Re-categorize")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
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
