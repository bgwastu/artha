package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.transactionHumanLine
import net.wastu.artha.ui.components.collectOrDefault

private data class SearchEntry(val transaction: UnifiedTransaction, val fields: List<String>)
private data class SearchResults(val query: String, val transactions: List<UnifiedTransaction>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var filters by rememberSaveable(stateSaver = TransactionFiltersSaver) { mutableStateOf(TransactionFilters()) }
    var firstDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    val dateRange = if (firstDay != null || lastDay != null) firstDay to lastDay else null
    val showCategory = true
    val categoryProgress by viewModel?.categoryProgress.collectOrDefault(CategoryProgress())
    val allTransactions by viewModel?.allTransactions?.collectAsStateWithLifecycle()
        ?: remember(ledgerState) { mutableStateOf((ledgerState as? LedgerState.Ready)?.transactions.orEmpty()) }
    val debitTransactions = allTransactions
    val effectiveFilters = remember(filters) { filters.copy(direction = null) }
    val transactions = remember(debitTransactions, effectiveFilters, dateRange) { transactionSearchScope(debitTransactions, effectiveFilters, dateRange) }
    fun clearFilters() { filters = TransactionFilters(); firstDay = null; lastDay = null }
    val activeFilters = buildList {
        effectiveFilters.type?.let { add(ActiveTransactionFilter(it.label) { filters = filters.copy(type = null) }) }
        effectiveFilters.provider?.let { add(ActiveTransactionFilter(it.displayName) { filters = filters.copy(provider = null) }) }
        dateRange?.let { add(ActiveTransactionFilter(periodLabel(it, LocalDate.now(AccountingTime.clock))) { firstDay = null; lastDay = null }) }
    }
    // Build the search index off the main thread, outside composition and scrolling.
    val index by produceState<List<SearchEntry>?>(null, transactions, showCategory) {
        value = null
        value = withContext(Dispatchers.Default) {
            transactions.map { transaction ->
                ensureActive()
                SearchEntry(transaction, listOf(
                    transactionHumanLine(transaction),
                    transaction.description,
                    transaction.provider.displayName,
                    transaction.cardLast4.orEmpty(),
                    transaction.userNote.orEmpty(),
                    if (showCategory) transaction.category.orEmpty() else "",
                    if (transaction.isQris) "QRIS" else "",
                    formatMoney(transaction.money),
                ) + payloadTextValues(transaction.rawPayload))
            }
        }
    }
    val results by produceState<SearchResults?>(null, index, keyword) {
        value = null
        val entries = index ?: return@produceState
        value = withContext(Dispatchers.Default) {
            SearchResults(keyword, entries.filter { entry ->
                ensureActive()
                keyword.isEmpty() || entry.fields.any { it.contains(keyword, ignoreCase = true) }
            }.map { it.transaction })
        }
    }
    val closeSearch = { keyboard?.hide(); onBack() }
    BackHandler(onBack = closeSearch)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Scaffold(
        topBar = {
            val searchColors = SearchBarDefaults.colors()
            Surface(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 16.dp, vertical = 8.dp),
                shape = SearchBarDefaults.inputFieldShape,
                color = searchColors.containerColor,
            ) {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { keyboard?.hide() },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.focusRequester(focusRequester).semantics { contentDescription = "Search payments" },
                    placeholder = { Text("Search payments") },
                    leadingIcon = {
                        IconButton(onClick = closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    trailingIcon = {
                        Row {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                            IconButton(
                                onClick = { keyboard?.hide(); focusManager.clearFocus(); showFilter = true },
                                modifier = Modifier.semantics { stateDescription = if (activeFilters.isEmpty()) "No filters" else "${activeFilters.size} filters applied" },
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter",
                                    tint = if (activeFilters.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    colors = searchColors.inputFieldColors,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when {
                ledgerState == LedgerState.Failed -> LedgerFeedback(title = "Couldn't load transactions", onRetry = onRetry)
                ledgerState == LedgerState.Loading || results == null || results?.query != keyword ->
                    LedgerFeedback(loading = true)
                else -> TransactionList(
                    transactions = results!!.transactions,
                    onTransaction = onTransaction,
                    highlightQuery = keyword,
                    resetKey = listOf(keyword, effectiveFilters, dateRange),
                    activeFilters = activeFilters,
                    onClearFilters = ::clearFilters,
                    resultCount = results!!.transactions.size,
                    showCategory = showCategory,
                    categoryProgress = categoryProgress,
                    periodSummary = if (activeFilters.isNotEmpty() || keyword.isNotEmpty()) {
                        periodSummary(results!!.transactions, dateRange, LocalDate.now(AccountingTime.clock), null).let {
                            if (it.label.isNotBlank()) it else it.copy(label = (listOfNotNull(keyword.takeIf(String::isNotEmpty)?.let { k -> "\"$k\"" }) +
                                activeFilters.map(ActiveTransactionFilter::label)).joinToString(", "))
                        }
                    } else null,
                    onFilterDates = { start, end -> firstDay = start; lastDay = end },
                    activeRange = dateRange,
                )
            }
        }
    }
    if (showFilter) TransactionFilterSheet(
        transactions = debitTransactions,
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
