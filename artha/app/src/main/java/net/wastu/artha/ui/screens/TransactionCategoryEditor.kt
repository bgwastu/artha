package net.wastu.artha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.wastu.artha.ui.categoryIconFor

/** Pick a category for a payment: search, a "use what I typed" shortcut, and the flat list. */
@Composable
internal fun CategoryPickerView(
    currentCategory: String,
    availableCategories: List<String>,
    categoryIcons: Map<String, String>,
    onSelectCategory: (String) -> Unit,
    saving: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val query = searchQuery.trim()
    val filteredCategories = remember(query, availableCategories) {
        if (query.isEmpty()) availableCategories else availableCategories.filter { it.contains(query, ignoreCase = true) }
    }
    val isExactMatch = availableCategories.any { it.equals(query, ignoreCase = true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryPickerHeader(saving, onBack)
        CategorySearchField(searchQuery, { searchQuery = it }, saving)
        error?.let {
            SaveFeedback(it, true)
            TextButton(onClick = onRetry, enabled = !saving) { Text("Retry") }
        }
        // A typed category can be assigned without changing the shared picker.
        if (query.isNotEmpty() && !isExactMatch) UseTypedCategoryRow(query, enabled = !saving, onClick = { onSelectCategory(query) })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (category in filteredCategories) {
                CategoryRow(
                    category = category,
                    icon = categoryIconFor(category, categoryIcons),
                    selected = category == currentCategory,
                    enabled = !saving,
                    onClick = { onSelectCategory(category) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CategoryPickerHeader(saving: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onBack, enabled = !saving) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            "Category",
            style = MaterialTheme.typography.titleLargeEmphasized,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
    if (saving) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CategorySearchField(query: String, onQueryChange: (String) -> Unit, saving: Boolean) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search categories") },
        enabled = !saving,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }, enabled = !saving) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UseTypedCategoryRow(typed: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.AddCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            "Use \"$typed\"",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A flat row (no cards on cards); the current category is tinted and checked. */
@Composable
private fun CategoryRow(category: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            category,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun TransactionNoteEditor(
    text: String, onTextChange: (String) -> Unit, saving: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Note", style = MaterialTheme.typography.titleLargeEmphasized)
        OutlinedTextField(
            value = text, onValueChange = onTextChange, label = { Text("Personal note") },
            enabled = !saving, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
        )
        Text("Notes add context. Your chosen category stays.", style = MaterialTheme.typography.bodySmall)
        error?.let { SaveFeedback(it, true) }
        if (text.isNotEmpty()) TextButton(onClick = { onTextChange("") }, enabled = !saving) { Text("Clear") }
        EditorActions(saving, onDismiss, onSave)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EditorActions(saving: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
        FilledTonalButton(onClick = onSave, enabled = !saving) { Text(if (saving) "Saving" else "Save") }
        if (saving) LoadingIndicator(Modifier.size(20.dp),)
    }
}

internal fun sureText(label: String, percent: Int?): String = if (percent == null) label else "$label, $percent% sure"
