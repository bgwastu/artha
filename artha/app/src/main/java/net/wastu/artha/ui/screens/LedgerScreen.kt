package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import net.wastu.artha.ui.theme.incomeColor
import net.wastu.artha.ui.theme.expenseColor
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePickerDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import net.wastu.artha.ui.ArthaViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.LedgerState
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.transactionHumanLine
import net.wastu.artha.ui.transactionTags
import net.wastu.artha.ui.TransactionTagKind
import net.wastu.artha.ui.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import net.wastu.artha.core.model.AccountingTime
import java.time.Instant

data class TransactionFilters(
    val provider: Provider? = null,
    val direction: Direction? = null,
) {
    fun matches(transaction: UnifiedTransaction): Boolean =
        transaction.isConfirmed &&
        (provider == null || transaction.provider == provider) &&
        (direction == null || transaction.direction == direction)
}

internal fun transactionSearchScope(
    allSaved: List<UnifiedTransaction>,
    filters: TransactionFilters = TransactionFilters(),
    range: Pair<Long?, Long?>? = null,
): List<UnifiedTransaction> {
    val start = range?.first
    val endExclusive = range?.second?.let(AccountingTime::dayAfter)
    return allSaved.filter {
        val millis = it.occurredAt.toEpochMilli()
        filters.matches(it) && (start == null || millis >= start) && (endExclusive == null || millis < endExclusive)
    }
}

val TransactionFiltersSaver = listSaver<TransactionFilters, String>(
    save = { listOf(it.provider?.name.orEmpty(), it.direction?.name.orEmpty()) },
    // Earlier bundles have a third activity item. Ignore it while restoring source/direction.
    restore = { TransactionFilters(it.getOrNull(0)?.takeIf(String::isNotBlank)?.let(Provider::valueOf),
        it.getOrNull(1)?.takeIf(String::isNotBlank)?.let(Direction::valueOf)) },
)

enum class TransactionPeriod(val label: String) {
    ALL("All time"), THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_30_DAYS("Last 30 days"), CUSTOM("Custom dates");
    fun range(today: LocalDate): Pair<Long?, Long?>? {
        val days = when (this) {
            ALL, CUSTOM -> return null
            THIS_MONTH -> today.withDayOfMonth(1) to today
            LAST_MONTH -> today.minusMonths(1).let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
            LAST_30_DAYS -> today.minusDays(29) to today
        }
        return days.first.atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli() to
            days.second.atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli()
    }
}

private fun rangeLabel(range: Pair<Long?, Long?>): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    fun date(value: Long?) = value?.let { Instant.ofEpochMilli(it).atZone(AccountingTime.zone).toLocalDate().format(formatter) }
    val start = date(range.first)
    val end = date(range.second)
    return if (start != null && end != null && start != end) "$start to $end" else start ?: end ?: "Dates"
}

private data class ActiveTransactionFilter(val label: String, val clear: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(
    state: LedgerState,
    enabledProviders: Set<Provider> = Provider.entries.toSet(),
    onTransaction: (UnifiedTransaction) -> Unit,
    onRetry: () -> Unit,
    onImportHistory: (() -> Unit)? = null,
    showFilter: Boolean = false,
    onDismissFilter: () -> Unit = {},
    viewModel: ArthaViewModel? = null,
    filters: TransactionFilters = TransactionFilters(),
    onFiltersChanged: (TransactionFilters) -> Unit = {},
) {
    val dateRange by viewModel?.dateRangeFilter?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val category by viewModel?.categoryFilter?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val showCategory = analysisShown(viewModel)
    // Category drill-down is all-source confirmed spending; prior browsing choices stay intact for Back.
    val effectiveFilters = if (category == null) filters else TransactionFilters(direction = Direction.DEBIT)
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
        effectiveFilters.provider?.let { add(ActiveTransactionFilter(it.displayName) { onFiltersChanged(filters.copy(provider = null)) }) }
        if (category == null) effectiveFilters.direction?.let {
            add(ActiveTransactionFilter(if (it == Direction.CREDIT) "Incoming" else "Outgoing") { onFiltersChanged(filters.copy(direction = null)) })
        }
        dateRange?.let { add(ActiveTransactionFilter(rangeLabel(it)) { viewModel?.clearDateRangeFilter() }) }
    }
    when (state) {
        LedgerState.Loading -> LedgerFeedback(loading = true)
        LedgerState.Failed -> LedgerFeedback(title = "Couldn't load transactions", onRetry = onRetry)
        is LedgerState.Ready -> TransactionList(
            transactions = remember(state.transactions, effectiveFilters) { state.transactions.filter(effectiveFilters::matches) },
            onTransaction = onTransaction, resetKey = listOf(effectiveFilters, dateRange, category),
            activeFilters = active, onClearFilters = ::clearAll,
            emptyTitle = if (active.isNotEmpty()) "No matching transactions" else "No saved payments",
            onImportHistory = if (active.isEmpty()) onImportHistory else null,
            showCategory = showCategory,
        )
    }
    if (showFilter) {
        TransactionFilterSheet(
            filters = effectiveFilters, range = dateRange, category = category,
            providers = visibleProviders,
            onDismiss = onDismissFilter,
            onApply = { updated, dates, clearCategory ->
                if (category == null || clearCategory) onFiltersChanged(updated)
                if (clearCategory) viewModel?.clearCategoryFilter()
                if (dates == null) viewModel?.clearDateRangeFilter() else viewModel?.setDateRangeFilter(dates.first, dates.second)
                onDismissFilter()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TransactionFilterSheet(
    filters: TransactionFilters, range: Pair<Long?, Long?>?, category: String?, providers: List<Provider>,
    onDismiss: () -> Unit,
    onApply: (TransactionFilters, Pair<Long?, Long?>?, Boolean) -> Unit,
) {
    var draft by remember { mutableStateOf(filters) }
    var dates by remember { mutableStateOf(range) }
    var clearCategory by remember { mutableStateOf(false) }
    var pickingDates by remember { mutableStateOf(false) }
    val today = LocalDate.now(AccountingTime.clock)
    val selectedPeriod = if (dates == null) TransactionPeriod.ALL else
        TransactionPeriod.entries.firstOrNull { it != TransactionPeriod.CUSTOM && it.range(today) == dates } ?: TransactionPeriod.CUSTOM
    // The date picker replaces the filter sheet; changes remain staged until the sheet's Apply.
    if (pickingDates) {
        fun pickerDay(value: Long?) = value?.let {
            Instant.ofEpochMilli(it).atZone(AccountingTime.zone).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        }
        val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = pickerDay(dates?.first),
            initialSelectedEndDateMillis = pickerDay(dates?.second))
        DatePickerDialog(onDismissRequest = { pickingDates = false },
            confirmButton = {
                TextButton(enabled = picker.selectedStartDateMillis != null, onClick = {
                    val start = picker.selectedStartDateMillis!!
                    val end = picker.selectedEndDateMillis ?: start
                    dates = AccountingTime.pickerDay(start) to AccountingTime.pickerDay(end)
                    pickingDates = false
                }) { Text("Done") }
            }, dismissButton = { TextButton(onClick = { pickingDates = false }) { Text("Cancel") } }) {
            DateRangePicker(picker, modifier = Modifier.heightIn(max = 520.dp))
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { draft = TransactionFilters(); dates = null; clearCategory = true }) { Text("Reset") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (category != null && !clearCategory) {
                        FilterChip(selected = true, onClick = { clearCategory = true; draft = filters.copy(direction = null) },
                            label = { Text(category) }, trailingIcon = { Icon(Icons.Default.Clear, "Remove category filter") })
                    }
                    if (category == null || clearCategory) {
                        FilterOptions("Direction", listOf(null, Direction.DEBIT, Direction.CREDIT), draft.direction,
                            { when (it) { Direction.DEBIT -> "Outgoing"; Direction.CREDIT -> "Incoming"; null -> "All" } }) { draft = draft.copy(direction = it) }
                        FilterOptions("Source", listOf(null) + providers, draft.provider, { it?.displayName ?: "All" }) { draft = draft.copy(provider = it) }
                    }
                    Column(Modifier.selectableGroup()) {
                        Text("Dates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        TransactionPeriod.entries.forEach { period ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selectedPeriod == period, role = Role.RadioButton, onClick = {
                                if (period == TransactionPeriod.CUSTOM) pickingDates = true else dates = period.range(today)
                            }).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedPeriod == period, onClick = null)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(period.label, style = MaterialTheme.typography.bodyLarge)
                                    if (period == TransactionPeriod.CUSTOM && selectedPeriod == period) {
                                        Text(rangeLabel(dates!!), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    FilledTonalButton(onClick = { onApply(draft, dates, clearCategory) }, modifier = Modifier.weight(1f)) { Text("Apply") }
                }
            }
        }
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
    val showCategory = analysisShown(viewModel)
    val allTransactions by viewModel?.allTransactions?.collectAsStateWithLifecycle()
        ?: remember(ledgerState) { mutableStateOf((ledgerState as? LedgerState.Ready)?.transactions.orEmpty()) }
    val transactions = remember(allTransactions, filters, dateRange) { transactionSearchScope(allTransactions, filters, dateRange) }
    fun clearFilters() { filters = TransactionFilters(); firstDay = null; lastDay = null }
    val activeFilters = buildList {
        filters.provider?.let { add(ActiveTransactionFilter(it.displayName) { filters = filters.copy(provider = null) }) }
        filters.direction?.let { add(ActiveTransactionFilter(if (it == Direction.CREDIT) "Incoming" else "Outgoing") { filters = filters.copy(direction = null) }) }
        dateRange?.let { add(ActiveTransactionFilter(rangeLabel(it)) { firstDay = null; lastDay = null }) }
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = closeSearch) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = "Search payments" },
                        decorationBox = { input ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("Search payments", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                input()
                            }
                        },
                    )
                },
                actions = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                    IconButton(onClick = { keyboard?.hide(); focusManager.clearFocus(); showFilter = true },
                        modifier = Modifier.semantics { stateDescription = if (activeFilters.isEmpty()) "No filters" else "${activeFilters.size} filters applied" }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter",
                            tint = if (activeFilters.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                },
            )
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
                    resetKey = listOf(keyword, filters, dateRange),
                    activeFilters = activeFilters,
                    onClearFilters = ::clearFilters,
                    resultCount = results!!.transactions.size,
                    showCategory = showCategory,
                )
            }
        }
    }
    if (showFilter) TransactionFilterSheet(
        filters = filters, range = dateRange, category = null, providers = Provider.entries,
        onDismiss = { showFilter = false },
        onApply = { updated, dates, _ ->
            filters = updated
            firstDay = dates?.first
            lastDay = dates?.second
            showFilter = false
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TransactionList(
    transactions: List<UnifiedTransaction>,
    onTransaction: (UnifiedTransaction) -> Unit,
    highlightQuery: String = "",
    resetKey: Any? = null,
    activeFilters: List<ActiveTransactionFilter> = emptyList(),
    onClearFilters: () -> Unit = {},
    emptyTitle: String? = null,
    resultCount: Int? = null,
    onImportHistory: (() -> Unit)? = null,
    showCategory: Boolean = true,
) {
    val listState = rememberLazyListState()
    var previousResetKey by rememberSaveable { mutableStateOf(resetKey?.toString()) }
    LaunchedEffect(resetKey) {
        val next = resetKey?.toString()
        if (previousResetKey != next) {
            previousResetKey = next
            listState.scrollToItem(0)
        }
    }

    val groupedByDay by produceState<Map<LocalDate, List<UnifiedTransaction>>>(emptyMap(), transactions) {
        value = withContext(Dispatchers.Default) {
            val zone = net.wastu.artha.core.model.AccountingTime.zone
            transactions.groupBy { it.occurredAt.atZone(zone).toLocalDate() }
        }
    }

    // Avoid measuring an empty list while groups load, which loses restored scroll position.
    if (transactions.isNotEmpty() && groupedByDay.isEmpty()) {
        LedgerFeedback(loading = true)
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        if (activeFilters.isNotEmpty()) {
            item(key = "active_filters") {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeFilters.forEach { filter ->
                        FilterChip(selected = true, onClick = filter.clear, label = { Text(filter.label) },
                            trailingIcon = { Icon(Icons.Default.Clear, "Remove ${filter.label} filter") })
                    }
                    TextButton(onClick = onClearFilters) { Text("Clear all") }
                }
            }
        }
        resultCount?.let { count -> item(key = "results") {
            Text("$count results", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (transactions.isEmpty()) {
            item(key = "empty") {
                Text(
                    emptyTitle ?: if (highlightQuery.isBlank()) "No transactions" else "No matching transactions",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
                    onImportHistory?.let { action -> TextButton(onClick = action) { Text("Import history") } }
                }
            }
        }
        groupedByDay.forEach { (date, dailyTransactions) ->
            stickyHeader(key = "day_$date") {
                DateGroupHeader(date = date)
            }
            items(
                dailyTransactions,
                key = { it.key },
                contentType = { "transaction" },
            ) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    onClick = { onTransaction(transaction) },
                    highlightQuery = highlightQuery,
                    showCategory = showCategory,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

/** Categories come from analysis; with it off they are hidden rather than shown stale. */
@Composable
internal fun analysisShown(viewModel: ArthaViewModel?): Boolean =
    viewModel?.analysisSnapshot?.collectAsStateWithLifecycle()?.value?.analysisEnabled ?: true

@Composable
private fun DateGroupHeader(date: LocalDate) {
    val zone = net.wastu.artha.core.model.AccountingTime.zone
    val today = LocalDate.now(AccountingTime.clock)
    val dayLabel = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US))
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayLabel,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// Traverse JSON values so nested notes, counterpart names and item names are searchable.
private fun payloadTextValues(rawPayload: String): List<String> = runCatching {
    buildList {
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> collect(value.opt(key)) }
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
                is String -> if (value.isNotBlank()) add(value)
            }
        }
        collect(org.json.JSONTokener(rawPayload).nextValue())
    }
}.getOrElse { listOf(rawPayload) }

@Composable
fun HighlightedText(
    text: String,
    query: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    maxLines: Int = 1,
) {
    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    val highlighted = remember(text, query, background, foreground) {
        buildAnnotatedString {
            append(text)
            if (query.isNotBlank()) {
                var start = text.indexOf(query, ignoreCase = true)
                while (start >= 0) {
                    addStyle(
                        SpanStyle(background = background, color = foreground, fontWeight = FontWeight.Bold),
                        start, start + query.length,
                    )
                    start = text.indexOf(query, startIndex = start + 1, ignoreCase = true)
                }
            }
        }
    }
    Text(highlighted, modifier = modifier, style = style, fontWeight = fontWeight,
        color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionRow(transaction: UnifiedTransaction, onClick: () -> Unit, highlightQuery: String = "", showCategory: Boolean = true) {
    val credit = transaction.direction == Direction.CREDIT
    val reversed = transaction.isReversal || transaction.description.contains("(Reversed)", ignoreCase = true)
    val tags = remember(transaction) { transactionTags(transaction) }
    val movedMoney = tags.any { it.kind == TransactionTagKind.REVERSED || it.kind == TransactionTagKind.REFUND || it.kind == TransactionTagKind.TRANSFER }
    val title = transactionHumanLine(transaction)
    val amount = paymentRowAmount(transaction)
    val amountColor = when {
        !credit && reversed -> MaterialTheme.colorScheme.onSurfaceVariant
        credit -> incomeColor()
        else -> expenseColor()
    }
    val payloadValues by produceState(initialValue = emptyList<String>(), transaction.rawPayload, highlightQuery.isNotBlank()) {
        value = if (highlightQuery.isBlank()) emptyList() else withContext(Dispatchers.Default) { payloadTextValues(transaction.rawPayload) }
    }
    val snippet = remember(transaction, highlightQuery, payloadValues) {
        if (highlightQuery.isBlank()) null else (payloadValues + transaction.provider.displayName + transaction.cardLast4.orEmpty())
            .firstOrNull { it.contains(highlightQuery, true) && !title.contains(it, true) }
    }
    val fontScale = LocalDensity.current.fontScale
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, modifier = Modifier.semantics { stateDescription = if (credit) "Incoming payment" else "Outgoing payment" }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(transaction.provider.iconRes), transaction.provider.displayName,
                Modifier.size(36.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stackAmount = fontScale >= 1.3f || maxWidth < 300.dp || amount.length > 16
                    if (stackAmount) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TaggedTitle(title, tags, MaterialTheme.typography.bodyLarge, highlightQuery = highlightQuery,
                                fontWeight = FontWeight.Medium)
                            HighlightedText(amount, highlightQuery, MaterialTheme.typography.titleSmall,
                                color = amountColor,
                                fontWeight = FontWeight.SemiBold, maxLines = Int.MAX_VALUE)
                        }
                    } else Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TaggedTitle(title, tags, MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            highlightQuery = highlightQuery, fontWeight = FontWeight.Medium)
                        HighlightedText(amount, highlightQuery, MaterialTheme.typography.titleSmall,
                                color = amountColor, fontWeight = FontWeight.SemiBold)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatTime(transaction.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showCategory && !credit && !movedMoney && !transaction.category.isNullOrBlank()) {
                        HighlightedText(transaction.category, highlightQuery, MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                transaction.userNote?.takeIf { it.isNotBlank() }?.let {
                    HighlightedText(it, highlightQuery, MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                snippet?.let { HighlightedText(it, highlightQuery, MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            }
        }
    }
}

@Composable
fun LedgerFeedback(
    title: String? = null,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
        }
        title?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        onRetry?.let {
            Spacer(Modifier.height(16.dp))
            Button(onClick = it) { Text("Retry") }
        }
    }
}
