package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.ui.categoryIconFor

/**
 * One payment of the one-by-one review: the AI suggestion and alternatives to pick from, an optional
 * "apply to similar" toggle and an optional note. Selection, note and dialog state live here; storage
 * and session changes are reported through the callbacks.
 */
@Composable
internal fun ReviewItemPane(
    item: ReviewItem,
    session: ReviewSession,
    snapshot: AnalysisSnapshot,
    similarCount: Int,
    onOpenTransaction: () -> Unit,
    onApply: (category: String, applyToSimilar: Boolean) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onSaveNote: (String) -> Unit,
) {
    var selected by remember(item.tx.key, session.cursor) { mutableStateOf(session.selection(item.aiCategory)) }
    var applySimilar by remember(item.tx.key) { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }

    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReviewProgress(session)
        ReviewHeader(item, onOpenTransaction)
        item.reason?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReviewOptions(item, selected, snapshot, onSelect = { selected = it }, onOther = { picking = true })
        if (similarCount > 0) {
            ListItem(
                modifier = Modifier.toggleable(value = applySimilar, role = Role.Checkbox, onValueChange = { applySimilar = it }),
                leadingContent = { Checkbox(checked = applySimilar, onCheckedChange = null) },
                headlineContent = { Text("Apply to $similarCount similar") },
                supportingContent = { Text(item.merchant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        ReviewNote(item, onSaveNote)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = session.canGoBack, onClick = onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSkip) { Text("Skip") }
            Button(enabled = selected.isNotBlank(), onClick = { onApply(selected, applySimilar) }) {
                Text(if (session.cursor >= session.total - 1) "Confirm" else "Next")
            }
        }
    }

    if (picking) CategoryChoiceDialog(
        categories = snapshot.categoriesFor(item.tx.type),
        selected = selected,
        onSelect = { selected = it },
        onDismiss = { picking = false },
    )
}

@Composable
private fun ColumnScope.ReviewProgress(session: ReviewSession) {
    LinearWavyProgressIndicator(
        progress = { if (session.total == 0) 0f else (session.cursor.toFloat() / session.total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("${session.position} of ${session.total}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReviewHeader(item: ReviewItem, onOpenTransaction: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpenTransaction), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                item.merchant,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${item.amountText}  ${item.dateText}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, "View transaction", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The suggestion, its alternatives and the current pick as one radio group, plus the full category picker. */
@Composable
private fun ReviewOptions(
    item: ReviewItem,
    selected: String,
    snapshot: AnalysisSnapshot,
    onSelect: (String) -> Unit,
    onOther: () -> Unit,
) {
    val options = (listOf(item.aiCategory) + item.alternatives + listOfNotNull(selected.takeIf { it.isNotBlank() }))
        .filter { it.isNotBlank() }.distinct()
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            ListItem(
                modifier = Modifier.selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) }),
                leadingContent = { RadioButton(selected = selected == option, onClick = null) },
                headlineContent = { Text(option) },
                supportingContent = if (option == item.aiCategory) {
                    { Text(snapshot.aiRecords[item.tx.key]?.confidence?.let { "AI suggestion, $it% sure" } ?: "AI suggestion") }
                } else null,
                trailingContent = { Icon(categoryIconFor(option, snapshot.categoryIcons), null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        ListItem(
            modifier = Modifier.clickable(onClick = onOther),
            headlineContent = { Text("Other category", color = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Collapsed to a button until opened; saving the note hands the payment back to the categorizer. */
@Composable
private fun ColumnScope.ReviewNote(item: ReviewItem, onSave: (String) -> Unit) {
    var draft by remember(item.tx.key) { mutableStateOf(item.tx.userNote.orEmpty()) }
    var open by remember(item.tx.key) { mutableStateOf(!item.tx.userNote.isNullOrBlank()) }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.Default.EditNote, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (draft.isBlank()) "Add note for AI" else "Edit note")
        }
        return
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Note for AI") },
        supportingText = { Text("Saving the note re-runs AI categorization") },
        minLines = 1,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    if (draft.isNotBlank()) {
        TextButton(onClick = { onSave(draft.trim()) }, modifier = Modifier.align(Alignment.End)) { Text("Save and re-analyze") }
    }
}

/** Single-choice category list; choosing one closes the dialog. */
@Composable
internal fun CategoryChoiceDialog(
    categories: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select category") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
                categories.forEach { category ->
                    ListItem(
                        headlineContent = { Text(category) },
                        leadingContent = { RadioButton(selected = category == selected, onClick = null) },
                        modifier = Modifier.selectable(
                            selected = category == selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(category); onDismiss() },
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
