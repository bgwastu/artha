package net.wastu.artha.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import net.wastu.artha.ui.components.PageScaffold
import net.wastu.artha.ui.components.ConnectedChoiceRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.core.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(viewModel: ArthaViewModel, onBack: () -> Unit) {
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val type = if (tab == 0) TransactionType.EXPENSE else TransactionType.INCOME
    val categories = snapshot.categoriesFor(type)
    var menu by remember { mutableStateOf(false) }
    var rowMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var editingIcon by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }

    PageScaffold(
        title = "Categories",
        onBack = onBack,
        actions = {
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add category") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reset categories") }, onClick = { menu = false; resetting = true })
                }
            }
        },
        belowBar = {
            ConnectedChoiceRow(
                options = listOf(0, 1),
                selected = tab,
                onSelect = { tab = it },
                label = { if (it == 0) "Expenses" else "Income" },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(categories, key = { it }) { category ->
                ListItem(
                    leadingContent = { Icon(categoryIconFor(category, snapshot.categoryIcons), null) },
                    headlineContent = { Text(category) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { rowMenu = category }) { Icon(Icons.Default.MoreVert, "Options for $category") }
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
        IconPickerDialog(snapshot.categoryIcons[category] ?: net.wastu.artha.ui.DEFAULT_CATEGORY_ICONS[category], onDismiss = { editingIcon = null }, onSelect = {
            viewModel.setCategoryIcon(category, it)
            editingIcon = null
        })
    }
    removing?.let { category ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove category?") },
            text = { Text("Saved payments keep their categories and notes.") },
            confirmButton = { TextButton(onClick = { viewModel.removeCustomCategory(category, type); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (resetting) AlertDialog(
        onDismissRequest = { resetting = false },
        title = { Text("Reset categories?") },
        text = { Text("Restores the default expense and income categories and icons. Saved payments keep their categories and notes.") },
        confirmButton = { TextButton(onClick = { viewModel.resetCategoriesToDefault(); resetting = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel") } },
    )
    if (adding) AddCategoryDialog(
        existing = categories,
        onDismiss = { adding = false },
        onAdd = { name, done -> viewModel.addCustomCategory(name, type) { result -> done(result.isSuccess) } },
    )
}

@Composable
internal fun AddCategoryDialog(existing: List<String>, onDismiss: () -> Unit, onAdd: (String, (Boolean) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val duplicate = existing.any { it.equals(name.trim(), ignoreCase = true) }
    fun submit() {
        if (saving || name.isBlank() || duplicate) return
        saving = true
        onAdd(name.trim()) { ok ->
            saving = false
            if (ok) onDismiss() else failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add category") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it; failed = false },
                label = { Text("Category name") },
                isError = duplicate || failed,
                supportingText = (if (duplicate) "Already exists" else if (failed) "Couldn't add category" else null)?.let { message -> { Text(message) } },
                singleLine = true, enabled = !saving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = { TextButton(enabled = !saving && name.isNotBlank() && !duplicate, onClick = ::submit) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
