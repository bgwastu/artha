package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.ui.ArthaViewModel

internal enum class AiCategoriesPage { HUB, PROVIDER, CATEGORIES, ADD_CATEGORY, CONTEXT }

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

@Composable
fun AiCategoriesScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    initialPage: String = AiCategoriesPage.HUB.name,
) {
    val entry = remember { AiCategoriesPage.entries.firstOrNull { it.name == initialPage } ?: AiCategoriesPage.HUB }
    var page by rememberSaveable { mutableStateOf(entry) }
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()

    fun back() {
        page = when {
            page == entry -> { onBack(); page }
            page == AiCategoriesPage.ADD_CATEGORY -> AiCategoriesPage.CATEGORIES
            else -> AiCategoriesPage.HUB
        }
    }
    BackHandler(enabled = page != AiCategoriesPage.HUB) { back() }

    when (page) {
        AiCategoriesPage.HUB -> AiCategoriesHub(
            viewModel = viewModel,
            onBack = onBack,
            onOpenReview = onOpenReview,
            onOpenPage = { page = it },
        )
        AiCategoriesPage.PROVIDER -> AiProviderPage(viewModel, snapshot, ::back)
        AiCategoriesPage.CATEGORIES -> AiCategoryListPage(viewModel, snapshot, ::back, onAdd = { page = AiCategoriesPage.ADD_CATEGORY })
        AiCategoriesPage.ADD_CATEGORY -> AiAddCategoryPage(viewModel, snapshot, ::back)
        AiCategoriesPage.CONTEXT -> AiContextPage(viewModel, snapshot, ::back)
    }
}

@Composable
private fun AiCategoriesHub(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenPage: (AiCategoriesPage) -> Unit,
) {
    val progress by viewModel.categoryProgress.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()

    val working = progress.state == CategoryAutomationState.WORKING
    val isError = progress.state == CategoryAutomationState.ERROR
    val config = snapshot.aiConfig
    val configured = config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()

    val currentMonth = remember { YearMonth.now(AccountingTime.zone) }
    val newScope = remember(currentMonth) { RecategorizeScope.New(currentMonth) }
    val monthScope = remember(selectedMonth) { RecategorizeScope.Month(selectedMonth) }
    val newCount = remember(allTransactions, snapshot, newScope) { viewModel.recategorizeCount(newScope) }
    val monthCount = remember(allTransactions, snapshot, monthScope) { viewModel.recategorizeCount(monthScope) }
    val uncategorizedCount = remember(allTransactions, snapshot) { viewModel.recategorizeCount(RecategorizeScope.Uncategorized) }
    val allCount = remember(allTransactions, snapshot) { viewModel.recategorizeCount(RecategorizeScope.All) }

    var pendingConfirmScope by remember { mutableStateOf<RecategorizeScope?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    AiSubPage(
        title = "AI categorization",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        busy = working,
        actions = {
            if (working) {
                IconButton(onClick = { viewModel.stopCategorization() }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop categorization")
                }
            }
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                    listOf(
                        Triple("Re-categorize this month", monthScope, monthCount),
                        Triple("Re-categorize uncategorized", RecategorizeScope.Uncategorized, uncategorizedCount),
                        Triple("Re-categorize all", RecategorizeScope.All, allCount),
                    ).forEach { (label, scope, count) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            enabled = !working && count > 0,
                            onClick = { showOverflowMenu = false; pendingConfirmScope = scope },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())) {
            val showRetry = AiCategoriesLogic.showRetryAction(progress)
            val showCategorizeNew = !showRetry && AiCategoriesLogic.showCategorizeNewAction(progress, newCount)
            ListItem(
                headlineContent = {
                    Text(
                        text = AiCategoriesLogic.headerStatusText(progress)
                            ?: if (configured) "Up to date" else "Not set up",
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                },
                trailingContent = {
                    if (showRetry) {
                        FilledTonalButton(onClick = { viewModel.retryAutomaticCategories() }) { Text("Retry") }
                    } else if (showCategorizeNew) {
                        FilledTonalButton(onClick = { viewModel.recategorize(newScope) }) { Text("Categorize new") }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            if (progress.review > 0) {
                ListItem(
                    headlineContent = { Text("Needs review") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Badge { Text(progress.review.toString()) }
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable(onClick = onOpenReview),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (progress.failed > 0) {
                ListItem(
                    leadingContent = {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    headlineContent = { Text("Couldn't categorize") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) { Text(progress.failed.toString()) }
                            TextButton(onClick = { viewModel.recategorize(RecategorizeScope.NeedsReview) }) { Text("Retry") }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            ListItem(
                headlineContent = { Text("Auto-categorize") },
                supportingContent = { Text("New payments this month") },
                trailingContent = { Switch(checked = snapshot.autoCategorize, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = snapshot.autoCategorize,
                    role = Role.Switch,
                    onValueChange = { viewModel.setAutoCategorize(it) },
                ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            val contextSummary = snapshot.knowledge.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Not set"
            HubLink(Icons.Outlined.Memory, "Provider", if (configured) config.model else "Not set up") { onOpenPage(AiCategoriesPage.PROVIDER) }
            HubLink(Icons.Outlined.Category, "Categories", "${snapshot.customCategories.size} categories") { onOpenPage(AiCategoriesPage.CATEGORIES) }
            HubLink(Icons.Outlined.Person, "Personal context", contextSummary) { onOpenPage(AiCategoriesPage.CONTEXT) }
        }
    }

    pendingConfirmScope?.let { scope ->
        val count = viewModel.recategorizeCount(scope)
        val monthLabel = remember(selectedMonth) { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US).format(selectedMonth) }
        val confirmText = recategorizeConfirmation(scope, count, monthLabel)
        if (confirmText != null) {
            AlertDialog(
                onDismissRequest = { pendingConfirmScope = null },
                text = { Text(confirmText) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmScope = null
                        viewModel.recategorize(scope)
                        coroutineScope.launch { snackbarHostState.showSnackbar("Re-categorizing $count payments") }
                    }) { Text("Re-categorize") }
                },
                dismissButton = { TextButton(onClick = { pendingConfirmScope = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun HubLink(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
