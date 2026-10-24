package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.theme.incomeColor
import net.wastu.artha.ui.transactionHumanLine

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

    // Category management state
    var categorySearchQuery by remember { mutableStateOf("") }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryError by remember { mutableStateOf<String?>(null) }
    var addingCategory by remember { mutableStateOf(false) }
    var editingIconCategory by remember { mutableStateOf<String?>(null) }
    var removingCategory by remember { mutableStateOf<String?>(null) }
    var showResetCategoriesDialog by remember { mutableStateOf(false) }
    var categoryRowMenu by remember { mutableStateOf<String?>(null) }

    // Knowledge state
    var showKnowledgeEditor by remember { mutableStateOf(false) }

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

    val visibleCategories = remember(snapshot.customCategories, categorySearchQuery) {
        if (categorySearchQuery.isBlank()) snapshot.customCategories
        else snapshot.customCategories.filter { it.contains(categorySearchQuery.trim(), ignoreCase = true) }
    }

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
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Reset categories to default") },
                                    enabled = !working,
                                    onClick = {
                                        showOverflowMenu = false
                                        showResetCategoriesDialog = true
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Status & Automation Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val progressHeadline = AiCategoriesLogic.headerStatusText(categoryProgress)
                        val showRetry = AiCategoriesLogic.showRetryAction(categoryProgress)
                        val showCategorizeNew = !showRetry && AiCategoriesLogic.showCategorizeNewAction(categoryProgress, newCount)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = if (working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Status",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = progressHeadline ?: if (snapshot.autoCategorize) "Ready" else "Paused",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (showRetry) {
                                FilledTonalButton(onClick = { viewModel.retryAutomaticCategories() }) {
                                    Text("Retry")
                                }
                            } else if (showCategorizeNew) {
                                FilledTonalButton(onClick = { viewModel.recategorize(newScope) }) {
                                    Text("Categorize new")
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Auto-categorize toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = snapshot.autoCategorize,
                                    role = Role.Switch,
                                    onValueChange = { viewModel.setAutoCategorize(it) },
                                ),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-categorize",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Automatically label new payments this month",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = snapshot.autoCategorize,
                                onCheckedChange = null,
                            )
                        }

                        // Needs Review row
                        if (categoryProgress.review > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onOpenReview)
                                    .padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Needs review",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "${categoryProgress.review} payments have low confidence",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Badge {
                                    Text(categoryProgress.review.toString())
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Failed row
                        if (categoryProgress.failed > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.recategorize(RecategorizeScope.NeedsReview) }
                                    .padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Couldn't categorize",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "${categoryProgress.failed} payments could not be labeled",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ) {
                                    Text(categoryProgress.failed.toString())
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 2. AI Provider Settings Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsSuggest,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI provider",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (snapshot.analysisEnabled) "Enabled" else "Disabled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = snapshot.analysisEnabled,
                                onCheckedChange = { viewModel.setAnalysisEnabled(it) },
                                enabled = !savingAi && !testing,
                            )
                        }

                        if (snapshot.analysisEnabled) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it; validationError = null; testResult = null; aiSaveFeedback = null },
                                label = { Text("Endpoint URL") },
                                placeholder = { Text("https://api.openai.com/v1") },
                                supportingText = { Text("OpenAI-compatible chat completion endpoint") },
                                singleLine = true,
                                enabled = !savingAi && !testing,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )

                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it; validationError = null; testResult = null; aiSaveFeedback = null },
                                label = { Text("API key") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                enabled = !savingAi && !testing,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )

                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it; validationError = null; testResult = null; aiSaveFeedback = null },
                                label = { Text("Model name") },
                                placeholder = { Text("e.g. gpt-4o-mini, gemini-1.5-flash") },
                                singleLine = true,
                                enabled = !savingAi && !testing,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
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
                                                    testResult = "Connection succeeded"
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
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    if (testing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(if (testing) "Testing..." else "Test connection")
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
                                                    aiSaveFeedback = "Settings saved"
                                                } else {
                                                    aiError = error
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    if (savingAi) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
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
                }

                // 3. Categories Management Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Label,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Categories",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${snapshot.customCategories.size} categories available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showAddCategoryDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add")
                            }
                        }

                        OutlinedTextField(
                            value = categorySearchQuery,
                            onValueChange = { categorySearchQuery = it },
                            placeholder = { Text("Filter categories...") },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingIcon = if (categorySearchQuery.isNotBlank()) ({
                                IconButton(onClick = { categorySearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }) else null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (visibleCategories.isEmpty()) {
                                Text(
                                    text = if (categorySearchQuery.isBlank()) "No categories" else "No matching categories",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                            visibleCategories.forEach { category ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                ) {
                                    Icon(
                                        categoryIconFor(category, snapshot.categoryIcons),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        text = category,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Box {
                                        IconButton(onClick = { categoryRowMenu = category }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Options for $category")
                                        }
                                        DropdownMenu(
                                            expanded = categoryRowMenu == category,
                                            onDismissRequest = { categoryRowMenu = null },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Change icon") },
                                                onClick = {
                                                    categoryRowMenu = null
                                                    editingIconCategory = category
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Remove from picker") },
                                                onClick = {
                                                    categoryRowMenu = null
                                                    removingCategory = category
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Personal Context Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Personal context",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Custom hints and notes for the AI model",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showKnowledgeEditor = true }) {
                                Text("Edit")
                            }
                        }

                        val knowledgePreview = snapshot.knowledge
                            ?.lineSequence()
                            ?.map { it.trim() }
                            ?.firstOrNull { it.isNotEmpty() }
                        Text(
                            text = knowledgePreview ?: "No personal instructions added yet. Context helps the model recognize special merchants and subscriptions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 5. Categorized Spending in Selected Month
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${monthSpending.size} payments",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (monthSpending.isEmpty()) {
                            Text(
                                text = "No payments this month",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                monthSpending.forEach { tx ->
                                    val catState = paymentCategoryState(tx.key, categoryProgress)
                                    val source = categorySource(tx, snapshot)
                                    val desc = AiCategoriesLogic.iconContentDescription(catState, source)

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onOpenTransaction(tx) }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier.size(24.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            when (catState) {
                                                PaymentCategoryState.CATEGORIZING -> {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .semantics { contentDescription = desc },
                                                        strokeWidth = 2.dp,
                                                    )
                                                }
                                                PaymentCategoryState.QUEUED -> {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Schedule,
                                                        contentDescription = desc,
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                PaymentCategoryState.IDLE -> {
                                                    when (source) {
                                                        CategorySource.MANUAL -> Icon(Icons.Outlined.Person, desc, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                                        CategorySource.AI -> Icon(Icons.Outlined.AutoAwesome, desc, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                                        CategorySource.MERCHANT -> Icon(Icons.Outlined.Storefront, desc, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                                                        CategorySource.REVIEW -> Icon(Icons.AutoMirrored.Outlined.HelpOutline, desc, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                                                        CategorySource.FAILED -> Icon(Icons.Outlined.ErrorOutline, desc, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                                        CategorySource.PENDING, CategorySource.NONE -> Icon(Icons.AutoMirrored.Outlined.Label, desc, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = transactionHumanLine(tx),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = AiCategoriesLogic.paymentRowSupportingText(catState, tx.category),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }

                                        Text(
                                            text = formatMoney(tx.money),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    // Dialogs
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
            initialKnowledge = snapshot.knowledge,
            onDismiss = { showKnowledgeEditor = false },
            onSave = { updated ->
                viewModel.applyKnowledgeRewrite(updated)
                showKnowledgeEditor = false
            },
        )
    }

    editingIconCategory?.let { category ->
        IconPickerDialog(
            currentKey = snapshot.categoryIcons[category],
            onDismiss = { editingIconCategory = null },
            onSelect = { key ->
                viewModel.setCategoryIcon(category, key)
                editingIconCategory = null
            },
        )
    }

    removingCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { removingCategory = null },
            title = { Text("Remove from picker?") },
            text = { Text("Saved payments keep their categories and notes.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeCustomCategory(category)
                    removingCategory = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removingCategory = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showResetCategoriesDialog) {
        AlertDialog(
            onDismissRequest = { showResetCategoriesDialog = false },
            title = { Text("Reset picker?") },
            text = { Text("Restore default categories and icons. Saved payment categories and notes stay.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetCategoriesToDefault()
                    showResetCategoriesDialog = false
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetCategoriesDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showAddCategoryDialog) {
        val duplicate = snapshot.customCategories.any { it.equals(newCategoryName.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = {
                if (!addingCategory) {
                    showAddCategoryDialog = false
                    newCategoryName = ""
                    newCategoryError = null
                }
            },
            title = { Text("Add category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = {
                            newCategoryName = it
                            newCategoryError = null
                        },
                        label = { Text("Category name") },
                        singleLine = true,
                        isError = duplicate || newCategoryError != null,
                        supportingText = (if (duplicate) "Already in picker" else newCategoryError)?.let { msg ->
                            { Text(msg) }
                        },
                        enabled = !addingCategory,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !addingCategory && newCategoryName.isNotBlank() && !duplicate,
                    onClick = {
                        val name = newCategoryName.trim()
                        if (name.isEmpty() || duplicate) return@TextButton
                        addingCategory = true
                        viewModel.addCustomCategory(name) { result ->
                            addingCategory = false
                            if (result.isSuccess) {
                                showAddCategoryDialog = false
                                newCategoryName = ""
                                newCategoryError = null
                            } else {
                                newCategoryError = "Couldn't add category. Try again."
                            }
                        }
                    },
                ) {
                    Text(if (addingCategory) "Adding..." else "Add")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !addingCategory,
                    onClick = {
                        showAddCategoryDialog = false
                        newCategoryName = ""
                        newCategoryError = null
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
