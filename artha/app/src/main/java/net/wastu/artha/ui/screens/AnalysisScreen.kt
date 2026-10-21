package net.wastu.artha.ui.screens

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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItemDefaults
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
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AnalysisScreen(
    viewModel: ArthaViewModel,
    onOpenTransaction: (net.wastu.artha.core.model.UnifiedTransaction) -> Unit = {},
    showCategoriesSheet: Boolean = false,
    onOpenCategoriesSheet: () -> Unit = {},
    onDismissCategoriesSheet: () -> Unit = {},
    showKnowledgeEditor: Boolean = false,
    onOpenKnowledgeEditor: () -> Unit = {},
    onDismissKnowledgeEditor: () -> Unit = {},
) {
    val analysis by viewModel.monthAnalysis.collectAsStateWithLifecycle()
    val ledgerState by viewModel.ledgerState.collectAsStateWithLifecycle()
    val savedTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val categoryAutomation by viewModel.categoryAutomation.collectAsStateWithLifecycle()
    val analysisStages by viewModel.analysisStages.collectAsStateWithLifecycle()
    val analyzingMonths by viewModel.analyzingMonths.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val analysisErrors by viewModel.analysisErrors.collectAsStateWithLifecycle()
    val isMonthAnalyzing = selectedMonth in analyzingMonths
    val analysisEnabled = snapshot.analysisEnabled

    var showMonthlyNoteDialog by remember { mutableStateOf(false) }
    var showScopeDialog by remember { mutableStateOf(false) }

    if (!analysisEnabled) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Analysis is turned off", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enable it in Settings to see categories and monthly insights.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
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
            TextButton(onClick = { viewModel.setTargetDestination("TRANSACTIONS") }) { Text("Open transactions") }
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
                monthlyNote = snapshot.monthlyNotes[selectedMonth.toString()],
                onEditNote = { showMonthlyNoteDialog = true },
                onOpenCategories = onOpenCategoriesSheet, onOpenScope = { showScopeDialog = true },
                onOpenLargestPayment = largest?.let { { onOpenTransaction(it) } },
                onPreviousMonth = { viewModel.setAnalysisMonth(selectedMonth.minusMonths(1)) },
                onNextMonth = { viewModel.setAnalysisMonth(selectedMonth.plusMonths(1)) },
            )
        }
        item(key = "category_header") {
            Text("Categories", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }.padding(top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold)
        }
        if (analysis.categories.isEmpty()) item(key = "empty_categories") {
            Text("No confirmed spending this month", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else items(analysis.categories, key = { it.category }) { category ->
            CategorySpendingRow(category, snapshot.categoryIcons, viewModel::openCategoryTransactions)
        }
        item(key = "ai_insights_bottom") {
            AiInsightsCard(
                summary = snapshot.monthlySummaries[net.wastu.artha.data.analysis.analysisKey(selectedMonth)], stage = analysisStages[selectedMonth], isAnalyzing = isMonthAnalyzing, readOnly = viewModel.analysisStore == null,
                onReanalyze = { viewModel.analyzeMonth(selectedMonth, forceReanalyze = true) },
                error = analysisErrors[selectedMonth], savedAt = snapshot.updatedAt["monthSummary:${net.wastu.artha.data.analysis.analysisKey(selectedMonth)}"],
                configured = snapshot.aiConfig.baseUrl.isNotBlank() && snapshot.aiConfig.model.isNotBlank() && snapshot.aiConfig.apiKey.isNotBlank(),
                onConfigure = { viewModel.setTargetDestination("SETTINGS") }, onOpenKnowledge = onOpenKnowledgeEditor,
            )
        }
    }
    if (showScopeDialog) AlertDialog(
        onDismissRequest = { showScopeDialog = false }, title = { Text("Spending total") },
        text = { Text("Paid expenses in rupiah, counted once across saved sources. Transfers, wallet top-ups and refunded charges are excluded. History may be incomplete.") },
        confirmButton = { TextButton(onClick = { showScopeDialog = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { showScopeDialog = false; viewModel.setTargetDestination("SETTINGS") }) { Text("Sources") } },
    )

    if (showMonthlyNoteDialog) {
        val currentNote = snapshot.monthlyNotes[selectedMonth.toString()].orEmpty()
        val monthLabel = remember(selectedMonth) {
            selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
        }
        MonthlyNoteDialog(
            monthLabel = monthLabel,
            initialNote = currentNote,
            onDismiss = { showMonthlyNoteDialog = false },
            onSave = { updated ->
                viewModel.setMonthlyNote(selectedMonth.toString(), updated)
                showMonthlyNoteDialog = false
            },
        )
    }

    if (showKnowledgeEditor) {
        KnowledgeEditorDialog(
            initialKnowledge = snapshot.knowledge,
            onDismiss = onDismissKnowledgeEditor,
            onSave = { updated ->
                viewModel.applyKnowledgeRewrite(updated)
                onDismissKnowledgeEditor()
            },
        )
    }

    if (showCategoriesSheet) {
        ManageCategoriesSheet(
            categories = snapshot.customCategories,
            categoryIcons = snapshot.categoryIcons,
            onDismiss = onDismissCategoriesSheet,
            onAdd = { name, complete -> viewModel.addCustomCategory(name, complete) },
            onRemove = { viewModel.removeCustomCategory(it) },
            onSetIcon = { cat, iconKey -> viewModel.setCategoryIcon(cat, iconKey) },
            onReset = { viewModel.resetCategoriesToDefault() },
            automationState = categoryAutomation,
            onRetryAi = viewModel::retryAutomaticCategories,
        )
    }
}

@Composable
private fun MonthSummaryCard(
    month: YearMonth, analysis: MonthSpendingAnalysis, monthlyNote: String?, editable: Boolean = true,
    onEditNote: () -> Unit, onOpenCategories: () -> Unit, onOpenScope: () -> Unit,
    onOpenLargestPayment: (() -> Unit)?,
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
                    onOpenLargestPayment?.let { open ->
                        DropdownMenuItem(text = { Text("Largest payment") }, onClick = { showMenu = false; open() })
                    }
                    DropdownMenuItem(enabled = editable, text = { Text("Monthly note") }, onClick = { showMenu = false; onEditNote() })
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
                    Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (!monthlyNote.isNullOrBlank()) Text(monthlyNote, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Edit monthly note", onClick = onEditNote).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun MonthlyNoteDialog(
    monthLabel: String,
    initialNote: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(initialNote) { mutableStateOf(initialNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note for $monthLabel", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Add context for commentary. Your chosen categories stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Month note") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onSave(text) }) {
                Text("Save context")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiInsightsCard(
    summary: String?,
    stage: net.wastu.artha.data.analysis.AnalysisStage?, isAnalyzing: Boolean, readOnly: Boolean = false, onReanalyze: () -> Unit,
    onOpenKnowledge: () -> Unit, error: String?, savedAt: Long?,
    configured: Boolean, onConfigure: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(readOnly && !summary.isNullOrBlank()) }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("AI analysis") },
            leadingContent = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            supportingContent = if (isAnalyzing || error != null) ({
                Text(if (isAnalyzing) stage?.label ?: "Preparing analysis" else "Analysis unavailable",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }) else null,
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isAnalyzing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable { expanded = !expanded }.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        )
        if (expanded) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!summary.isNullOrBlank()) net.wastu.artha.ui.AnalysisListContent(summary)
            else Text(if (configured) "Analyze spending and find practical next steps." else "Set up AI for analysis and recommendations.", style = MaterialTheme.typography.bodySmall)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!readOnly) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(onClick = if (configured) onReanalyze else onConfigure, enabled = !isAnalyzing) {
                    Text(if (isAnalyzing) "Analyzing" else if (configured) "Analyze" else "Set up AI")
                }
                TextButton(onClick = onOpenKnowledge) { Text("Personal context") }
            }
            if (savedAt != null) Text("Saved ${formatDate(java.time.Instant.ofEpochMilli(savedAt))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySpendingRow(category: CategorySpending, categoryIcons: Map<String, String>, onOpenCategory: (String) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackAmount = fontScale > 1.3f || maxWidth < 280.dp
        ListItem(
            headlineContent = { Text(category.category) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (stackAmount) Text(formatAmountMinor(category.amountMinor), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${category.sharePercent.toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Text("${category.transactions.size} ${if (category.transactions.size == 1) "payment" else "payments"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            leadingContent = { Icon(categoryIconFor(category.category, categoryIcons), null) },
            trailingContent = if (stackAmount) null else ({ Text(formatAmountMinor(category.amountMinor), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable(onClickLabel = "Open category payments") { onOpenCategory(category.category) },
        )
    }
}

@Composable
fun KnowledgeEditorDialog(
    initialKnowledge: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(initialKnowledge) { mutableStateOf(initialKnowledge) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Personal context", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Context for AI commentary. Your chosen categories stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Personal context") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onSave(text) }) {
                Text("Save context")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManageCategoriesSheet(
    categories: List<String>, categoryIcons: Map<String, String>, onDismiss: () -> Unit,
    onAdd: (String, (Result<Unit>) -> Unit) -> Unit, onRemove: (String) -> Unit,
    onSetIcon: (String, String?) -> Unit, onReset: () -> Unit,
    automationState: CategoryAutomationState? = null, onRetryAi: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight()) {
            ManageCategoriesContent(
                categories, categoryIcons, onDismiss, onAdd, onRemove, onSetIcon, onReset,
                automationState, onRetryAi, expandToSheetHeight = true, showCloseButton = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManageCategoriesContent(
    categories: List<String>, categoryIcons: Map<String, String>, onDismiss: () -> Unit,
    onAdd: (String, (Result<Unit>) -> Unit) -> Unit, onRemove: (String) -> Unit,
    onSetIcon: (String, String?) -> Unit, onReset: () -> Unit,
    automationState: CategoryAutomationState? = null, onRetryAi: () -> Unit = {},
    expandToSheetHeight: Boolean = false,
    showCloseButton: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var addError by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var rowMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var editingIcon by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var revealCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val visibleCategories = remember(categories, query) {
        categories.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    var previousQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (previousQuery != query) {
            previousQuery = query
            listState.scrollToItem(0)
        }
    }
    val duplicate = categories.any { it.equals(name.trim(), ignoreCase = true) }
    fun submit() {
        if (saving) return
        val value = name.trim()
        if (value.isEmpty()) { addError = "Enter a category name"; return }
        if (duplicate) { addError = "Already in picker"; return }
        saving = true
        addError = null
        onAdd(value) { result ->
            saving = false
            if (result.isSuccess) {
                focus.clearFocus()
                query = ""
                revealCategory = value
                name = ""
                adding = false
            } else addError = "Couldn't add category. Try again."
        }
    }
    LaunchedEffect(revealCategory, visibleCategories, adding) {
        val index = revealCategory?.let(visibleCategories::indexOf) ?: -1
        if (!adding && index >= 0) {
            listState.scrollToItem(index)
            revealCategory = null
        }
    }
    BackHandler(enabled = adding) {
        if (!saving) { focus.clearFocus(); adding = false }
    }
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (adding) IconButton(onClick = { focus.clearFocus(); adding = false }, enabled = !saving) {
                Icon(Icons.Default.ChevronLeft, "Back to categories")
            }
            Text(if (adding) "Add category" else "Categories", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (!adding && showCloseButton) {
                Box {
                    IconButton(onClick = { headerMenu = true }) { Icon(Icons.Default.MoreVert, "Category options") }
                    DropdownMenu(headerMenu, onDismissRequest = { headerMenu = false }) {
                        DropdownMenuItem(text = { Text("Reset picker") }, onClick = { headerMenu = false; reset = true })
                    }
                }
                IconButton(onClick = { focus.clearFocus(); onDismiss() }) { Icon(Icons.Default.Close, "Close categories") }
            } else if (!adding) {
                Box {
                    IconButton(onClick = { headerMenu = true }) { Icon(Icons.Default.MoreVert, "Category options") }
                    DropdownMenu(headerMenu, onDismissRequest = { headerMenu = false }) {
                        DropdownMenuItem(text = { Text("Reset picker") }, onClick = { headerMenu = false; reset = true })
                    }
                }
            }
        }
        if (adding) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                OutlinedTextField(name, { name = it; addError = null }, label = { Text("Category name") },
                    enabled = !saving, isError = duplicate || addError != null,
                    supportingText = (if (duplicate) "Already in picker" else addError)?.let { message -> {
                        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    } },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { focus.clearFocus(); adding = false }, enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                FilledTonalButton(onClick = { submit() }, enabled = !saving && name.isNotBlank() && !duplicate,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (saving) "Adding" else "Add") }
            }
        } else {
            automationState?.let { state ->
                Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(when (state) {
                        CategoryAutomationState.NOT_CONFIGURED -> "AI not configured"
                        CategoryAutomationState.WORKING -> "AI categorizing"
                        CategoryAutomationState.READY -> "AI automatic"
                        CategoryAutomationState.ERROR -> "AI unavailable"
                    }, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == CategoryAutomationState.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state == CategoryAutomationState.ERROR) {
                        TextButton(onClick = { focus.clearFocus(); onRetryAi() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Retry")
                        }
                    }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search categories") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear category search") }
                }) else null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            TextButton(onClick = { focus.clearFocus(); adding = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add category")
            }
            val listModifier = if (expandToSheetHeight) {
                Modifier.fillMaxWidth().weight(1f, fill = false)
            } else {
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
            }
            LazyColumn(listModifier, state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (visibleCategories.isEmpty()) item {
                    Text(if (query.isBlank()) "No categories" else "No matching categories",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
                items(visibleCategories, key = { it }) { category ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(categoryIconFor(category, categoryIcons), null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Text(category, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Box {
                            IconButton(onClick = { focus.clearFocus(); rowMenu = category }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.MoreVert, "Options for $category")
                            }
                            DropdownMenu(rowMenu == category, onDismissRequest = { rowMenu = null }) {
                                DropdownMenuItem(text = { Text("Change icon") }, onClick = { rowMenu = null; editingIcon = category })
                                DropdownMenuItem(text = { Text("Remove from picker") }, onClick = { rowMenu = null; removing = category })
                            }
                        }
                    }
                }
            }
        }
    }
    editingIcon?.let { category ->
        IconPickerDialog(categoryIcons[category], onDismiss = { editingIcon = null }, onSelect = {
            onSetIcon(category, it)
            editingIcon = null
        })
    }
    removing?.let { category ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove from picker?") },
            text = { Text("Saved payments keep their categories and notes.") },
            confirmButton = { TextButton(onClick = { onRemove(category); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset picker?") },
        text = { Text("Restore default categories and icons. Saved payment categories and notes stay.") },
        confirmButton = { TextButton(onClick = { onReset(); reset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
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
