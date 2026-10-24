package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
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
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.transactionHumanLine
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object AiCategoriesLogic {
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

    fun iconContentDescription(state: PaymentCategoryState, source: CategorySource): String = when (state) {
        PaymentCategoryState.CATEGORIZING -> "Categorizing"
        PaymentCategoryState.QUEUED -> "Queued"
        PaymentCategoryState.IDLE -> when (source) {
            CategorySource.MANUAL -> "Set by you"
            CategorySource.AI -> "Suggested by AI"
            CategorySource.MERCHANT -> "Same merchant"
            CategorySource.REVIEW -> "Needs review"
            CategorySource.FAILED -> "Couldn't categorize"
            CategorySource.PENDING, CategorySource.NONE -> "Not categorized"
        }
    }

    fun paymentRowSupportingText(state: PaymentCategoryState, category: String?): String = when (state) {
        PaymentCategoryState.CATEGORIZING -> "Categorizing"
        PaymentCategoryState.QUEUED -> "Queued"
        PaymentCategoryState.IDLE -> category ?: "Uncategorized"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val newCount = remember(allTransactions, snapshot, newScope) { viewModel.recategorizeCount(newScope) }

    val working = categoryProgress.state == CategoryAutomationState.WORKING
    val isError = categoryProgress.state == CategoryAutomationState.ERROR

    var pendingConfirmScope by remember { mutableStateOf<RecategorizeScope?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val monthSpending = remember(allTransactions, selectedMonth) {
        allTransactions
            .filter { tx ->
                BudgetAnalyzer.isSpending(tx) &&
                    YearMonth.from(tx.occurredAt.atZone(AccountingTime.zone)) == selectedMonth
            }
            .sortedByDescending { it.occurredAt }
    }

    val monthScope = remember(selectedMonth) { RecategorizeScope.Month(selectedMonth) }
    val monthCount = remember(allTransactions, snapshot, monthScope) { viewModel.recategorizeCount(monthScope) }
    val uncategorizedScope = RecategorizeScope.Uncategorized
    val uncategorizedCount = remember(allTransactions, snapshot, uncategorizedScope) { viewModel.recategorizeCount(uncategorizedScope) }
    val allScope = RecategorizeScope.All
    val allCount = remember(allTransactions, snapshot, allScope) { viewModel.recategorizeCount(allScope) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (working) {
                        IconButton(onClick = { viewModel.stopCategorization() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Re-categorize this month") },
                                enabled = !working && monthCount > 0,
                                onClick = {
                                    showOverflowMenu = false
                                    pendingConfirmScope = monthScope
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Re-categorize uncategorized") },
                                enabled = !working && uncategorizedCount > 0,
                                onClick = {
                                    showOverflowMenu = false
                                    pendingConfirmScope = uncategorizedScope
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Re-categorize all") },
                                enabled = !working && allCount > 0,
                                onClick = {
                                    showOverflowMenu = false
                                    pendingConfirmScope = allScope
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (working) {
                val fraction = if (categoryProgress.total > 0) {
                    (categoryProgress.labeled.toFloat() / categoryProgress.total.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                item(key = "progress_status") {
                    val progressHeadline = AiCategoriesLogic.headerStatusText(categoryProgress)
                    val showRetry = AiCategoriesLogic.showRetryAction(categoryProgress)
                    val showCategorizeNew = !showRetry && AiCategoriesLogic.showCategorizeNewAction(categoryProgress, newCount)

                    ListItem(
                        headlineContent = {
                            Text(
                                text = progressHeadline.orEmpty(),
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        trailingContent = {
                            if (showRetry) {
                                TextButton(onClick = { viewModel.retryAutomaticCategories() }) {
                                    Text("Retry")
                                }
                            } else if (showCategorizeNew) {
                                TextButton(onClick = { viewModel.recategorize(newScope) }) {
                                    Text("Categorize new")
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }

                item(key = "auto_categorize") {
                    ListItem(
                        headlineContent = { Text("Auto-categorize") },
                        supportingContent = { Text("New payments this month") },
                        trailingContent = {
                            Switch(
                                checked = snapshot.autoCategorize,
                                onCheckedChange = null,
                            )
                        },
                        modifier = Modifier.toggleable(
                            value = snapshot.autoCategorize,
                            role = Role.Switch,
                            onValueChange = { viewModel.setAutoCategorize(it) },
                        ),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }

                if (categoryProgress.review > 0) {
                    item(key = "needs_review") {
                        ListItem(
                            headlineContent = { Text("Needs review") },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Badge {
                                        Text(categoryProgress.review.toString())
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier = Modifier.clickable(onClick = onOpenReview),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }

                if (categoryProgress.failed > 0) {
                    item(key = "failed") {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            headlineContent = { Text("Couldn't categorize") },
                            trailingContent = {
                                Badge {
                                    Text(categoryProgress.failed.toString())
                                }
                            },
                            modifier = Modifier.clickable {
                                viewModel.recategorize(RecategorizeScope.NeedsReview)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }

                item(key = "month_divider_and_header") {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider()
                        Text(
                            text = selectedMonth.format(DateTimeFormatter.ofPattern("MMMM", Locale.US)),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .semantics { heading() }
                                .padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                }

                if (monthSpending.isEmpty()) {
                    item(key = "empty_month") {
                        ListItem(
                            headlineContent = { Text("No payments this month") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                } else {
                    items(monthSpending, key = { it.key }) { tx ->
                        val catState = paymentCategoryState(tx.key, categoryProgress)
                        val source = categorySource(tx, snapshot)
                        val desc = AiCategoriesLogic.iconContentDescription(catState, source)

                        ListItem(
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when (catState) {
                                        PaymentCategoryState.CATEGORIZING -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .semantics { contentDescription = desc },
                                                strokeWidth = 2.dp,
                                            )
                                        }
                                        PaymentCategoryState.QUEUED -> {
                                            Icon(
                                                imageVector = Icons.Outlined.Schedule,
                                                contentDescription = desc,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                        PaymentCategoryState.IDLE -> {
                                            when (source) {
                                                CategorySource.MANUAL -> {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Person,
                                                        contentDescription = desc,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                                CategorySource.AI -> {
                                                    Icon(
                                                        imageVector = Icons.Outlined.AutoAwesome,
                                                        contentDescription = desc,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                                CategorySource.MERCHANT -> {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Storefront,
                                                        contentDescription = desc,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                                CategorySource.REVIEW -> {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                                        contentDescription = desc,
                                                        tint = MaterialTheme.colorScheme.tertiary,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                                CategorySource.FAILED -> {
                                                    Icon(
                                                        imageVector = Icons.Outlined.ErrorOutline,
                                                        contentDescription = desc,
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                                CategorySource.PENDING, CategorySource.NONE -> {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Outlined.Label,
                                                        contentDescription = desc,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            headlineContent = {
                                Text(
                                    text = transactionHumanLine(tx),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = AiCategoriesLogic.paymentRowSupportingText(catState, tx.category),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Text(
                                    text = formatMoney(tx.money),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                            modifier = Modifier.clickable { onOpenTransaction(tx) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
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
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Re-categorizing $count payments")
                            }
                        }
                    ) {
                        Text("Re-categorize")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmScope = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
