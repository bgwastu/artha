package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
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
import net.wastu.artha.core.model.Money
import net.wastu.artha.ui.AVAILABLE_CATEGORY_ICONS
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.data.analysis.CategorySpending
import net.wastu.artha.ui.MarkdownText
import net.wastu.artha.data.analysis.MonthSpendingAnalysis
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMoney
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
    val analyzingMonths by viewModel.analyzingMonths.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val analysisErrors by viewModel.analysisErrors.collectAsStateWithLifecycle()
    val isMonthAnalyzing = selectedMonth in analyzingMonths

    var showMonthlyNoteDialog by remember { mutableStateOf(false) }
    var showScopeDialog by remember { mutableStateOf(false) }

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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header_summary") {
            MonthSummaryCard(
                month = selectedMonth, analysis = analysis,
                monthlyNote = snapshot.monthlyNotes[selectedMonth.toString()],
                onEditNote = { showMonthlyNoteDialog = true },
                onOpenCategories = onOpenCategoriesSheet, onOpenScope = { showScopeDialog = true },
                onPreviousMonth = { viewModel.setAnalysisMonth(selectedMonth.minusMonths(1)) },
                onNextMonth = { viewModel.setAnalysisMonth(selectedMonth.plusMonths(1)) },
            )
        }
        if (largest != null) item(key = "largest_payment") {
            val stackAmount = LocalDensity.current.fontScale > 1.3f
            ListItem(
                headlineContent = { Text("Largest payment", style = MaterialTheme.typography.labelMedium) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(largest.description, maxLines = if (stackAmount) 2 else 1, overflow = TextOverflow.Ellipsis)
                        if (stackAmount) Text(formatAmountMinor(largest.money.amountMinor), fontWeight = FontWeight.SemiBold)
                    }
                },
                trailingContent = {
                    if (!stackAmount) Text(formatAmountMinor(largest.money.amountMinor), fontWeight = FontWeight.SemiBold)
                    else Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable { onOpenTransaction(largest) },
            )
        }
        item(key = "category_header") {
            Text("Categories", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold)
        }
        if (analysis.categories.isEmpty()) item(key = "empty_categories") {
            Text("No confirmed spending this month", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else items(analysis.categories, key = { it.category }) { category ->
            CategorySpendingRow(category, snapshot.categoryIcons, viewModel::openCategoryTransactions)
        }
        item(key = "ai_insights_bottom") {
            AiInsightsCard(
                summary = analysis.monthlyAiSummary, isAnalyzing = isMonthAnalyzing,
                onReanalyze = { viewModel.analyzeMonth(selectedMonth, forceReanalyze = true) },
                error = analysisErrors[selectedMonth], savedAt = snapshot.updatedAt["monthSummary:$selectedMonth"],
                configured = snapshot.aiConfig.baseUrl.isNotBlank() && snapshot.aiConfig.model.isNotBlank() && snapshot.aiConfig.apiKey.isNotBlank(),
                onConfigure = { viewModel.setTargetDestination("SETTINGS") }, onOpenKnowledge = onOpenKnowledgeEditor,
            )
        }
    }
    if (showScopeDialog) AlertDialog(
        onDismissRequest = { showScopeDialog = false }, title = { Text("Spending total") },
        text = { Text("Paid expenses in rupiah, counted once across saved sources. Transfers, wallet top-ups and refunded charges are excluded. History may be incomplete.") },
        confirmButton = { TextButton(onClick = { showScopeDialog = false }) { Text("Close") } },
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
            onAdd = { viewModel.addCustomCategory(it) },
            onRemove = { viewModel.removeCustomCategory(it) },
            onSetIcon = { cat, iconKey -> viewModel.setCategoryIcon(cat, iconKey) },
            onReset = { viewModel.resetCategoriesToDefault() },
        )
    }
}

@Composable
private fun MonthSummaryCard(
    month: YearMonth, analysis: MonthSpendingAnalysis, monthlyNote: String?,
    onEditNote: () -> Unit, onOpenCategories: () -> Unit, onOpenScope: () -> Unit,
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
                    DropdownMenuItem(text = { Text("Monthly note") }, onClick = { showMenu = false; onEditNote() })
                    DropdownMenuItem(text = { Text("Manage categories") }, onClick = { showMenu = false; onOpenCategories() })
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
                    fontWeight = FontWeight.Bold)
                if (!canGoNext && analysis.previousMonthTotalMinor > 0) {
                    Text("Previous full month ${formatAmountMinor(analysis.previousMonthTotalMinor)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (canGoNext) analysis.totalDeltaPercent?.let { delta ->
                    Text("${kotlin.math.abs(delta).toInt()}% ${if (delta < 0) "less" else "more"} than previous month",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (analysis.unsupportedCurrencyCount > 0) Text("${analysis.unsupportedCurrencyCount} non-IDR records excluded",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!monthlyNote.isNullOrBlank()) Text(monthlyNote, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onEditNote).padding(vertical = 12.dp),
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
    var text by remember { mutableStateOf(initialNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note for $monthLabel", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Explain what you spent on this month (e.g. trips, big purchases, home repairs). AI uses this context to explain spending and provide commentary; your categories are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g., Vacation to Jogja; bought laptop on Tokopedia...") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
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

@Composable
private fun AiInsightsCard(
    summary: String?, isAnalyzing: Boolean, onReanalyze: () -> Unit,
    onOpenKnowledge: () -> Unit, error: String?, savedAt: Long?,
    configured: Boolean, onConfigure: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("AI commentary") },
            supportingContent = if (isAnalyzing || error != null) ({
                Text(if (isAnalyzing) "Updating" else "Refresh unavailable", style = MaterialTheme.typography.bodySmall)
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
            if (savedAt != null) Text("Saved ${formatDate(java.time.Instant.ofEpochMilli(savedAt))}", style = MaterialTheme.typography.labelSmall)
            if (!summary.isNullOrBlank()) MarkdownText(markdown = summary, style = MaterialTheme.typography.bodyMedium)
            else Text(if (configured) "A summary of saved spending." else "Connect a provider for spending commentary.", style = MaterialTheme.typography.bodySmall)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = if (configured) onReanalyze else onConfigure, enabled = !isAnalyzing) {
                Text(if (isAnalyzing) "Refreshing" else if (configured) "Refresh" else "Set up AI")
            }
            TextButton(onClick = onOpenKnowledge) { Text("Personal context") }
        }
    }
}

@Composable
private fun CategorySpendingRow(category: CategorySpending, categoryIcons: Map<String, String>, onOpenCategory: (String) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackAmount = fontScale > 1.3f || maxWidth < 280.dp
        ListItem(
            headlineContent = { Text(category.category) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (stackAmount) Text(formatAmountMinor(category.amountMinor), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${category.sharePercent.toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Text("${category.transactions.size} payments", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            leadingContent = { Icon(categoryIconFor(category.category, categoryIcons), null) },
            trailingContent = if (stackAmount) null else ({ Text(formatAmountMinor(category.amountMinor), fontWeight = FontWeight.SemiBold) }),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable { onOpenCategory(category.category) },
        )
    }
}

@Composable
fun KnowledgeEditorDialog(
    initialKnowledge: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialKnowledge) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Personal Context Rules", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Simple markdown bullet list governing personal relationships, accounting heuristics, and categorization rules for AI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onSave(text) }) {
                Text("Save Knowledge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCategoriesSheet(
    categories: List<String>,
    categoryIcons: Map<String, String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetIcon: (category: String, iconKey: String?) -> Unit,
    onReset: () -> Unit,
) {
    var newCategoryText by remember { mutableStateOf("") }
    var selectedNewIconKey by remember { mutableStateOf<String?>("default") }
    var categoryToEditIcon by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column {
                        Text(
                            "Categories",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${categories.size} categories active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                TextButton(
                    onClick = { showResetConfirm = true },
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
            }

            // Add new category surface
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "ADD NEW CATEGORY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Tappable Icon button to pick an icon for the new category
                        Surface(
                            onClick = { categoryToEditIcon = "__NEW__" },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val currentVector = categoryIconFor("", mapOf("" to (selectedNewIconKey ?: "default")))
                                Icon(
                                    currentVector,
                                    contentDescription = "Pick Icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }

                        OutlinedTextField(
                            value = newCategoryText,
                            onValueChange = { newCategoryText = it },
                            placeholder = { Text("Category name...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val trimmed = newCategoryText.trim()
                                    if (trimmed.isNotBlank()) {
                                        onAdd(trimmed)
                                        if (selectedNewIconKey != null && selectedNewIconKey != "default") {
                                            onSetIcon(trimmed, selectedNewIconKey)
                                        }
                                        newCategoryText = ""
                                        selectedNewIconKey = "default"
                                    }
                                }
                            ),
                        )

                        FilledTonalButton(
                            onClick = {
                                val trimmed = newCategoryText.trim()
                                if (trimmed.isNotBlank()) {
                                    onAdd(trimmed)
                                    if (selectedNewIconKey != null && selectedNewIconKey != "default") {
                                        onSetIcon(trimmed, selectedNewIconKey)
                                    }
                                    newCategoryText = ""
                                    selectedNewIconKey = "default"
                                }
                            },
                            enabled = newCategoryText.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(50.dp),
                        ) {
                            Text("Add")
                        }
                    }
                }
            }

            // Categories list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(categories, key = { it }) { cat ->
                    val icon = categoryIconFor(cat, categoryIcons)
                    val hasCustomIcon = categoryIcons.containsKey(cat)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                // Tappable icon: click to customize the icon for this category!
                                Surface(
                                    onClick = { categoryToEditIcon = cat },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (hasCustomIcon) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            icon,
                                            contentDescription = "Customize Icon",
                                            tint = if (hasCustomIcon) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        cat,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (hasCustomIcon) {
                                        Text(
                                            "Custom icon",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { onRemove(cat) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }

    // Icon Picker Dialog when user taps an icon to customize
    categoryToEditIcon?.let { targetCat ->
        IconPickerDialog(
            currentKey = if (targetCat == "__NEW__") selectedNewIconKey else categoryIcons[targetCat],
            onDismiss = { categoryToEditIcon = null },
            onSelect = { chosenKey ->
                if (targetCat == "__NEW__") {
                    selectedNewIconKey = chosenKey
                } else {
                    onSetIcon(targetCat, chosenKey)
                }
                categoryToEditIcon = null
            },
        )
    }

    // Reset confirmation alert
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Categories?") },
            text = { Text("This will restore default categories and clear custom icon assignments.") },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    showResetConfirm = false
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun IconPickerDialog(
    currentKey: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Category Icon", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Select a custom icon or keep the default icon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(AVAILABLE_CATEGORY_ICONS, key = { it.first }) { (key, vector) ->
                            val isSelected = (currentKey ?: "default") == key
                            Surface(
                                onClick = { onSelect(key) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        vector,
                                        contentDescription = key,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect("default") }) {
                Text("Use Default")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatAmountMinor(amountMinor: Long): String =
    if (amountMinor <= 0L) "Rp0" else formatMoney(Money(amountMinor))
