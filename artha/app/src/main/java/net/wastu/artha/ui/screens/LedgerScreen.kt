package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import net.wastu.artha.core.model.Money
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
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
import androidx.compose.ui.draw.alpha
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
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryStateLabel
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import net.wastu.artha.ui.GroupBadge
import net.wastu.artha.ui.groupBadgeInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalDensity
import net.wastu.artha.core.model.AccountingTime
import java.time.Instant

enum class PaymentMethod(val label: String) {
    QRIS("QRIS"),
    TRANSFER("Transfer"),
    CARD("Card");

    fun matches(transaction: UnifiedTransaction): Boolean {
        val payload = runCatching { JSONObject(transaction.rawPayload) }.getOrNull()
        return when (this) {
            QRIS -> transaction.isQris ||
                payload?.optBoolean("isQris") == true ||
                payload?.optString("f1").equals("QRD01", ignoreCase = true) ||
                payload?.optString("code").equals("QRD01", ignoreCase = true) ||
                payload?.optString("paymentMethod").equals("QRIS", ignoreCase = true) ||
                transaction.description.startsWith("QRIS", ignoreCase = true)

            TRANSFER -> payload?.optBoolean("isPocketTransfer") == true ||
                payload?.optBoolean("isWalletFunding") == true ||
                payload?.optBoolean("isOwnTransfer") == true ||
                payload?.optString("paymentMethod") in setOf("Transfer", "Top Up", "Pocket", "Savings") ||
                payload?.optString("code") in net.wastu.artha.core.model.TransactionClassifier.INTERNAL_TRANSFER_CODES ||
                payload?.optString("code") in net.wastu.artha.core.model.TransactionClassifier.WALLET_FUNDING_CODES ||
                payload?.optString("code") in setOf("OTD10", "TFD70", "TFC51", "WTD01", "WTC01") ||
                transaction.description.startsWith("Pocket Transfer:", ignoreCase = true) ||
                transaction.description.startsWith("Transfer ", ignoreCase = true) ||
                transaction.description.startsWith("Top up", ignoreCase = true) ||
                transaction.description.startsWith("Topup", ignoreCase = true)

            CARD -> transaction.cardLast4 != null ||
                payload?.optString("cardLast4")?.isNotBlank() == true ||
                payload?.optString("cardMask")?.isNotBlank() == true ||
                payload?.optString("paymentMethod") in setOf("Visa Debit", "Credit Card", "Card") ||
                payload?.optString("code") in setOf("PSD02", "PSD02-R")
        }
    }
}

data class TransactionFilters(
    val provider: Provider? = null,
    val direction: Direction? = null,
    val type: PaymentMethod? = null,
) {
    fun matches(transaction: UnifiedTransaction): Boolean =
        transaction.isConfirmed &&
        (provider == null || transaction.provider == provider) &&
        (direction == null || transaction.direction == direction) &&
        (type == null || type.matches(transaction))
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
    save = { listOf(it.provider?.name.orEmpty(), it.direction?.name.orEmpty(), it.type?.name.orEmpty()) },
    restore = { list ->
        TransactionFilters(
            provider = list.getOrNull(0)?.takeIf(String::isNotBlank)?.let { name ->
                runCatching { Provider.valueOf(name) }.getOrNull()
            },
            direction = list.getOrNull(1)?.takeIf(String::isNotBlank)?.let { name ->
                runCatching { Direction.valueOf(name) }.getOrNull()
            },
            type = list.getOrNull(2)?.takeIf(String::isNotBlank)?.let { name ->
                PaymentMethod.entries.firstOrNull { it.name == name }
            },
        )
    },
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

// Money rejects zero, but an empty period or day legitimately totals nothing.
internal fun formatSpendingTotal(totalMinor: Long): String = if (totalMinor <= 0L) "Rp0" else formatMoney(Money(totalMinor))

internal fun rangeLabel(range: Pair<Long?, Long?>): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    fun date(value: Long?) = value?.let { Instant.ofEpochMilli(it).atZone(AccountingTime.zone).toLocalDate().format(formatter) }
    val start = date(range.first)
    val end = date(range.second)
    return if (start != null && end != null && start != end) "$start to $end" else start ?: end ?: "Dates"
}

internal data class PeriodSummary(
    val totalMinor: Long,
    val count: Int,
    val label: String,
    val incomeMinor: Long = 0L,
    val incomeCount: Int = 0,
) {
    val netMinor: Long get() = incomeMinor - totalMinor
    val rows: Int get() = count + incomeCount
}

/** "+Rp1.000", "-Rp1.000" or "Rp0"; Money rejects zero so the sign and amount are formatted separately. */
internal fun formatNetTotal(netMinor: Long): String = when {
    netMinor > 0L -> "+" + formatMoney(Money(netMinor))
    netMinor < 0L -> "-" + formatMoney(Money(-netMinor))
    else -> "Rp0"
}

internal fun periodSummary(
    rows: List<UnifiedTransaction>,
    range: Pair<Long?, Long?>?,
    today: LocalDate,
    category: String? = null,
): PeriodSummary {
    val spending = rows.filter(UnifiedTransaction::isExpense)
    val totalMinor = spending.sumOf { it.money.amountMinor }
    val count = spending.size
    val income = rows.filter(UnifiedTransaction::isIncome)
    val label = when {
        range != null -> periodLabel(range, today)
        category != null -> category
        else -> ""
    }
    return PeriodSummary(totalMinor, count, label, income.sumOf { it.money.amountMinor }, income.size)
}

/** Preset name when the range matches one ("This month"), otherwise the dates. Used by the summary and the chip. */
internal fun periodLabel(range: Pair<Long?, Long?>, today: LocalDate): String = when (range) {
    TransactionPeriod.THIS_MONTH.range(today) -> TransactionPeriod.THIS_MONTH.label
    TransactionPeriod.LAST_MONTH.range(today) -> TransactionPeriod.LAST_MONTH.label
    TransactionPeriod.LAST_30_DAYS.range(today) -> TransactionPeriod.LAST_30_DAYS.label
    else -> rangeLabel(range)
}

@Composable
private fun TypeChips(selected: net.wastu.artha.core.model.TransactionType?, onSelect: (net.wastu.artha.core.model.TransactionType?) -> Unit) {
    val options = listOf<Pair<String, net.wastu.artha.core.model.TransactionType?>>(
        "All" to null,
        "Expenses" to net.wastu.artha.core.model.TransactionType.EXPENSE,
        "Income" to net.wastu.artha.core.model.TransactionType.INCOME,
        "Transfers" to net.wastu.artha.core.model.TransactionType.TRANSFER,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, type) -> FilterChip(selected = selected == type, onClick = { onSelect(type) }, label = { Text(label) }) }
    }
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
            onImportHistory = if (active.isEmpty()) onImportHistory else null,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TransactionFilterSheet(
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = true,
                                onClick = { clearCategory = true; draft = filters.copy(direction = null) },
                                label = { Text(category) },
                                trailingIcon = { Icon(Icons.Default.Clear, "Remove category filter") },
                            )
                            if (viewModel != null) {
                                AssistChip(
                                    onClick = {
                                        val month = viewModel.selectedMonth.value
                                        viewModel.recategorize(net.wastu.artha.data.analysis.RecategorizeScope.CategoryInMonth(category, month))
                                        onDismiss()
                                    },
                                    label = { Text("Re-categorize") },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    if (category == null || clearCategory) {
                        FilterOptions("Method", listOf(null) + PaymentMethod.entries, draft.type, { it?.label ?: "All" }) { draft = draft.copy(type = it) }
                        FilterOptions("Source", listOf(null) + providers, draft.provider, { it?.displayName ?: "All" }) { draft = draft.copy(provider = it) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Dates", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransactionPeriod.entries.forEach { period ->
                                FilterChip(
                                    selected = selectedPeriod == period,
                                    onClick = {
                                        if (period == TransactionPeriod.CUSTOM) pickingDates = true else dates = period.range(today)
                                    },
                                    label = { Text(period.label) },
                                )
                            }
                        }
                        if (selectedPeriod == TransactionPeriod.CUSTOM && dates?.first != null && dates?.second != null) {
                            val customRange = dates!!
                            val (customTotal, customCount) = remember(transactions, customRange) {
                                val start = customRange.first
                                val endExclusive = customRange.second?.let(AccountingTime::dayAfter)
                                val spending = transactions.filter {
                                    val millis = it.occurredAt.toEpochMilli()
                                    it.isExpense &&
                                        (start == null || millis >= start) &&
                                        (endExclusive == null || millis < endExclusive)
                                }
                                spending.sumOf { it.money.amountMinor } to spending.size
                            }
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatSpendingTotal(customTotal),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "$customCount ${if (customCount == 1) "payment" else "payments"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    FilledTonalButton(onClick = { onApply(draft.copy(direction = null), dates, clearCategory) }, modifier = Modifier.weight(1f)) { Text("Apply") }
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
    val showCategory = true
    val categoryProgress by viewModel?.categoryProgress?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(CategoryProgress()) }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal data class DayGroup(val date: LocalDate, val transactions: List<UnifiedTransaction>)

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
    categoryProgress: CategoryProgress = CategoryProgress(),
    periodSummary: PeriodSummary? = null,
    onFilterDates: ((Long, Long) -> Unit)? = null,
    activeRange: Pair<Long?, Long?>? = null,
) {
    val listState = rememberLazyListState()
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    var previousResetKey by rememberSaveable { mutableStateOf(resetKey?.toString()) }
    LaunchedEffect(resetKey) {
        val next = resetKey?.toString()
        if (previousResetKey != next) {
            previousResetKey = next
            listState.scrollToItem(0)
        }
    }

    val groupedByDay by produceState<List<DayGroup>>(emptyList(), transactions) {
        value = withContext(Dispatchers.Default) {
            val zone = net.wastu.artha.core.model.AccountingTime.zone
            transactions
                .groupBy { it.occurredAt.atZone(zone).toLocalDate() }
                .map { (date, dailyTxs) -> DayGroup(date, dailyTxs) }
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
        if (periodSummary != null) {
            item(key = "period_summary") {
                TotalsSummary(
                    label = periodSummary.label,
                    count = periodSummary.rows,
                    incomeMinor = periodSummary.incomeMinor,
                    expenseMinor = periodSummary.totalMinor,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                )
            }
        }
        if (activeFilters.isNotEmpty()) {
            item(key = "active_filters") {
                FlowRow(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = if (periodSummary != null) 8.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                    emptyTitle ?: if (highlightQuery.isBlank()) "No expenses" else "No matching transactions",
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
        groupedByDay.forEach { group ->
            stickyHeader(key = "day_${group.date}") {
                DateGroupHeader(date = group.date, onClick = { openDay = group.date })
            }
            items(
                group.transactions,
                key = { it.key },
                contentType = { "transaction" },
            ) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    modifier = if (transaction.type == net.wastu.artha.core.model.TransactionType.TRANSFER) Modifier.alpha(0.6f) else Modifier,
                    onClick = { onTransaction(transaction) },
                    highlightQuery = highlightQuery,
                    showCategory = showCategory,
                    categoryState = paymentCategoryState(transaction.key, categoryProgress),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
    openDay?.let { day ->
        groupedByDay.firstOrNull { it.date == day }?.let { DayTotalsSheet(it, activeRange, onFilterDates) { openDay = null } }
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now(AccountingTime.clock)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US))
    }
}

@Composable
private fun DateGroupHeader(date: LocalDate, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Show day totals", onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayLabel(date),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TotalsSummary(
    label: String,
    count: Int,
    incomeMinor: Long,
    expenseMinor: Long,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    val net = incomeMinor - expenseMinor
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showHeader) Text(
                text = listOf(label, "$count ${if (count == 1) "payment" else "payments"}").filter(String::isNotBlank).joinToString(", "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text("Difference", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatNetTotal(net),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        net > 0L -> incomeColor()
                        net < 0L -> expenseColor()
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TotalsFigure("Income", formatSpendingTotal(incomeMinor), incomeColor(), Modifier.weight(1f))
                TotalsFigure("Expenses", formatSpendingTotal(expenseMinor), expenseColor(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TotalsFigure(title: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayTotalsSheet(
    group: DayGroup,
    activeRange: Pair<Long?, Long?>?,
    onFilterDates: ((Long, Long) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val expenseMinor = group.transactions.filter(UnifiedTransaction::isExpense).sumOf { it.money.amountMinor }
    val incomeMinor = group.transactions.filter(UnifiedTransaction::isIncome).sumOf { it.money.amountMinor }
    val zone = AccountingTime.zone
    val dayStart = group.date.atStartOfDay(zone).toInstant().toEpochMilli()
    val month = java.time.YearMonth.from(group.date)
    val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = month.atEndOfMonth().atStartOfDay(zone).toInstant().toEpochMilli()
    val dayActive = activeRange == dayStart to dayStart
    val monthActive = activeRange == monthStart to monthEnd
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(dayLabel(group.date), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            TotalsSummary(
                label = group.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)),
                count = group.transactions.size,
                incomeMinor = incomeMinor,
                expenseMinor = expenseMinor,
                showHeader = false,
            )
            if (onFilterDates != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        enabled = !dayActive,
                        onClick = { onFilterDates(dayStart, dayStart); onDismiss() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Only this day") }
                    androidx.compose.material3.OutlinedButton(
                        enabled = !monthActive,
                        onClick = { onFilterDates(monthStart, monthEnd); onDismiss() },
                        modifier = Modifier.weight(1f),
                    ) { Text(month.format(DateTimeFormatter.ofPattern("MMMM", Locale.US))) }
                }
            }
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
fun TransactionRow(
    transaction: UnifiedTransaction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    showCategory: Boolean = true,
    categoryState: PaymentCategoryState = PaymentCategoryState.IDLE,
) {
    val reversed = transaction.isReversedRecord
    val tags = remember(transaction) { transactionTags(transaction) }
    // A readable group drops the Grouped pill for an icon overlay; unreadable keeps the pill.
    val groupBadge = remember(transaction) { groupBadgeInfo(transaction) }.takeIf { tags.any { it.kind == TransactionTagKind.GROUPED } }
    val rowTags = if (groupBadge != null) tags.filter { it.kind != TransactionTagKind.GROUPED } else tags
    val movedMoney = tags.any { it.kind == TransactionTagKind.REVERSED || it.kind == TransactionTagKind.REFUND || it.kind == TransactionTagKind.TRANSFER }
    val title = remember(transaction) { transactionHumanLine(transaction) }
    val amount = remember(transaction) { paymentRowAmount(transaction) }
    // Reversed charges and own transfers aren't spending, so their amounts are not shown as expenses.
    val amountColor = if (reversed || tags.any { it.kind == TransactionTagKind.TRANSFER }) MaterialTheme.colorScheme.onSurfaceVariant else if (transaction.direction == Direction.CREDIT) incomeColor() else expenseColor()
    val payloadValues by produceState(initialValue = emptyList<String>(), transaction.rawPayload, highlightQuery.isNotBlank()) {
        value = if (highlightQuery.isBlank()) emptyList() else withContext(Dispatchers.Default) { payloadTextValues(transaction.rawPayload) }
    }
    val snippet = remember(transaction, highlightQuery, payloadValues) {
        if (highlightQuery.isBlank()) null else (payloadValues + transaction.provider.displayName + transaction.cardLast4.orEmpty())
            .firstOrNull { it.contains(highlightQuery, true) && !title.contains(it, true) }
    }
    val fontScale = LocalDensity.current.fontScale
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, modifier = modifier.semantics { stateDescription = if (transaction.direction == Direction.CREDIT) "Incoming payment" else "Outgoing payment" }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clearAndSetSemantics {
                contentDescription = transaction.provider.displayName + if (groupBadge != null) ", Grouped" else ""
            }) {
                Image(painterResource(transaction.provider.iconRes), contentDescription = null,
                    Modifier.matchParentSize().clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                groupBadge?.let { badge ->
                    GroupBadgeOverlay(badge, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stackAmount = fontScale >= 1.3f || maxWidth < 300.dp || amount.length > 16
                    if (stackAmount) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TaggedTitle(title, rowTags, MaterialTheme.typography.bodyLarge, highlightQuery = highlightQuery,
                                fontWeight = FontWeight.Medium)
                            HighlightedText(amount, highlightQuery, MaterialTheme.typography.titleSmall,
                                color = amountColor,
                                fontWeight = FontWeight.SemiBold, maxLines = Int.MAX_VALUE)
                        }
                    } else Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TaggedTitle(title, rowTags, MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            highlightQuery = highlightQuery, fontWeight = FontWeight.Medium)
                        HighlightedText(amount, highlightQuery, MaterialTheme.typography.titleSmall,
                                color = amountColor, fontWeight = FontWeight.SemiBold)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatTime(transaction.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showCategory && !movedMoney) {
                        val busyLabel = paymentCategoryStateLabel(categoryState)
                        if (categoryState != PaymentCategoryState.IDLE && busyLabel != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                )
                                Text(
                                    text = busyLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (!transaction.category.isNullOrBlank()) {
                            HighlightedText(transaction.category, highlightQuery, MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
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

/** The tiny bottom-right overlay on a grouped row: the 2nd member's icon, or "+n" for bigger groups. */
@Composable
private fun GroupBadgeOverlay(badge: GroupBadge, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(16.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(1.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (badge.extraCount > 0) {
            Text("+${badge.extraCount}", style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        } else {
            Image(painterResource(badge.secondProvider.iconRes), contentDescription = null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
