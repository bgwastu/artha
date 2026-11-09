package net.wastu.artha.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.ui.ArthaViewModel

@Composable
internal fun TypeChips(selected: TransactionType?, onSelect: (TransactionType?) -> Unit) {
    val options = listOf<Pair<String, TransactionType?>>(
        "All" to null,
        "Expenses" to TransactionType.EXPENSE,
        "Income" to TransactionType.INCOME,
        "Transfers" to TransactionType.TRANSFER,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, type) -> FilterChip(selected = selected == type, onClick = { onSelect(type) }, label = { Text(label) }) }
    }
}

internal data class ActiveTransactionFilter(val label: String, val clear: () -> Unit)

/**
 * Method, source and date filters, staged in a draft until Apply. A category drill-down replaces the
 * method and source options until it is removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionFilterSheet(
    transactions: List<UnifiedTransaction>,
    filters: TransactionFilters, range: Pair<Long?, Long?>?, category: String?, providers: List<Provider>,
    onDismiss: () -> Unit,
    onApply: (TransactionFilters, Pair<Long?, Long?>?, Boolean) -> Unit,
    viewModel: ArthaViewModel? = null,
) {
    var draft by remember { mutableStateOf(filters) }
    var dates by remember { mutableStateOf(range) }
    var clearCategory by remember { mutableStateOf(false) }
    var pickingDates by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone)) }
    val selectedPeriod = if (dates == null) TransactionPeriod.ALL else
        TransactionPeriod.entries.firstOrNull { it != TransactionPeriod.CUSTOM && it.range(today) == dates } ?: TransactionPeriod.CUSTOM

    // The date picker replaces the filter sheet; changes remain staged until the sheet's Apply.
    if (pickingDates) {
        DateRangePickerDialog(
            initial = dates, today = today,
            onDismiss = { pickingDates = false },
            onConfirm = { startMillis, endMillis -> dates = startMillis to endMillis; pickingDates = false },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = TransactionFilters(); dates = null; clearCategory = true }) { Text("Reset") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (category != null && !clearCategory) {
                    CategoryFilterRow(
                        category = category,
                        onRemove = { clearCategory = true; draft = filters.copy(direction = null) },
                        onRecategorize = viewModel?.let { vm ->
                            {
                                vm.recategorize(RecategorizeScope.CategoryInMonth(category, vm.selectedMonth.value))
                                onDismiss()
                            }
                        },
                    )
                }
                if (category == null || clearCategory) {
                    FilterOptions("Method", listOf(null) + PaymentMethod.entries, draft.type, { it?.label ?: "All" }) { draft = draft.copy(type = it) }
                    FilterOptions("Source", listOf(null) + providers, draft.provider, { it?.displayName ?: "All" }) { draft = draft.copy(provider = it) }
                }
                DateFilterOptions(
                    selected = selectedPeriod,
                    customRange = dates.takeIf { selectedPeriod == TransactionPeriod.CUSTOM },
                    transactions = transactions,
                    onSelect = { period -> if (period == TransactionPeriod.CUSTOM) pickingDates = true else dates = period.range(today) },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                FilledTonalButton(onClick = { onApply(draft.copy(direction = null), dates, clearCategory) }, modifier = Modifier.weight(1f)) { Text("Apply") }
            }
        }
    }
}

/** The category drill-down as a removable chip, with a Re-categorize shortcut when a view model is available. */
@Composable
private fun CategoryFilterRow(category: String, onRemove: () -> Unit, onRecategorize: (() -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = true,
            onClick = onRemove,
            label = { Text(category) },
            trailingIcon = { Icon(Icons.Default.Clear, "Remove category filter") },
        )
        if (onRecategorize != null) {
            AssistChip(
                onClick = onRecategorize,
                label = { Text("Re-categorize") },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
            )
        }
    }
}

/** Period chips; a custom range also shows what it adds up to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateFilterOptions(
    selected: TransactionPeriod,
    customRange: Pair<Long?, Long?>?,
    transactions: List<UnifiedTransaction>,
    onSelect: (TransactionPeriod) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Dates", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransactionPeriod.entries.forEach { period ->
                FilterChip(selected = selected == period, onClick = { onSelect(period) }, label = { Text(period.label) })
            }
        }
        if (customRange?.first != null && customRange.second != null) CustomRangeTotal(transactions, customRange)
    }
}

@Composable
private fun CustomRangeTotal(transactions: List<UnifiedTransaction>, range: Pair<Long?, Long?>) {
    val (total, count) = remember(transactions, range) {
        val start = range.first
        val endExclusive = range.second?.let(AccountingTime::dayAfter)
        val spending = transactions.filter {
            val millis = it.occurredAt.toEpochMilli()
            it.isExpense && (start == null || millis >= start) && (endExclusive == null || millis < endExclusive)
        }
        spending.sumOf { it.money.amountMinor } to spending.size
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = formatSpendingTotal(total), style = MaterialTheme.typography.titleMedium)
        Text(
            text = "$count ${if (count == 1) "payment" else "payments"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FilterOptions(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(label(option)) }) }
        }
    }
}
