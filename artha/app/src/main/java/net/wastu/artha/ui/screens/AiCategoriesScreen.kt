package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryStateLabel
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.data.analysis.recategorizeLabel
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.transactionHumanLine
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object AiCategoriesLogic {
    fun statusSupportingText(progress: CategoryProgress): String? {
        if (progress.stopped) return "Stopped"
        if (progress.paused) return "Paused"
        return categoryProgressLabel(progress)
    }

    fun reviewLine(progress: CategoryProgress): String? {
        return if (progress.review > 0) "${progress.review} to review" else null
    }

    fun failedLine(progress: CategoryProgress): String? {
        return if (progress.failed > 0) "${progress.failed} couldn't be categorized" else null
    }

    fun headerStatusText(progress: CategoryProgress): String? = when {
        progress.stopped -> "Stopped"
        progress.state == CategoryAutomationState.ERROR -> progress.error ?: "Categorizing stopped"
        else -> categoryProgressLabel(progress)
    }

    fun showStopAction(progress: CategoryProgress): Boolean =
        progress.state == CategoryAutomationState.WORKING

    fun showRetryAction(progress: CategoryProgress): Boolean =
        progress.state != CategoryAutomationState.WORKING &&
            (progress.state == CategoryAutomationState.ERROR || progress.stopped || progress.failed > 0)

    fun showCategorizeNewAction(progress: CategoryProgress, newCount: Int): Boolean =
        progress.state != CategoryAutomationState.WORKING && newCount > 0

    fun rowSourceLabel(source: CategorySource): String? = when (source) {
        CategorySource.MANUAL -> "Set by you"
        CategorySource.AI -> "Suggested by AI"
        CategorySource.MERCHANT -> "Same merchant"
        CategorySource.REVIEW -> "Needs review"
        CategorySource.FAILED -> "Couldn't categorize"
        CategorySource.PENDING -> null
        CategorySource.NONE -> null
    }

    fun paymentRowStateText(state: PaymentCategoryState, source: CategorySource): String? = when (state) {
        PaymentCategoryState.CATEGORIZING -> "Categorizing"
        PaymentCategoryState.QUEUED -> "Queued"
        PaymentCategoryState.IDLE -> rowSourceLabel(source)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiCategoriesScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenTransaction: (UnifiedTransaction) -> Unit,
) {
    val categoryProgress by viewModel.categoryProgress.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()

    val currentMonth = remember { YearMonth.now(AccountingTime.zone) }
    val newScope = remember(currentMonth) { RecategorizeScope.New(currentMonth) }
    val newCount = viewModel.recategorizeCount(newScope)

    val isWorking = categoryProgress.state == CategoryAutomationState.WORKING
    val isError = categoryProgress.state == CategoryAutomationState.ERROR

    var pendingConfirmScope by remember { mutableStateOf<RecategorizeScope?>(null) }

    val monthSpending = remember(allTransactions, selectedMonth) {
        allTransactions
            .filter { tx ->
                BudgetAnalyzer.isSpending(tx) &&
                    YearMonth.from(tx.occurredAt.atZone(AccountingTime.zone)) == selectedMonth
            }
            .sortedByDescending { it.occurredAt }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item(key = "header_card") {
                val statusText = AiCategoriesLogic.headerStatusText(categoryProgress)
                val fraction = if (categoryProgress.total > 0) {
                    (categoryProgress.labeled.toFloat() / categoryProgress.total.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (statusText != null) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (isWorking) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = "${categoryProgress.labeled} of ${categoryProgress.total} payments this month categorized",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        val showStop = AiCategoriesLogic.showStopAction(categoryProgress)
                        val showRetry = AiCategoriesLogic.showRetryAction(categoryProgress)
                        val showCategorizeNew = AiCategoriesLogic.showCategorizeNewAction(categoryProgress, newCount)

                        if (showStop || showRetry || showCategorizeNew) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (showStop) {
                                    OutlinedButton(onClick = { viewModel.stopCategorization() }) {
                                        Text("Stop")
                                    }
                                }
                                if (showRetry) {
                                    FilledTonalButton(onClick = { viewModel.retryAutomaticCategories() }) {
                                        Text("Retry")
                                    }
                                }
                                if (showCategorizeNew) {
                                    Button(onClick = { viewModel.recategorize(newScope) }) {
                                        Text("Categorize new payments")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "auto_categorize_switch") {
                ListItem(
                    headlineContent = { Text("Auto-categorize new payments") },
                    supportingContent = { Text("Only this month's new payments") },
                    trailingContent = {
                        Switch(
                            checked = snapshot.autoCategorize,
                            onCheckedChange = { viewModel.setAutoCategorize(it) },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item(key = "divider_actions") {
                HorizontalDivider()
            }

            item(key = "recategorize_month") {
                val monthScope = remember(selectedMonth) { RecategorizeScope.Month(selectedMonth) }
                val count = viewModel.recategorizeCount(monthScope)
                val enabled = !isWorking && count > 0
                ListItem(
                    headlineContent = {
                        Text(
                            text = recategorizeLabel(monthScope),
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = enabled) {
                        pendingConfirmScope = monthScope
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item(key = "recategorize_uncategorized") {
                val uncategorizedScope = RecategorizeScope.Uncategorized
                val count = viewModel.recategorizeCount(uncategorizedScope)
                val enabled = !isWorking && count > 0
                ListItem(
                    headlineContent = {
                        Text(
                            text = recategorizeLabel(uncategorizedScope),
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = enabled) {
                        pendingConfirmScope = uncategorizedScope
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item(key = "recategorize_all") {
                val allScope = RecategorizeScope.All
                val count = viewModel.recategorizeCount(allScope)
                val enabled = !isWorking && count > 0
                ListItem(
                    headlineContent = {
                        Text(
                            text = recategorizeLabel(allScope),
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = enabled) {
                        pendingConfirmScope = allScope
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item(key = "review_row") {
                val reviewCount = categoryProgress.review
                val reviewEnabled = reviewCount > 0
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Review $reviewCount",
                            color = if (reviewEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = reviewEnabled, onClick = onOpenReview),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            if (categoryProgress.failed > 0) {
                item(key = "failed_row") {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "${categoryProgress.failed} couldn't be categorized",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { viewModel.recategorize(RecategorizeScope.NeedsReview) }) {
                                Text("Retry")
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item(key = "this_month_header") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "This month",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (monthSpending.isEmpty()) {
                item(key = "this_month_empty") {
                    Text(
                        text = "No spending payments this month",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(monthSpending, key = { it.key }) { tx ->
                    val catState = paymentCategoryState(tx.key, categoryProgress)
                    ListItem(
                        headlineContent = {
                            Text(
                                text = transactionHumanLine(tx),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Text(
                                text = formatMoney(tx.money),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            if (catState != PaymentCategoryState.IDLE) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = paymentCategoryStateLabel(catState) ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                val source = categorySource(tx, snapshot)
                                val sourceText = AiCategoriesLogic.rowSourceLabel(source)
                                val categoryName = tx.category ?: "Uncategorized"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = categoryName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (sourceText != null) {
                                        Text(
                                            text = sourceText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (source == CategorySource.FAILED) MaterialTheme.colorScheme.error
                                            else if (source == CategorySource.REVIEW) MaterialTheme.colorScheme.tertiary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpenTransaction(tx) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item(key = "footnote") {
                Text(
                    text = "Your own picks are never changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    pendingConfirmScope?.let { scope ->
        val count = viewModel.recategorizeCount(scope)
        val monthLabel = remember(selectedMonth) {
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US).format(selectedMonth)
        }
        val confirmText = recategorizeConfirmation(scope, count, monthLabel)
        if (confirmText != null) {
            AlertDialog(
                onDismissRequest = { pendingConfirmScope = null },
                text = { Text(confirmText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingConfirmScope = null
                            viewModel.recategorize(scope)
                        },
                    ) {
                        Text("Re-categorize")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmScope = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
