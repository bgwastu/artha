package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.formatTime
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
) {
    var selectedProvider by rememberSaveable { mutableStateOf<Provider?>(null) }
    var selectedDirection by rememberSaveable { mutableStateOf<Direction?>(null) }
    val dateRange by viewModel?.dateRangeFilter?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    var showDateRangePicker by remember { mutableStateOf(false) }
    val visibleProviders = remember(enabledProviders) {
        Provider.entries.filter { it in enabledProviders }
    }
    LaunchedEffect(enabledProviders) {
        if (selectedProvider !in enabledProviders) selectedProvider = null
    }
    when (state) {
        LedgerState.Loading -> LedgerFeedback(loading = true)
        LedgerState.Failed -> LedgerFeedback(title = "Failed to load", onRetry = onRetry)
        is LedgerState.Ready -> {
            val filtered = remember(state.transactions, selectedProvider, selectedDirection) {
                state.transactions.filter { transaction ->
                    (selectedProvider == null || transaction.provider == selectedProvider) &&
                        (selectedDirection == null || transaction.direction == selectedDirection)
                }
            }
            TransactionList(
                transactions = filtered,
                onTransaction = onTransaction,
                resetKey = selectedProvider to selectedDirection,
                activeFilter = run {
                    val dateLabel = dateRange?.let { (start, end) ->
                        val zone = ZoneId.systemDefault()
                        val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
                        val s = start?.let { fmt.format(java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }
                        val e = end?.let { fmt.format(java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }
                        if (s != null && e != null) "$s to $e" else s ?: e ?: "Date range"
                    }
                    listOfNotNull(
                        selectedProvider?.displayName,
                        selectedDirection?.let { if (it == Direction.CREDIT) "Incoming" else "Outgoing" },
                        dateLabel
                    ).joinToString(" / ").ifBlank { null }
                },
                onClearFilter = {
                    selectedProvider = null
                    selectedDirection = null
                    viewModel?.clearDateRangeFilter()
                },
            )
        }
    }
    if (showFilter) {
        ModalBottomSheet(onDismissRequest = onDismissFilter) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Filter Transactions", style = MaterialTheme.typography.titleLarge)
                Text("Direction", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "All directions", Direction.CREDIT to "Incoming", Direction.DEBIT to "Outgoing")
                        .forEach { (direction, label) ->
                            FilterChip(
                                selected = selectedDirection == direction,
                                onClick = { selectedDirection = direction },
                                label = { Text(label) },
                            )
                        }
                }
                Text("Provider", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedProvider == null,
                        onClick = { selectedProvider = null },
                        label = { Text("All") },
                    )
                    visibleProviders.forEach { item ->
                        FilterChip(
                            selected = selectedProvider == item,
                            onClick = { selectedProvider = if (selectedProvider == item) null else item },
                            label = { Text(item.displayName) },
                        )
                    }
                }
                Text("Date Range", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = dateRange == null,
                        onClick = { viewModel?.clearDateRangeFilter() },
                        label = { Text("All time") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val now = LocalDate.now()
                            val start = now.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val end = now.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            viewModel?.setDateRangeFilter(start, end)
                        },
                        label = { Text("This Month") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val last = LocalDate.now().minusMonths(1)
                            val start = last.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val end = last.withDayOfMonth(last.lengthOfMonth()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            viewModel?.setDateRangeFilter(start, end)
                        },
                        label = { Text("Last Month") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val now = LocalDate.now()
                            val start = now.minusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val end = now.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            viewModel?.setDateRangeFilter(start, end)
                        },
                        label = { Text("Last 30 Days") },
                    )
                    FilterChip(
                        selected = dateRange != null,
                        onClick = { showDateRangePicker = true },
                        label = { Text("Custom Range...") },
                    )
                }
                if (selectedProvider != null || selectedDirection != null) {
                    TextButton(onClick = {
                        selectedProvider = null
                        selectedDirection = null
                    }) { Text("Reset") }
                }
            }
                if (showDateRangePicker) {
                    val pickerState = rememberDateRangePickerState()
                    DatePickerDialog(
                        onDismissRequest = { showDateRangePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                val s = pickerState.selectedStartDateMillis
                                val e = pickerState.selectedEndDateMillis ?: s
                                if (s != null) {
                                    viewModel?.setDateRangeFilter(s, e)
                                }
                                showDateRangePicker = false
                            }) { Text("Apply") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDateRangePicker = false }) { Text("Cancel") }
                        }
                    ) {
                        DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
                    }
                }
        }
    }
}

private data class SearchEntry(val transaction: UnifiedTransaction, val fields: List<String>)
private data class SearchResults(val query: String, val transactions: List<UnifiedTransaction>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTransactionsScreen(
    ledgerState: LedgerState,
    onBack: () -> Unit,
    onTransaction: (UnifiedTransaction) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyword = query.trim()
    val focusRequester = remember { FocusRequester() }
    val transactions = (ledgerState as? LedgerState.Ready)?.transactions.orEmpty()
    // Decode each payload once per ledger update, away from composition and scrolling.
    val index by produceState<List<SearchEntry>?>(null, transactions) {
        value = null
        value = withContext(Dispatchers.Default) {
            transactions.map { transaction ->
                ensureActive()
                SearchEntry(transaction, listOf(
                    transaction.description,
                    transaction.provider.displayName,
                    transaction.cardLast4.orEmpty(),
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
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search transactions...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                },
                actions = {
                    IconButton(onClick = { query = "" }, enabled = query.isNotEmpty()) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                ledgerState == LedgerState.Failed -> LedgerFeedback(title = "Failed to load")
                ledgerState == LedgerState.Loading || results == null || results?.query != keyword ->
                    LedgerFeedback(loading = true)
                else -> TransactionList(
                    transactions = results!!.transactions,
                    onTransaction = onTransaction,
                    highlightQuery = keyword,
                    resetKey = keyword,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TransactionList(
    transactions: List<UnifiedTransaction>,
    onTransaction: (UnifiedTransaction) -> Unit,
    highlightQuery: String = "",
    resetKey: Any? = null,
    activeFilter: String? = null,
    onClearFilter: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(resetKey) { listState.scrollToItem(0) }

    val groupedByDay = remember(transactions) {
        val zone = ZoneId.systemDefault()
        transactions.groupBy { LocalDate.ofInstant(it.occurredAt, zone) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
    ) {
        if (activeFilter != null) {
            item(key = "active_filter") {
                FilterChip(
                    selected = true,
                    onClick = onClearFilter,
                    label = { Text(activeFilter) },
                    trailingIcon = { Icon(Icons.Default.Clear, contentDescription = "Reset filters") },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (transactions.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (highlightQuery.isBlank()) "No transactions" else "No matching transactions",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groupedByDay.forEach { (date, dailyTransactions) ->
            stickyHeader(key = "day_$date") {
                DateGroupHeader(date = date)
            }
            items(
                dailyTransactions,
                key = { "${it.provider.name}:${it.providerTransactionId}" },
                contentType = { "transaction" },
            ) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    onClick = { onTransaction(transaction) },
                    highlightQuery = highlightQuery,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

@Composable
private fun DateGroupHeader(date: LocalDate) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
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
        color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun TransactionRow(transaction: UnifiedTransaction, onClick: () -> Unit, highlightQuery: String = "") {
    val credit = transaction.direction == Direction.CREDIT
    val payloadValues by produceState(
        initialValue = emptyList<String>(),
        transaction.rawPayload,
        highlightQuery.isNotBlank(),
    ) {
        value = if (highlightQuery.isBlank()) emptyList() else withContext(Dispatchers.Default) {
            payloadTextValues(transaction.rawPayload)
        }
    }
    val snippet = remember(transaction, highlightQuery, payloadValues) {
        if (highlightQuery.isBlank()) null else {
            (payloadValues + transaction.provider.displayName + transaction.cardLast4.orEmpty())
                .firstOrNull { it.contains(highlightQuery, ignoreCase = true) &&
                    !transaction.description.contains(it, ignoreCase = true) }
                ?.let { value ->
                    val match = value.indexOf(highlightQuery, ignoreCase = true)
                    val start = match
                    (if (start > 0) "..." else "") + value.substring(start)
                }
        }
    }
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        ListItem(
            modifier = Modifier.height(80.dp),
            leadingContent = {
                val isGrouped = transaction.pocket != null && transaction.provider != Provider.JAGO
                Box(contentAlignment = Alignment.Center) {
                    if (isGrouped) {
                        Box(modifier = Modifier.size(40.dp)) {
                            Image(
                                painter = painterResource(transaction.provider.iconRes),
                                contentDescription = transaction.provider.displayName,
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Image(
                                painter = painterResource(Provider.JAGO.iconRes),
                                contentDescription = Provider.JAGO.displayName,
                                modifier = Modifier
                                    .size(18.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    } else {
                        Image(
                            painter = painterResource(transaction.provider.iconRes),
                            contentDescription = transaction.provider.displayName,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val isReversal = transaction.description.contains("(Reversed)", ignoreCase = true)
                    if (isReversal) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            Text(
                                text = "REVERSED",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    val isPocketTransfer = transaction.description.startsWith("Pocket Transfer:", ignoreCase = true)
                    if (isPocketTransfer) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            Text(
                                text = "TRANSFER",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    val badge = if (transaction.isQris) "QRIS" else transaction.cardLast4
                    if (badge != null) {
                        Surface(
                            color = if (transaction.isQris) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            HighlightedText(
                                text = badge,
                                query = highlightQuery,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (transaction.isQris) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    HighlightedText(
                        text = transaction.description,
                        query = highlightQuery,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val timeStr = formatTime(transaction.occurredAt)
                        val catStr = transaction.category
                        val label = if (!credit && !catStr.isNullOrBlank()) "$timeStr - $catStr" else timeStr
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (snippet != null) Modifier.weight(1f) else Modifier,
                        )
                        if (snippet != null) {
                            HighlightedText(
                                text = snippet,
                                query = highlightQuery,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                            )
                        }
                    }
                    if (!transaction.userNote.isNullOrBlank()) {
                        Text(
                            text = "# ${transaction.userNote}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            trailingContent = {
                val isReversal = transaction.description.contains("(Reversed)", ignoreCase = true)
                val isZero = isReversal || transaction.money.amountMinor == 0L
                val amountText = if (isZero) "Rp0" else "${if (credit) "+" else "−"}${formatMoney(transaction.money)}"
                val amountColor = if (isZero) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else if (credit) {
                    incomeColor()
                } else {
                    expenseColor()
                }
                HighlightedText(
                    text = amountText,
                    query = highlightQuery,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
            },
        )
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
