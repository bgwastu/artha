package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.LedgerState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(
    state: LedgerState,
    enabledProviders: Set<Provider> = Provider.entries.toSet(),
    onTransaction: (UnifiedTransaction) -> Unit,
    onRetry: () -> Unit,
    showFilter: Boolean = false,
    onDismissFilter: () -> Unit = {},
    viewModel: ArthaViewModel? = null,
    filters: TransactionFilters = TransactionFilters(),
    onFiltersChanged: (TransactionFilters) -> Unit = {},
) {
    val dateRange by viewModel?.dateRangeFilter?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val refreshing by viewModel?.refreshing?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val category by viewModel?.categoryFilter?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val categoryProgress by viewModel?.categoryProgress?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(CategoryProgress()) }
    val showCategory = true
    // Category drill-down is all-source confirmed spending; prior browsing choices stay intact for Back.
    val effectiveFilters = if (category == null) filters.copy(direction = null) else TransactionFilters()
    val visibleProviders = Provider.entries.filter { it in enabledProviders }
    LaunchedEffect(enabledProviders, filters.provider) {
        if (filters.provider != null && filters.provider !in enabledProviders) onFiltersChanged(filters.copy(provider = null))
    }
    fun clearAll() {
        onFiltersChanged(TransactionFilters())
        viewModel?.clearDateRangeFilter()
        viewModel?.clearCategoryFilter()
    }
    val active = buildList {
        category?.let { add(ActiveTransactionFilter(it) { viewModel?.clearCategoryFilter() }) }
        effectiveFilters.type?.let { add(ActiveTransactionFilter(it.label) { onFiltersChanged(filters.copy(type = null)) }) }
        effectiveFilters.provider?.let { add(ActiveTransactionFilter(it.displayName) { onFiltersChanged(filters.copy(provider = null)) }) }
        dateRange?.let { add(ActiveTransactionFilter(periodLabel(it, LocalDate.now(AccountingTime.clock))) { viewModel?.clearDateRangeFilter() }) }
    }
    val today = remember { LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone)) }
    val typeFilter by viewModel?.typeFilter?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val allDebitTransactions = remember(state) { (state as? LedgerState.Ready)?.transactions.orEmpty() }
    val filteredTransactions = remember(allDebitTransactions, effectiveFilters, typeFilter, category) {
        allDebitTransactions.filter { effectiveFilters.matches(it) && (category != null || typeFilter == null || it.type == typeFilter) }
    }
    val periodSummary = if (active.isNotEmpty()) {
        remember(filteredTransactions, dateRange, today, category, active.map { it.label }) {
            periodSummary(filteredTransactions, dateRange, today, category)
                .let { if (it.label.isBlank()) it.copy(label = active.joinToString(", ") { f -> f.label }) else it }
        }
    } else null

    when (state) {
        LedgerState.Loading -> LedgerFeedback(loading = true)
        LedgerState.Failed -> LedgerFeedback(title = "Couldn't load transactions", onRetry = onRetry)
        is LedgerState.Ready -> Column {
          if (category == null && viewModel != null) TypeChips(typeFilter, viewModel::setTypeFilter)
          TransactionList(
            transactions = filteredTransactions,
            onTransaction = onTransaction, resetKey = listOf(effectiveFilters, dateRange, category),
            activeFilters = active, onClearFilters = ::clearAll,
            emptyTitle = if (active.isNotEmpty()) "No matching transactions" else "No saved payments",
            isPopulating = refreshing,
            showCategory = showCategory,
            categoryProgress = categoryProgress,
            periodSummary = periodSummary,
            onFilterDates = viewModel?.let { vm -> { start, end -> vm.setDateRangeFilter(start, end) } },
            activeRange = dateRange,
          )
        }
    }
    if (showFilter) {
        TransactionFilterSheet(
            transactions = allDebitTransactions,
            filters = effectiveFilters, range = dateRange, category = category,
            providers = visibleProviders,
            onDismiss = onDismissFilter,
            onApply = { updated, dates, clearCategory ->
                if (category == null || clearCategory) onFiltersChanged(updated.copy(direction = null))
                if (clearCategory) viewModel?.clearCategoryFilter()
                if (dates == null) viewModel?.clearDateRangeFilter() else viewModel?.setDateRangeFilter(dates.first, dates.second)
                onDismissFilter()
            },
            viewModel = viewModel,
        )
    }
}
