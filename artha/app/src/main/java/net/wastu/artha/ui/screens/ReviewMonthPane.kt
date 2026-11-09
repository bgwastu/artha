package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.UNCATEGORIZED_LABEL
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.components.ConnectedChoiceRow
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.paymentRowAmount

/** Every merchant of a month with one-tap category chips; the whole group is categorized together. */
@Composable
internal fun WholeMonthPane(
    viewModel: ArthaViewModel,
    month: YearMonth,
    type: TransactionType,
    onType: (TransactionType) -> Unit,
    onOpenTransaction: (UnifiedTransaction) -> Unit,
) {
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val rows by viewModel.allTransactions.collectAsStateWithLifecycle()
    val groups by produceState(emptyList<MerchantGroup>(), rows, snapshot, month, type) {
        value = withContext(Dispatchers.Default) { buildMonthGroups(rows, snapshot, month, type) }
    }
    var expanded by rememberSaveable(month, type) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<MerchantGroup?>(null) }
    val categories = snapshot.categoriesFor(type)
    val topCategories = remember(rows, snapshot, type) { mostUsedCategories(rows, snapshot, type, categories) }
    val assign = { group: MerchantGroup, category: String ->
        group.transactions.forEach { viewModel.confirmCategory(it, category) {} }
    }

    LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ConnectedChoiceRow(
                options = listOf(TransactionType.EXPENSE, TransactionType.INCOME),
                selected = type,
                onSelect = onType,
                label = { if (it == TransactionType.INCOME) "Income" else "Expenses" },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (groups.isEmpty()) item {
            Text("Nothing in this month", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(groups, key = { txMerchantKey(it.transactions.first()) }) { group ->
            val id = txMerchantKey(group.transactions.first())
            MerchantGroupRow(
                group = group,
                chips = chipsFor(group, topCategories),
                snapshot = snapshot,
                expanded = expanded == id,
                onToggle = { expanded = if (expanded == id) null else id },
                onCategory = { assign(group, it) },
                onOther = { picking = group },
                onOpenTransaction = onOpenTransaction,
            )
        }
    }
    picking?.let { group ->
        CategoryChoiceDialog(
            categories = categories,
            selected = group.category.takeUnless { group.mixed },
            onSelect = { assign(group, it) },
            onDismiss = { picking = null },
        )
    }
}

/** The four categories used most for [type] in the ledger, padded with the defaults. */
private fun mostUsedCategories(
    rows: List<UnifiedTransaction>,
    snapshot: AnalysisSnapshot,
    type: TransactionType,
    categories: List<String>,
): List<String> {
    val used = rows.filter { it.type == type }.mapNotNull { snapshot.categories[it.key] ?: it.category }
        .filter { it in categories }.groupingBy { it }.eachCount()
    return (used.entries.sortedByDescending { it.value }.map { it.key } + categories).distinct().take(4)
}

/** The group's own category stays visible as a chip even when it isn't among the common ones. */
private fun chipsFor(group: MerchantGroup, topCategories: List<String>): List<String> {
    val own = group.category?.takeIf { !group.mixed && it !in topCategories }
    return if (own != null) listOf(own) + topCategories else topCategories
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MerchantGroupRow(
    group: MerchantGroup,
    chips: List<String>,
    snapshot: AnalysisSnapshot,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCategory: (String) -> Unit,
    onOther: () -> Unit,
    onOpenTransaction: (UnifiedTransaction) -> Unit,
) {
    Column {
        ListItem(
            modifier = Modifier.clickable(onClick = onToggle),
            headlineContent = { Text(group.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val status = when {
                    group.mixed -> "Mixed categories"
                    group.category != null -> group.category
                    else -> UNCATEGORIZED_LABEL
                }
                val count = group.transactions.size
                Text("$count ${if (count == 1) "payment" else "payments"}, $status")
            },
            trailingContent = { Text(formatMoney(Money(group.totalMinor))) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { category ->
                FilterChip(
                    selected = !group.mixed && group.category == category,
                    onClick = { onCategory(category) },
                    label = { Text(category) },
                )
            }
            AssistChip(onClick = onOther, label = { Text("Other") })
        }
        if (expanded) {
            val today = LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone))
            group.transactions.forEach { tx ->
                ListItem(
                    modifier = Modifier.clickable { onOpenTransaction(tx) }.padding(start = 16.dp),
                    headlineContent = { Text(formatReviewDate(tx.occurredAt, today)) },
                    supportingContent = { Text(snapshot.categories[tx.key] ?: tx.category ?: UNCATEGORIZED_LABEL) },
                    trailingContent = { Text(paymentRowAmount(tx)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
