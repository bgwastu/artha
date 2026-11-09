package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.LedgerState
import net.wastu.artha.ui.components.collectOrDefault
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.transactionHumanLine

private data class SearchEntry(val transaction: UnifiedTransaction, val fields: List<String>)
private data class SearchResults(val query: String, val transactions: List<UnifiedTransaction>)

/** Search over every saved payment, with the same source / method / date filters as the main list. */
@Composable
fun SearchTransactionsScreen(
    ledgerState: LedgerState,
    onBack: () -> Unit,
    onTransaction: (UnifiedTransaction) -> Unit,
    viewModel: ArthaViewModel? = null,
    onRetry: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyword = query.trim()
    var filters by rememberSaveable(stateSaver = TransactionFiltersSaver) { mutableStateOf(TransactionFilters()) }
    var firstDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    val dateRange = if (firstDay != null || lastDay != null) firstDay to lastDay else null
    val categoryProgress by viewModel?.categoryProgress.collectOrDefault(CategoryProgress())
    val allTransactions by viewModel?.allTransactions?.collectAsStateWithLifecycle()
        ?: remember(ledgerState) { mutableStateOf((ledgerState as? LedgerState.Ready)?.transactions.orEmpty()) }
    val effectiveFilters = remember(filters) { filters.copy(direction = null) }
    val transactions = remember(allTransactions, effectiveFilters, dateRange) { transactionSearchScope(allTransactions, effectiveFilters, dateRange) }
    fun clearFilters() { filters = TransactionFilters(); firstDay = null; lastDay = null }
    val activeFilters = buildList {
        effectiveFilters.type?.let { add(ActiveTransactionFilter(it.label) { filters = filters.copy(type = null) }) }
        effectiveFilters.provider?.let { add(ActiveTransactionFilter(it.displayName) { filters = filters.copy(provider = null) }) }
        dateRange?.let { add(ActiveTransactionFilter(periodLabel(it, LocalDate.now(AccountingTime.clock))) { firstDay = null; lastDay = null }) }
    }
    val results by rememberSearchResults(transactions, keyword)
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch = { keyboard?.hide(); onBack() }
    BackHandler(onBack = closeSearch)

    Scaffold(
        topBar = {
            SearchTopBar(
                query = query,
                onQueryChange = { query = it },
                activeFilterCount = activeFilters.size,
                onClose = closeSearch,
                onOpenFilter = { showFilter = true },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val current = results?.takeIf { it.query == keyword }
            when {
                ledgerState == LedgerState.Failed -> LedgerFeedback(title = "Couldn't load transactions", onRetry = onRetry)
                ledgerState == LedgerState.Loading || current == null -> LedgerFeedback(loading = true)
                else -> TransactionList(
                    transactions = current.transactions,
                    onTransaction = onTransaction,
                    highlightQuery = keyword,
                    resetKey = listOf(keyword, effectiveFilters, dateRange),
                    activeFilters = activeFilters,
                    onClearFilters = ::clearFilters,
                    resultCount = current.transactions.size,
                    categoryProgress = categoryProgress,
                    periodSummary = searchPeriodSummary(current.transactions, dateRange, keyword, activeFilters),
                    onFilterDates = { start, end -> firstDay = start; lastDay = end },
                    activeRange = dateRange,
                )
            }
        }
    }
    if (showFilter) TransactionFilterSheet(
        transactions = allTransactions,
        filters = effectiveFilters, range = dateRange, category = null, providers = Provider.entries,
        onDismiss = { showFilter = false },
        onApply = { updated, dates, _ ->
            filters = updated.copy(direction = null)
            firstDay = dates?.first
            lastDay = dates?.second
            showFilter = false
        },
        viewModel = viewModel,
    )
}

/**
 * Builds the search index off the main thread, then filters it by [keyword]. A result is tagged with the
 * keyword it answers, so the screen can show a spinner instead of results for a stale query.
 */
@Composable
private fun rememberSearchResults(transactions: List<UnifiedTransaction>, keyword: String): State<SearchResults?> {
    val index = produceState<List<SearchEntry>?>(null, transactions) {
        value = null
        value = withContext(Dispatchers.Default) {
            transactions.map { transaction ->
                ensureActive()
                SearchEntry(transaction, searchFields(transaction))
            }
        }
    }
    return produceState<SearchResults?>(null, index.value, keyword) {
        value = null
        val entries = index.value ?: return@produceState
        value = withContext(Dispatchers.Default) {
            SearchResults(keyword, entries.filter { entry ->
                ensureActive()
                keyword.isEmpty() || entry.fields.any { it.contains(keyword, ignoreCase = true) }
            }.map { it.transaction })
        }
    }
}

private fun searchFields(transaction: UnifiedTransaction): List<String> = listOf(
    transactionHumanLine(transaction),
    transaction.description,
    transaction.provider.displayName,
    transaction.cardLast4.orEmpty(),
    transaction.userNote.orEmpty(),
    transaction.category.orEmpty(),
    if (transaction.isQris) "QRIS" else "",
    formatMoney(transaction.money),
) + payloadTextValues(transaction.rawPayload)

/** A summary line while searching or filtering; labelled with the keyword and filters when the date range gives none. */
private fun searchPeriodSummary(
    results: List<UnifiedTransaction>,
    dateRange: Pair<Long?, Long?>?,
    keyword: String,
    activeFilters: List<ActiveTransactionFilter>,
): PeriodSummary? {
    if (activeFilters.isEmpty() && keyword.isEmpty()) return null
    val summary = periodSummary(results, dateRange, LocalDate.now(AccountingTime.clock), null)
    if (summary.label.isNotBlank()) return summary
    val parts = listOfNotNull(keyword.takeIf(String::isNotEmpty)?.let { "\"$it\"" }) + activeFilters.map(ActiveTransactionFilter::label)
    return summary.copy(label = parts.joinToString(", "))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    activeFilterCount: Int,
    onClose: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val searchColors = SearchBarDefaults.colors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = SearchBarDefaults.inputFieldShape,
        color = searchColors.containerColor,
    ) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = { keyboard?.hide() },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.focusRequester(focusRequester).semantics { contentDescription = "Search payments" },
            placeholder = { Text("Search payments") },
            leadingIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                    IconButton(
                        onClick = { keyboard?.hide(); focusManager.clearFocus(); onOpenFilter() },
                        modifier = Modifier.semantics {
                            stateDescription = if (activeFilterCount == 0) "No filters" else "$activeFilterCount filters applied"
                        },
                    ) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Filter",
                            tint = if (activeFilterCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            colors = searchColors.inputFieldColors,
        )
    }
}
