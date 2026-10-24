package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.wastu.artha.data.analysis.AiAnalysisService
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.data.analysis.categoryProgressLabel
import net.wastu.artha.ui.theme.incomeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisSettingsSheet(
    aiConfig: AiConfig,
    analysisEnabled: Boolean,
    customCategories: List<String>,
    categoryIcons: Map<String, String>,
    automationState: CategoryAutomationState? = null,
    categoryProgress: CategoryProgress,
    savingAi: Boolean,
    aiError: String?,
    onDismiss: () -> Unit,
    onAnalysisEnabled: (Boolean) -> Unit,
    onSaveAi: (AiConfig) -> Unit,
    onAddCategory: (String, (Result<Unit>) -> Unit) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onSetCategoryIcon: (String, String?) -> Unit,
    onResetCategories: () -> Unit,
    onRetryAi: () -> Unit,
    knowledge: String?,
    onApplyKnowledge: (String) -> Unit,
    onOpenAiCategories: () -> Unit = {},
) {
    var showKnowledge by rememberSaveable { mutableStateOf(false) }
    var baseUrl by remember(aiConfig) { mutableStateOf(aiConfig.baseUrl) }
    var apiKey by remember(aiConfig) { mutableStateOf(aiConfig.apiKey) }
    var model by remember(aiConfig) { mutableStateOf(aiConfig.model) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val feedbackRequester = remember { BringIntoViewRequester() }
    fun draft() = aiConfig.copy(baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim())

    ModalBottomSheet(
        onDismissRequest = { if (!savingAi) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
                .padding(vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Transaction Analysis", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss, enabled = !savingAi) { Icon(Icons.Default.Close, "Close") }
            }
            ListItem(
                headlineContent = { Text("Analysis") },
                trailingContent = {
                    Switch(
                        checked = analysisEnabled,
                        onCheckedChange = null,
                        enabled = !savingAi,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.toggleable(
                    value = analysisEnabled,
                    enabled = !savingAi,
                    role = Role.Switch,
                    onValueChange = onAnalysisEnabled,
                ),
            )
            if (analysisEnabled) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val progressLabel = categoryProgressLabel(categoryProgress)
                    ListItem(
                        headlineContent = { Text("AI categories") },
                        supportingContent = progressLabel?.let { { Text(it) } },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Open AI categories")
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(onClick = onOpenAiCategories),
                    )
                    HorizontalDivider()
                    Text(
                        "AI provider",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it; validationError = null; testResult = null },
                            label = { Text("Endpoint URL") },
                            supportingText = { Text("Payment details are sent here") },
                            singleLine = true,
                            enabled = !savingAi && !testing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; validationError = null; testResult = null },
                            label = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !savingAi && !testing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it; validationError = null; testResult = null },
                            label = { Text("Model") },
                            singleLine = true,
                            enabled = !savingAi && !testing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(
                                enabled = !savingAi && !testing,
                                onClick = {
                                    val config = draft()
                                    validationError = aiConfigValidation(config)
                                    testResult = null
                                    if (validationError == null) {
                                        testing = true
                                        scope.launch {
                                            try {
                                                AiAnalysisService.testConnection(config)
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
                                Text(if (testing) "Testing" else "Test")
                            }
                            Button(
                                enabled = !savingAi && !testing,
                                onClick = {
                                    val config = draft()
                                    validationError = aiConfigValidation(config)
                                    if (validationError == null) onSaveAi(config)
                                },
                            ) {
                                Text(if (savingAi) "Saving" else "Save")
                            }
                        }
                        if (testResult != null || validationError != null || aiError != null) {
                            Column(Modifier.bringIntoViewRequester(feedbackRequester).padding(vertical = 4.dp)) {
                                testResult?.let {
                                    Text(it, color = if (testSucceeded) incomeColor() else MaterialTheme.colorScheme.error)
                                }
                                (validationError ?: aiError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                            LaunchedEffect(testResult, validationError, aiError) { feedbackRequester.bringIntoView() }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    ManageCategoriesContent(
                        customCategories, categoryIcons, onDismiss, onAddCategory, onRemoveCategory,
                        onSetCategoryIcon, onResetCategories, automationState = null, onRetryAi,
                        expandToSheetHeight = false, showCloseButton = false,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    val knowledgeSummary = knowledge
                        ?.lineSequence()
                        ?.map { it.trim() }
                        ?.firstOrNull { it.isNotEmpty() }
                        ?: "Not set"
                    ListItem(
                        headlineContent = { Text("Personal context") },
                        supportingContent = { Text(knowledgeSummary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = "Personal context") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = !savingAi) { showKnowledge = true },
                    )
                }
            }
        }
    }
    if (showKnowledge && analysisEnabled) {
        KnowledgeEditorDialog(
            initialKnowledge = knowledge.orEmpty(),
            onDismiss = { showKnowledge = false },
            onSave = { text ->
                showKnowledge = false
                onApplyKnowledge(text)
            },
        )
    }
}
