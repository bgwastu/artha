package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AiClient
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.theme.incomeColor

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
    onOpenTransaction: (UnifiedTransaction) -> Unit = {},
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

    // Provider form state
    var baseUrl by remember(snapshot.aiConfig.baseUrl) { mutableStateOf(snapshot.aiConfig.baseUrl) }
    var apiKey by remember(snapshot.aiConfig.apiKey) { mutableStateOf(snapshot.aiConfig.apiKey) }
    var model by remember(snapshot.aiConfig.model) { mutableStateOf(snapshot.aiConfig.model) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var savingAi by remember { mutableStateOf(false) }
    var aiSaveFeedback by remember { mutableStateOf<String?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }

    // Knowledge editor state
    var showKnowledgeEditor by remember { mutableStateOf(false) }

    val monthScope = remember(selectedMonth) { RecategorizeScope.Month(selectedMonth) }
    val monthCount = remember(allTransactions, snapshot, monthScope) { viewModel.recategorizeCount(monthScope) }
    val uncategorizedScope = RecategorizeScope.Uncategorized
    val uncategorizedCount = remember(allTransactions, snapshot, uncategorizedScope) { viewModel.recategorizeCount(uncategorizedScope) }
    val allScope = RecategorizeScope.All
    val allCount = remember(allTransactions, snapshot, allScope) { viewModel.recategorizeCount(allScope) }

    fun draftConfig() = snapshot.aiConfig.copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
    )

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = { Text("AI categorization") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
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
                if (working || savingAi || testing) {
                    val fraction = if (categoryProgress.total > 0) {
                        (categoryProgress.labeled.toFloat() / categoryProgress.total.toFloat()).coerceIn(0f, 1f)
                    } else null
                    if (fraction != null && working) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Status row
            val progressHeadline = AiCategoriesLogic.headerStatusText(categoryProgress)
            val showRetry = AiCategoriesLogic.showRetryAction(categoryProgress)
            val showCategorizeNew = !showRetry && AiCategoriesLogic.showCategorizeNewAction(categoryProgress, newCount)

            ListItem(
                headlineContent = {
                    Text(
                        text = progressHeadline ?: if (snapshot.autoCategorize) "Ready" else "Paused",
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = {
                    Text(
                        if (snapshot.autoCategorize) "Auto-categorize runs on new spending"
                        else "Auto-categorize is paused",
                    )
                },
                trailingContent = {
                    if (showRetry) {
                        FilledTonalButton(onClick = { viewModel.retryAutomaticCategories() }) {
                            Text("Retry")
                        }
                    } else if (showCategorizeNew) {
                        FilledTonalButton(onClick = { viewModel.recategorize(newScope) }) {
                            Text("Categorize new")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            // Needs Review row
            if (categoryProgress.review > 0) {
                ListItem(
                    headlineContent = { Text("Needs review") },
                    supportingContent = { Text("${categoryProgress.review} payments have low confidence") },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Badge { Text(categoryProgress.review.toString()) }
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable(onClick = onOpenReview),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            // Failed row
            if (categoryProgress.failed > 0) {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = { Text("Couldn't categorize") },
                    supportingContent = { Text("${categoryProgress.failed} payments could not be labeled") },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Text(categoryProgress.failed.toString())
                            }
                            TextButton(onClick = { viewModel.recategorize(RecategorizeScope.NeedsReview) }) {
                                Text("Retry")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            HorizontalDivider()

            // Automation settings
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )

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

            ListItem(
                headlineContent = { Text("AI analysis") },
                supportingContent = { Text("Enable model suggestions and monthly insights") },
                trailingContent = {
                    Switch(
                        checked = snapshot.analysisEnabled,
                        onCheckedChange = null,
                        enabled = !savingAi && !testing,
                    )
                },
                modifier = Modifier.toggleable(
                    value = snapshot.analysisEnabled,
                    enabled = !savingAi && !testing,
                    role = Role.Switch,
                    onValueChange = { viewModel.setAnalysisEnabled(it) },
                ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            if (snapshot.analysisEnabled) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text(
                    text = "AI provider",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .semantics { heading() }
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; validationError = null; testResult = null; aiSaveFeedback = null },
                        label = { Text("Endpoint URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        supportingText = { Text("Payment details are sent here") },
                        singleLine = true,
                        enabled = !savingAi && !testing,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; validationError = null; testResult = null; aiSaveFeedback = null },
                        label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !savingAi && !testing,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; validationError = null; testResult = null; aiSaveFeedback = null },
                        label = { Text("Model") },
                        placeholder = { Text("e.g. gpt-4o-mini, gemini-1.5-flash") },
                        singleLine = true,
                        enabled = !savingAi && !testing,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        OutlinedButton(
                            enabled = !savingAi && !testing,
                            onClick = {
                                val config = draftConfig()
                                validationError = aiConfigValidation(config)
                                testResult = null
                                aiSaveFeedback = null
                                if (validationError == null) {
                                    testing = true
                                    coroutineScope.launch {
                                        try {
                                            AiClient.testConnection(config)
                                            testSucceeded = true
                                            testResult = "Connected"
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            testSucceeded = false
                                            testResult = aiConnectionFailure(failure)
                                        } finally {
                                            testing = false
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (testing) "Testing..." else "Test")
                        }

                        Button(
                            enabled = !savingAi && !testing,
                            onClick = {
                                val config = draftConfig()
                                validationError = aiConfigValidation(config)
                                testResult = null
                                aiSaveFeedback = null
                                if (validationError == null) {
                                    savingAi = true
                                    viewModel.updateAiConfig(config) { error ->
                                        savingAi = false
                                        if (error == null) {
                                            aiSaveFeedback = "Saved"
                                        } else {
                                            aiError = error
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (savingAi) "Saving..." else "Save")
                        }
                    }

                    if (testResult != null || validationError != null || aiError != null || aiSaveFeedback != null) {
                        Column(modifier = Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            testResult?.let {
                                Text(it, color = if (testSucceeded) incomeColor() else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                            aiSaveFeedback?.let {
                                Text(it, color = incomeColor(), style = MaterialTheme.typography.bodyMedium)
                            }
                            (validationError ?: aiError)?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Categories Management
            ManageCategoriesContent(
                categories = snapshot.customCategories,
                categoryIcons = snapshot.categoryIcons,
                onDismiss = onBack,
                onAdd = { name, complete -> viewModel.addCustomCategory(name, complete) },
                onRemove = { viewModel.removeCustomCategory(it) },
                onSetIcon = { cat, icon -> viewModel.setCategoryIcon(cat, icon) },
                onReset = { viewModel.resetCategoriesToDefault() },
                automationState = null,
                onRetryAi = viewModel::retryAutomaticCategories,
                expandToSheetHeight = false,
                showCloseButton = false,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Personal context
            Text(
                text = "Context",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )

            val knowledgeSummary = snapshot.knowledge
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?: "Not set"

            ListItem(
                headlineContent = { Text("Personal context") },
                supportingContent = { Text(knowledgeSummary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = "Personal context") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { showKnowledgeEditor = true },
            )
        }
    }

    // Confirmation dialog for manual recategorize
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

    if (showKnowledgeEditor) {
        KnowledgeEditorDialog(
            initialKnowledge = snapshot.knowledge.orEmpty(),
            onDismiss = { showKnowledgeEditor = false },
            onSave = { updated ->
                viewModel.applyKnowledgeRewrite(updated)
                showKnowledgeEditor = false
            },
        )
    }
}
