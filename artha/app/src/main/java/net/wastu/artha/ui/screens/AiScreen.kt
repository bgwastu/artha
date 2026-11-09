package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import net.wastu.artha.data.analysis.AiClient
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.data.analysis.categoryRunMessage
import net.wastu.artha.data.analysis.recategorizeConfirmation
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.components.PageScaffold

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

    fun historyHeadline(pending: Int, progress: CategoryProgress): String = when {
        progress.state == CategoryAutomationState.WORKING -> categoryProgressLabel(progress) ?: "Categorizing"
        pending == 0 -> "All payments categorized"
        pending == 1 -> "1 payment not categorized"
        else -> "$pending payments not categorized"
    }

    fun historySupporting(progress: CategoryProgress, configured: Boolean): String? = when {
        !configured -> "Add a provider below"
        progress.stopped || progress.state == CategoryAutomationState.ERROR -> headerStatusText(progress)
        else -> null
    }

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
fun AiScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val progress by viewModel.categoryProgress.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val working = progress.state == CategoryAutomationState.WORKING

    val saved = snapshot.aiConfig
    val baseUrl = remember(saved.baseUrl) { TextFieldState(saved.baseUrl) }
    val apiKey = remember(saved.apiKey) { TextFieldState(saved.apiKey) }
    val model = remember(saved.model) { TextFieldState(saved.model) }
    var context by rememberSaveable(snapshot.knowledge) { mutableStateOf(snapshot.knowledge.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var menu by remember { mutableStateOf(false) }
    var pendingScope by remember { mutableStateOf<RecategorizeScope?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    fun say(message: String) { coroutineScope.launch { snackbar.showSnackbar(message) } }

    LaunchedEffect(Unit) {
        viewModel.categoryRunResults.collect {
            val result = snackbar.showSnackbar(
                categoryRunMessage(it),
                actionLabel = if (it.review > 0 || it.failed > 0) "Review" else null,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenReview()
        }
    }

    fun draft() = saved.copy(baseUrl = baseUrl.text.trim().toString(), apiKey = apiKey.text.trim().toString(), model = model.text.trim().toString())
    val providerDirty = draft() != saved
    val contextDirty = context != snapshot.knowledge.orEmpty()
    val busy = testing || saving
    val providerEnabled = !busy

    fun save() {
        if (contextDirty) viewModel.applyKnowledgeRewrite(context)
        if (!providerDirty) { if (contextDirty) say("Saved"); return }
        val config = draft()
        val invalid = aiConfigValidation(config)
        if (invalid != null) { say(invalid); return }
        saving = true
        viewModel.updateAiConfig(config) { error ->
            saving = false
            say(error ?: "Saved")
        }
    }

    fun testConnection() {
        val config = draft()
        val invalid = aiConfigValidation(config)
        if (invalid != null) { say(invalid); return }
        testing = true
        coroutineScope.launch {
            val message = try {
                AiClient.testConnection(config)
                "Connected"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                aiConnectionFailure(failure)
            } finally {
                testing = false
            }
            snackbar.showSnackbar(message)
        }
    }

    val uncategorizedCount = remember(allTransactions, snapshot) { viewModel.recategorizeCount(RecategorizeScope.Uncategorized) }
    val allCount = remember(allTransactions, snapshot) { viewModel.recategorizeCount(RecategorizeScope.All) }
    val historyCount = remember(allTransactions, snapshot) { viewModel.recategorizeCount(RecategorizeScope.AllPending) }
    val configured = saved.baseUrl.isNotBlank() && saved.apiKey.isNotBlank() && saved.model.isNotBlank()

    PageScaffold(
        title = "AI categorization",
        onBack = onBack,
        snackbarHost = snackbar,
        actions = {
                    if (working) {
                        IconButton(onClick = { viewModel.stopCategorization() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop categorization")
                        }
                    }
                    TextButton(enabled = !busy && (providerDirty || contextDirty), onClick = ::save) { Text("Save") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Re-categorize uncategorized") },
                                enabled = !working && uncategorizedCount > 0,
                                onClick = { menu = false; pendingScope = RecategorizeScope.Uncategorized },
                            )
                            DropdownMenuItem(
                                text = { Text("Re-categorize all") },
                                enabled = !working && allCount > 0,
                                onClick = { menu = false; pendingScope = RecategorizeScope.All },
                            )
                        }
                    }
        },
        belowBar = {
                if (working) {
                    if (progress.runTotal > 0) {
                        LinearWavyProgressIndicator(
                            progress = { AiCardLogic.categoryProgressFraction(progress.runDone, progress.runTotal) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                } else if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().imePadding().padding(padding)) {
            item {
                ListItem(
                    headlineContent = { Text(AiCategoriesLogic.historyHeadline(historyCount, progress)) },
                    supportingContent = AiCategoriesLogic.historySupporting(progress, configured)
                        ?.let { text -> { Text(text) } },
                    trailingContent = if (!working && historyCount > 0) {
                        {
                            FilledTonalButton(
                                enabled = configured,
                                onClick = { pendingScope = RecategorizeScope.AllPending },
                            ) { Text("Categorize") }
                        }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { SectionHeader("Provider") }
            item { ProviderForm(baseUrl, apiKey, model, enabled = providerEnabled, onTest = ::testConnection) }
            item {
                ListItem(
                    headlineContent = { Text("Auto-categorize") },
                    supportingContent = { Text("New payments in the current month") },
                    trailingContent = { Switch(checked = snapshot.autoCategorize, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = snapshot.autoCategorize,
                        role = Role.Switch,
                        onValueChange = { viewModel.setAutoCategorize(it) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { SectionHeader("Personal context") }
            item {
                OutlinedTextField(
                    value = context, onValueChange = { context = it },
                    placeholder = { Text("Job, household, shops you use") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item { Box(Modifier.padding(16.dp)) }
        }
    }

    pendingScope?.let { scope ->
        val count = viewModel.recategorizeCount(scope)
        val monthLabel = remember(selectedMonth) { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US).format(selectedMonth) }
        val text = recategorizeConfirmation(scope, count, monthLabel)
        if (text != null) AlertDialog(
            onDismissRequest = { pendingScope = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { pendingScope = null; viewModel.recategorize(scope) }) {
                    Text(if (scope == RecategorizeScope.AllPending) "Categorize" else "Re-categorize")
                }
            },
            dismissButton = { TextButton(onClick = { pendingScope = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}


@Composable
private fun ProviderForm(baseUrl: TextFieldState, apiKey: TextFieldState, model: TextFieldState, enabled: Boolean, onTest: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            state = baseUrl,
            label = { Text("Endpoint URL") },
            labelPosition = TextFieldLabelPosition.Above(),
            placeholder = { Text("https://example.com/v1") },
            supportingText = { Text("Merchant names and amounts are sent to this endpoint") },
            lineLimits = TextFieldLineLimits.SingleLine, enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            state = apiKey,
            label = { Text("API key") },
            labelPosition = TextFieldLabelPosition.Above(),
            outputTransformation = { replace(0, length, "\u2022".repeat(length)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            lineLimits = TextFieldLineLimits.SingleLine, enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            state = model,
            label = { Text("Model") },
            labelPosition = TextFieldLabelPosition.Above(),
            placeholder = { Text("model name") },
            lineLimits = TextFieldLineLimits.SingleLine, enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(enabled = enabled, onClick = onTest) { Text("Test connection") }
    }
}
