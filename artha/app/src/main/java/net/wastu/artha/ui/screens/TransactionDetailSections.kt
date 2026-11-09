package net.wastu.artha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.categoryReasonText
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import org.json.JSONArray

@Composable
internal fun TripCard(serviceTier: String?, pickup: String, dropoff: String) {
    DetailCard("Trip") {
        serviceTier?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                .padding(12.dp),
        ) {
            TripStop(Icons.Default.MyLocation, "Pickup", "Pick-up", pickup, incomeColor())
            Box(
                Modifier
                    .padding(start = 9.dp, top = 4.dp, bottom = 4.dp)
                    .width(2.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            TripStop(Icons.Default.LocationOn, "Dropoff", "Drop-off", dropoff, expenseColor())
        }
    }
}

@Composable
private fun TripStop(icon: ImageVector, description: String, label: String, value: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun OrderItemsCard(items: JSONArray, shopLabel: String?, notes: String?) {
    DetailCard("Items") {
        shopLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val name = item.detailText("name") ?: continue
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    item.detailText("qty")?.let {
                        Text("Qty $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item.detailText("price")?.let { Text(itemPriceText(it), style = MaterialTheme.typography.titleSmall) }
            }
        }
        notes?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Column(Modifier.weight(1f)) {
                    Text("Order Note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun PaymentDetailsCard(rows: JSONArray) {
    DetailCard("Payment details") {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val label = row.detailText("title") ?: row.detailText("label")
            val value = row.detailText("value") ?: continue
            if (label != null) DetailRow(label, value) else Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TransactionTypeChips(current: TransactionType, overridden: Boolean, onSelect: (TransactionType) -> Unit, onReset: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            TransactionType.EXPENSE to "Expense",
            TransactionType.INCOME to "Income",
            TransactionType.TRANSFER to "Transfer",
        ).forEach { (type, label) ->
            FilterChip(selected = current == type, onClick = { if (current != type) onSelect(type) }, label = { Text(label) })
        }
        if (overridden) TextButton(onClick = onReset) { Text("Reset") }
    }
}

/** The one-line "where did this category come from" caption under the category row. */
private fun categoryStatusLine(source: CategorySource, confidence: Int?): (@Composable () -> Unit)? {
    val (text, color) = when (source) {
        CategorySource.MANUAL -> "Set by you" to CategoryTone.Muted
        CategorySource.AI -> sureText("Suggested by AI", confidence) to CategoryTone.Muted
        CategorySource.MERCHANT -> "Same merchant" to CategoryTone.Muted
        CategorySource.REVIEW -> sureText("Needs review", confidence) to CategoryTone.Attention
        CategorySource.FAILED -> "Couldn't categorize" to CategoryTone.Error
        CategorySource.PENDING -> "Waiting for AI" to CategoryTone.Muted
        CategorySource.NONE -> return null
    }
    return {
        val tint = when (color) {
            CategoryTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
            CategoryTone.Attention -> MaterialTheme.colorScheme.tertiary
            CategoryTone.Error -> MaterialTheme.colorScheme.error
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

private enum class CategoryTone { Muted, Attention, Error }

/** Category row, the AI reasoning, and the re-categorize / confirm actions for one payment. */
@Composable
internal fun CategorySection(
    record: UnifiedTransaction,
    viewModel: ArthaViewModel?,
    snapshot: AnalysisSnapshot?,
    categorySource: CategorySource,
    currentCategory: String,
    isBusy: Boolean,
    busyLabel: String?,
    hasRecords: Boolean,
    snackbarHostState: SnackbarHostState,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showReplaceManualDialog by remember(record.key) { mutableStateOf(false) }
    Column(Modifier.padding(top = if (hasRecords) SectionGap else 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailEditRow(
            label = "Category", value = currentCategory,
            supportingContent = if (isBusy && busyLabel != null) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LoadingIndicator(modifier = Modifier.size(13.dp))
                        Text(busyLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else categoryStatusLine(categorySource, snapshot?.aiRecords?.get(record.key)?.confidence),
            icon = categoryIconFor(currentCategory, snapshot?.categoryIcons.orEmpty()),
            enabled = viewModel?.analysisStore != null && !isBusy,
            onClick = onEdit,
        )
        if (snapshot == null) return@Column
        val reason = categoryReasonText(snapshot, record.key)
        if (reason != null && !isBusy) Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 36.dp),
        )
        FlowRow(
            modifier = Modifier.padding(start = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {
                    if (categorySource == CategorySource.MANUAL) showReplaceManualDialog = true
                    else viewModel?.recategorize(RecategorizeScope.Payment(record.key))
                },
                enabled = !isBusy,
                label = { Text(if (isBusy) "Categorizing" else "Re-categorize") },
                leadingIcon = {
                    if (isBusy) LoadingIndicator(modifier = Modifier.size(AssistChipDefaults.IconSize))
                    else Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                },
            )
            if (categorySource == CategorySource.REVIEW && !isBusy) {
                SuggestionChip(
                    onClick = {
                        viewModel?.confirmCategory(record, currentCategory) { result ->
                            if (result.isSuccess) scope.launch { snackbarHostState.showSnackbar("Category saved") }
                        }
                    },
                    label = { Text("Looks right") },
                    icon = { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(SuggestionChipDefaults.IconSize)) },
                )
            }
        }
        if (showReplaceManualDialog) {
            AlertDialog(
                onDismissRequest = { showReplaceManualDialog = false },
                title = { Text("Replace your pick?") },
                text = { Text("AI will choose a new category for this payment.") },
                confirmButton = {
                    TextButton(onClick = {
                        showReplaceManualDialog = false
                        viewModel?.recategorize(RecategorizeScope.Payment(record.key, replaceManual = true))
                    }) { Text("Replace") }
                },
                dismissButton = { TextButton(onClick = { showReplaceManualDialog = false }) { Text("Cancel") } },
            )
        }
    }
}
