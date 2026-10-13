package net.wastu.artha.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lightbulb
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.AVAILABLE_CATEGORY_ICONS
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.CategorySpending
import net.wastu.artha.ui.MarkdownText
import net.wastu.artha.ui.MonthSpendingAnalysis
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMoney
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AnalysisScreen(
    viewModel: ArthaViewModel,
    onTransactionClick: (UnifiedTransaction) -> Unit,
    showCategoriesSheet: Boolean = false,
    onOpenCategoriesSheet: () -> Unit = {},
    onDismissCategoriesSheet: () -> Unit = {},
    showKnowledgeEditor: Boolean = false,
    onOpenKnowledgeEditor: () -> Unit = {},
    onDismissKnowledgeEditor: () -> Unit = {},
) {
    val analysis by viewModel.monthAnalysis.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val analyzingMonths by viewModel.analyzingMonths.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val isMonthAnalyzing = selectedMonth in analyzingMonths

    var showMonthlyNoteDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Month Switcher & Spending Total Card + Monthly User Note
        item(key = "header_summary") {
            MonthSummaryCard(
                month = selectedMonth,
                analysis = analysis,
                monthlyNote = snapshot.monthlyNotes[selectedMonth.toString()],
                onEditNote = { showMonthlyNoteDialog = true },
                onPreviousMonth = { viewModel.setAnalysisMonth(selectedMonth.minusMonths(1)) },
                onNextMonth = { viewModel.setAnalysisMonth(selectedMonth.plusMonths(1)) },
            )
        }

        // 2. Compact background analysis indicator (shown only while analyzing)
        if (isMonthAnalyzing) {
            item(key = "top_analyzing_banner") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Analyzing month with AI in background...",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // 3. Category Breakdown Header
        item(key = "category_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Spending by Category",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onOpenCategoriesSheet() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(12.dp))
                        Text(
                            "${snapshot.customCategories.size} categories",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (analysis.categories.isEmpty()) {
            item(key = "empty_categories") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No spending recorded for this month",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(
                analysis.categories,
                key = { it.category },
            ) { cat ->
                CategorySpendingCard(
                    category = cat,
                    categoryIcons = snapshot.categoryIcons,
                    onTransactionClick = onTransactionClick,
                )
            }
        }

        // 4. Compact AI Summary + Date-Aware Recommendations at the bottom
        item(key = "ai_insights_bottom") {
            AiInsightsCard(
                summary = analysis.monthlyAiSummary,
                model = snapshot.aiConfig.model,
                isAnalyzing = isMonthAnalyzing,
                onReanalyze = { viewModel.analyzeMonth(selectedMonth, forceReanalyze = true) },
                onRecategorizeAll = { viewModel.recategorizeAll() },
                onOpenKnowledge = onOpenKnowledgeEditor,
            )
        }
    }

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
    month: YearMonth,
    analysis: MonthSpendingAnalysis,
    monthlyNote: String?,
    onEditNote: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val monthTitle = remember(month) {
        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
    }
    val canGoNext = remember(month) { month < YearMonth.now() }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(month, canGoNext) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount -> dragOffset += dragAmount },
                    onDragEnd = {
                        if (dragOffset < -60f && canGoNext) {
                            onNextMonth()
                        } else if (dragOffset > 60f) {
                            onPreviousMonth()
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Month switcher row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPreviousMonth, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Month")
                }
                Text(
                    monthTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (canGoNext) {
                    IconButton(onClick = onNextMonth, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Month")
                    }
                } else {
                    Spacer(Modifier.size(32.dp))
                }
            }

            // Total spending display + subtle delta badge inline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatAmountMinor(analysis.totalPersonalSpendingMinor),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val delta = analysis.totalDeltaPercent
                val prevTotal = analysis.previousMonthTotalMinor
                if (delta != null && prevTotal > 0L) {
                    val symbol = if (delta < 0) "▼" else "▲"
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "$symbol ${kotlin.math.abs(delta).toInt()}% vs last month",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Monthly User Note banner
            Surface(
                onClick = onEditNote,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.EditNote,
                        contentDescription = "Edit Monthly Note",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (monthlyNote.isNullOrBlank()) "Add monthly note (explain this month's spending)..." else monthlyNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (monthlyNote.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
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
                    "Explain what you spent on this month (e.g. trips, big purchases, home repairs). AI uses this context to categorize transactions and provide advice.",
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
                Text("Save & Analyze")
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
    summary: String?,
    model: String,
    isAnalyzing: Boolean,
    onReanalyze: () -> Unit,
    onRecategorizeAll: () -> Unit,
    onOpenKnowledge: () -> Unit,
) {
    var showRecategorizeConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "AI Monthly Summary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        model.substringAfter("/").take(16),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            val (summaryPart, recommendationsPart) = remember(summary) {
                val raw = summary ?: ""
                if ("RECOMMENDATIONS:" in raw) {
                    val parts = raw.split("RECOMMENDATIONS:", limit = 2)
                    parts[0].trim() to parts[1].trim()
                } else {
                    raw.trim() to ""
                }
            }

            if (summaryPart.isNotBlank()) {
                MarkdownText(
                    markdown = summaryPart,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    bulletColor = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "AI analyzes expenses automatically in the background based on your Knowledge rules and Monthly Note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (recommendationsPart.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Recommendations",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                MarkdownText(
                    markdown = recommendationsPart,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    bulletColor = MaterialTheme.colorScheme.tertiary,
                )
            }

            // Action Buttons: Re-analyze and Recategorize All
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onReanalyze,
                    enabled = !isAnalyzing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Analyzing...", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Re-analyze", style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedButton(
                    onClick = { showRecategorizeConfirm = true },
                    enabled = !isAnalyzing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Recategorize", style = MaterialTheme.typography.labelMedium)
                }
            }

            OutlinedButton(
                onClick = onOpenKnowledge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Knowledge Base Rules")
            }
        }
    }

    if (showRecategorizeConfirm) {
        AlertDialog(
            onDismissRequest = { showRecategorizeConfirm = false },
            title = { Text("Recategorize All?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This will reset all transaction categories (including manual overrides) and re-classify expenses with AI based on your Knowledge rules and Monthly Note.")
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    onRecategorizeAll()
                    showRecategorizeConfirm = false
                }) {
                    Text("Reset & Recategorize")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecategorizeConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun CategorySpendingCard(
    category: CategorySpending,
    categoryIcons: Map<String, String>,
    onTransactionClick: (UnifiedTransaction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // Line 1: Category Name with Icon + Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    val icon = categoryIconFor(category.category, categoryIcons)
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        category.category,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    formatAmountMinor(category.amountMinor),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Line 2: Share and tx count + Subtle Delta
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${category.sharePercent.toInt()}% (${category.transactions.size} tx)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val delta = category.deltaPercent
                if (delta != null && category.previousMonthAmountMinor > 0L) {
                    val symbol = if (delta < 0) "▼" else "▲"
                    Text(
                        "$symbol ${kotlin.math.abs(delta).toInt()}% vs last month",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }

            // Line 3: Thin 3dp progress bar
            LinearProgressIndicator(
                progress = { (category.sharePercent / 100f).coerceIn(0.01f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // Expandable list of transactions
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (tx in category.transactions) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().clickable { onTransactionClick(tx) },
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Image(
                                    painter = painterResource(tx.provider.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        tx.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        formatDate(tx.occurredAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    formatMoney(tx.money),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
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
        title = { Text("Knowledge Base Rules", fontWeight = FontWeight.Bold) },
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
