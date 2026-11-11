package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.wastu.artha.data.analysis.AiClient
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AiProvider
import net.wastu.artha.data.analysis.AiProviders
import net.wastu.artha.data.analysis.ModelCatalog
import net.wastu.artha.data.analysis.ModelInfo
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.components.PageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
) {
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()

    val saved = snapshot.aiConfig
    val baseUrl = remember(saved.baseUrl) { TextFieldState(saved.baseUrl) }
    val apiKey = remember(saved.apiKey) { TextFieldState(saved.apiKey) }
    val model = remember(saved.model) { TextFieldState(saved.model) }
    var providerId by rememberSaveable(saved.baseUrl) { mutableStateOf(AiProviders.selectionFor(saved)) }
    var context by rememberSaveable(snapshot.knowledge) { mutableStateOf(snapshot.knowledge.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var contextOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    fun say(message: String) { coroutineScope.launch { snackbar.showSnackbar(message) } }

    fun draft() = saved.copy(baseUrl = baseUrl.text.trim().toString(), apiKey = apiKey.text.trim().toString(), model = model.text.trim().toString())
    val providerDirty = draft() != saved
    val contextDirty = context != snapshot.knowledge.orEmpty()
    val busy = saving
    val dirty = providerDirty || contextDirty
    BackHandler(enabled = dirty) { confirmDiscard = true }

    /** Fills the form for [provider] (null is Custom); the key only comes back if it was saved for that provider. */
    fun choose(provider: AiProvider?) {
        providerId = provider?.id ?: AiProviders.CUSTOM_ID
        val next = AiProviders.draftFor(provider, saved)
        baseUrl.setTextAndPlaceCursorAtEnd(next.baseUrl)
        apiKey.setTextAndPlaceCursorAtEnd(next.apiKey)
        model.setTextAndPlaceCursorAtEnd(next.model)
    }

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

    PageScaffold(
        title = "AI categorization",
        onBack = { if (dirty) confirmDiscard = true else onBack() },
        snackbarHost = snackbar,
        actions = { Button(enabled = !busy && dirty, onClick = ::save, modifier = Modifier.padding(end = 8.dp)) { Text("Save") } },
        belowBar = { if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth()) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().imePadding().padding(padding)) {
            item { SectionHeader("Provider") }
            item { ProviderSelector(providerId, enabled = !busy, onChoose = ::choose) }
            providerId?.let { id ->
                item(key = "provider-$id") {
                    ProviderForm(AiProviders.presets.firstOrNull { it.id == id }, baseUrl, apiKey, model, enabled = !busy, onMessage = ::say)
                }
            }
            item { AutoCategorizeRow(snapshot.autoCategorize) { viewModel.setAutoCategorize(it) } }
            item {
                Column(Modifier.animateContentSize()) {
                    CollapsibleHeader("Personal context", "Shapes how the AI categorizes your payments", contextOpen) { contextOpen = !contextOpen }
                    if (contextOpen) PersonalContextField(context) { context = it }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved changes will be lost.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun PersonalContextField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Job, household, shops you use") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun AutoCategorizeRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text("Auto-categorize") },
        supportingContent = { Text("New payments in the current month") },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** A section title that expands and collapses what sits under it. */
@Composable
private fun CollapsibleHeader(title: String, description: String, expanded: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(description) },
        trailingContent = {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand")
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onToggle),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SectionHeader(title: String) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** One field that opens the popular providers, then Custom. Picking one fills the URL, leaving only the key to paste. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelector(selectedId: String?, enabled: Boolean, onChoose: (AiProvider?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = AiProviders.presets.firstOrNull { it.id == selectedId }
    val name = when (selectedId) {
        null -> ""
        AiProviders.CUSTOM_ID -> "Custom"
        else -> selected?.name.orEmpty()
    }
    val field = remember(name) { TextFieldState(name) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            state = field,
            readOnly = true,
            label = { Text("Provider") },
            labelPosition = TextFieldLabelPosition.Above(),
            placeholder = { Text("Choose a provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            lineLimits = TextFieldLineLimits.SingleLine, enabled = enabled,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            AiProviders.presets.forEach { provider ->
                DropdownMenuItem(text = { Text(provider.name) }, onClick = { expanded = false; onChoose(provider) })
            }
            DropdownMenuItem(text = { Text("Custom") }, onClick = { expanded = false; onChoose(null) })
        }
    }
}

/** The last model-list request: [models] when the key worked, [error] when it didn't. Valid only for the url and key it was made with. */
private data class KeyCheck(val url: String, val key: String, val models: List<String>?, val error: String?)

/**
 * [provider] null is Custom: the user types the endpoint. A preset already knows it, so only the key is required.
 * The key is checked only when the user asks: Verify, or Done on the keyboard. Editing it afterwards locks the model again.
 */
@Composable
private fun ProviderForm(provider: AiProvider?, baseUrl: TextFieldState, apiKey: TextFieldState, model: TextFieldState, enabled: Boolean, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<KeyCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    val run = remember { IntArray(1) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Prices and deprecations from models.dev, when reachable. Nothing here depends on it.
    var catalog by remember { mutableStateOf<Map<String, ModelInfo>>(emptyMap()) }
    LaunchedEffect(provider?.catalogId) {
        val id = provider?.catalogId ?: return@LaunchedEffect
        ModelCatalog.forProvider(File(context.cacheDir, "models-dev.json"), id) { catalog = it }
    }

    fun verify() {
        val url = baseUrl.text.trim().toString()
        val key = apiKey.text.trim().toString()
        val config = AiConfig(url, key, "model")
        if (aiConfigValidation(config) != null) return
        focusManager.clearFocus()
        job?.cancel()
        val mine = ++run[0]
        checking = true
        job = scope.launch {
            try {
                val models = ModelCatalog.visible(AiClient.listModels(config), catalog)
                check = KeyCheck(url, key, models, null)
                onMessage("Key verified. ${models.size} models available")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = aiConnectionFailure(failure)
                check = KeyCheck(url, key, null, reason)
                onMessage(if (provider == null) "$reason. If this endpoint has no model list, type the model name" else reason)
            } finally { if (run[0] == mine) checking = false }
        }
    }

    val url = baseUrl.text.trim().toString()
    val key = apiKey.text.trim().toString()
    val current = check?.takeIf { it.url == url && it.key == key }
    // A custom endpoint that can't list models falls back to a typed name.
    val typedOnly = provider == null && current?.error != null
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (provider == null) {
            OutlinedTextField(
                state = baseUrl,
                label = { Text("Endpoint URL") },
                labelPosition = TextFieldLabelPosition.Above(),
                placeholder = { Text("https://example.com/v1") },
                lineLimits = TextFieldLineLimits.SingleLine, enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            state = apiKey,
            label = { Text("API key") },
            labelPosition = TextFieldLabelPosition.Above(),
            outputTransformation = { replace(0, length, "•".repeat(length)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            onKeyboardAction = KeyboardActionHandler { performDefaultAction -> verify(); performDefaultAction() },
            lineLimits = TextFieldLineLimits.SingleLine, enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        // Once this URL and key are verified the model picker takes over; editing either brings Verify back.
        if (current?.models == null) {
            val canVerify = aiConfigValidation(AiConfig(url, key, "model")) == null
            FilledTonalButton(enabled = enabled && canVerify && !checking, onClick = ::verify) {
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Verify")
            }
        }
        if (current?.models != null || typedOnly) {
            ModelSelector(model, current?.models, catalog, typedOnly, enabled)
        }
    }
}

private const val MODEL_MENU_LIMIT = 50

/**
 * Searchable model picker. It stays locked until [models] is known, or [typedOnly] allows a free name.
 * Typing filters the list and any typed name is kept, so a model the provider doesn't list still works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(model: TextFieldState, models: List<String>?, info: Map<String, ModelInfo>, typedOnly: Boolean, enabled: Boolean) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    // Typing in this field opens the list; text set by picking an entry or by the app does not.
    LaunchedEffect(model) {
        snapshotFlow { model.text.toString() }.drop(1).collect { if (focused && it != picked) expanded = true }
    }

    val usable = enabled && (models != null || typedOnly)
    val menuOpen = expanded && usable && models != null
    val query = model.text.trim().toString()
    val all = models.orEmpty()
    val shown = if (query.isEmpty() || all.any { it == query }) all else all.filter { it.contains(query, ignoreCase = true) }

    ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            state = model,
            label = { Text("Model") },
            labelPosition = TextFieldLabelPosition.Above(),
            placeholder = { Text("Search or type a model name") },
            trailingIcon = if (models != null) ({ ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) }) else null,
            lineLimits = TextFieldLineLimits.SingleLine, enabled = usable,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, usable),
        )
        ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { expanded = false }) {
            shown.take(MODEL_MENU_LIMIT).forEach { id ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(id)
                            ModelCatalog.priceLabel(info[id])?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        picked = id; model.setTextAndPlaceCursorAtEnd(id); expanded = false
                        // Choosing is the end of typing: drop focus and the keyboard so the form is visible again.
                        focusManager.clearFocus(); keyboard?.hide()
                    },
                )
            }
            val note = when {
                shown.size > MODEL_MENU_LIMIT -> "${shown.size} models. Keep typing to narrow."
                shown.isEmpty() -> "No match. The typed name is used."
                else -> null
            }
            if (note != null) DropdownMenuItem(text = { Text(note, style = MaterialTheme.typography.bodySmall) }, enabled = false, onClick = {})
        }
    }
}
