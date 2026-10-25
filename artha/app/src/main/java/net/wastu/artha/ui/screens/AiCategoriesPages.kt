package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import net.wastu.artha.data.analysis.AiClient
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.aiConfigValidation
import net.wastu.artha.data.analysis.aiConnectionFailure
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSubPage(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    busy: Boolean = false,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = titleContent ?: { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = actions,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        content = content,
    )
}

@Composable
internal fun AiProviderPage(viewModel: ArthaViewModel, snapshot: AnalysisSnapshot, onBack: () -> Unit) {
    val saved = snapshot.aiConfig
    var baseUrl by remember(saved.baseUrl) { mutableStateOf(saved.baseUrl) }
    var apiKey by remember(saved.apiKey) { mutableStateOf(saved.apiKey) }
    var model by remember(saved.model) { mutableStateOf(saved.model) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val busy = testing || saving

    fun draft() = saved.copy(baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim())
    fun say(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    AiSubPage(
        title = "Provider",
        onBack = onBack,
        snackbarHostState = snackbar,
        busy = busy,
        actions = {
            TextButton(
                enabled = !busy && draft() != saved,
                onClick = {
                    val config = draft()
                    val invalid = aiConfigValidation(config)
                    if (invalid != null) { say(invalid); return@TextButton }
                    saving = true
                    viewModel.updateAiConfig(config) { error ->
                        saving = false
                        say(error ?: "Saved")
                    }
                },
            ) { Text("Save") }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().imePadding().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text("AI analysis") },
                trailingContent = {
                    Switch(checked = snapshot.analysisEnabled, onCheckedChange = null, enabled = !busy)
                },
                modifier = Modifier.toggleable(
                    value = snapshot.analysisEnabled,
                    enabled = !busy,
                    role = Role.Switch,
                    onValueChange = { viewModel.setAnalysisEnabled(it) },
                ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Endpoint URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    supportingText = { Text("Payment details are sent here") },
                    singleLine = true, enabled = !busy && snapshot.analysisEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !busy && snapshot.analysisEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("Model") },
                    placeholder = { Text("gpt-4o-mini") },
                    singleLine = true, enabled = !busy && snapshot.analysisEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !busy && snapshot.analysisEnabled,
                    onClick = {
                        val config = draft()
                        val invalid = aiConfigValidation(config)
                        if (invalid != null) { say(invalid); return@OutlinedButton }
                        testing = true
                        scope.launch {
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
                    },
                ) { Text("Test connection") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiCategoryListPage(
    viewModel: ArthaViewModel,
    snapshot: AnalysisSnapshot,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var rowMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var editingIcon by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val categories = remember(snapshot.customCategories, query) {
        snapshot.customCategories.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }

    fun closeSearch() { focus.clearFocus(); searching = false; query = "" }

    AiSubPage(
        title = "Categories",
        onBack = { if (searching) closeSearch() else onBack() },
        titleContent = if (searching) ({
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search categories") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            )
        }) else null,
        actions = {
            if (searching) {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") }
            } else {
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search categories") }
                IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add category") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Reset to defaults") }, onClick = { menu = false; reset = true })
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (categories.isEmpty()) item {
                Text(
                    if (query.isBlank()) "No categories" else "No matches",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(categories, key = { it }) { category ->
                ListItem(
                    leadingContent = { Icon(categoryIconFor(category, snapshot.categoryIcons), null) },
                    headlineContent = { Text(category) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { focus.clearFocus(); rowMenu = category }) {
                                Icon(Icons.Default.MoreVert, "Options for $category")
                            }
                            DropdownMenu(rowMenu == category, onDismissRequest = { rowMenu = null }) {
                                DropdownMenuItem(text = { Text("Change icon") }, onClick = { rowMenu = null; editingIcon = category })
                                DropdownMenuItem(text = { Text("Remove") }, onClick = { rowMenu = null; removing = category })
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    editingIcon?.let { category ->
        IconPickerDialog(snapshot.categoryIcons[category], onDismiss = { editingIcon = null }, onSelect = {
            viewModel.setCategoryIcon(category, it)
            editingIcon = null
        })
    }
    removing?.let { category ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove category?") },
            text = { Text("Saved payments keep their categories and notes.") },
            confirmButton = { TextButton(onClick = { viewModel.removeCustomCategory(category); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (reset) AlertDialog(
        onDismissRequest = { reset = false },
        title = { Text("Reset categories?") },
        text = { Text("Restores default categories and icons. Saved payments keep their categories and notes.") },
        confirmButton = { TextButton(onClick = { viewModel.resetCategoriesToDefault(); reset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun AiAddCategoryPage(
    viewModel: ArthaViewModel,
    snapshot: AnalysisSnapshot,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val duplicate = snapshot.customCategories.any { it.equals(name.trim(), ignoreCase = true) }
    LaunchedEffect(Unit) { requester.requestFocus() }

    fun submit() {
        if (saving || name.isBlank() || duplicate) return
        saving = true
        error = null
        viewModel.addCustomCategory(name.trim()) { result ->
            saving = false
            if (result.isSuccess) { focus.clearFocus(); onBack() } else error = "Couldn't add category"
        }
    }

    AiSubPage(
        title = "Add category",
        onBack = onBack,
        busy = saving,
        actions = { TextButton(enabled = !saving && name.isNotBlank() && !duplicate, onClick = ::submit) { Text("Add") } },
    ) { padding ->
        OutlinedTextField(
            value = name, onValueChange = { name = it; error = null },
            label = { Text("Category name") },
            isError = duplicate || error != null,
            supportingText = (if (duplicate) "Already exists" else error)?.let { message -> { Text(message) } },
            singleLine = true, enabled = !saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp).focusRequester(requester),
        )
    }
}

@Composable
internal fun AiContextPage(viewModel: ArthaViewModel, snapshot: AnalysisSnapshot, onBack: () -> Unit) {
    var text by rememberSaveable(snapshot.knowledge) { mutableStateOf(snapshot.knowledge.orEmpty()) }
    AiSubPage(
        title = "Personal context",
        onBack = onBack,
        actions = {
            TextButton(
                enabled = text != snapshot.knowledge.orEmpty(),
                onClick = { viewModel.applyKnowledgeRewrite(text); onBack() },
            ) { Text("Save") }
        },
    ) { padding ->
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("Job, household, shops you use") },
            modifier = Modifier.fillMaxSize().imePadding().padding(padding).padding(16.dp),
        )
    }
}
