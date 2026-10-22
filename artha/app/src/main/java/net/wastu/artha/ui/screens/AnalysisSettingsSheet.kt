package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.wastu.artha.data.analysis.AiAnalysisService
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.CategoryAutomationState
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.ui.theme.incomeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisSettingsSheet(
    aiConfig: AiConfig,
    analysisEnabled: Boolean,
    customCategories: List<String>,
    categoryIcons: Map<String, String>,
    automationState: CategoryAutomationState?,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Transaction Analysis", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss, enabled = !savingAi) { Icon(Icons.Default.Close, "Close") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable analysis", modifier = Modifier.weight(1f))
                Switch(checked = analysisEnabled, onCheckedChange = onAnalysisEnabled, enabled = !savingAi)
            }
            if (!analysisEnabled) {
                Text(
                    "Turn on to use categories, AI labeling, and monthly insights",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text("AI provider", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Text(
                        "Sends payment details and personal context to your provider for categorization and summaries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(baseUrl, { baseUrl = it; validationError = null; testResult = null },
                        label = { Text("Endpoint URL") }, singleLine = true, enabled = !savingAi && !testing, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it; validationError = null; testResult = null },
                        label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, enabled = !savingAi && !testing, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it; validationError = null; testResult = null },
                        label = { Text("Model") }, singleLine = true, enabled = !savingAi && !testing, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !savingAi && !testing, onClick = {
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
                        }) { Text(if (testing) "Testing" else "Test") }
                        FilledTonalButton(enabled = !savingAi && !testing, onClick = {
                            val config = draft()
                            validationError = aiConfigValidation(config)
                            if (validationError == null) onSaveAi(config)
                        }) { Text(if (savingAi) "Saving" else "Save AI") }
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
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    ManageCategoriesContent(
                        customCategories, categoryIcons, onDismiss, onAddCategory, onRemoveCategory,
                        onSetCategoryIcon, onResetCategories, automationState, onRetryAi,
                        expandToSheetHeight = false, showCloseButton = false,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Personal context", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                    Text(
                        "Facts the AI may use to read your payments fairly. Part of Transaction Analysis, so it is only here while analysis is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(enabled = !savingAi, onClick = { showKnowledge = true }) { Text("Edit personal context") }
                    }
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
