package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.UNCATEGORIZED_LABEL
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.LedgerState
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.components.ConnectedChoiceRow
import net.wastu.artha.ui.components.PageScaffold
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.paymentRowAmount

private enum class ReviewMode(val label: String) { NEEDS_REVIEW("Needs review"), WHOLE_MONTH("Whole month") }

private data class ReviewData(val order: List<String>, val items: Map<String, ReviewItem>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryReviewScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
    month: YearMonth,
    onOpenTransaction: (UnifiedTransaction) -> Unit = {},
) {
    var mode by rememberSaveable { mutableStateOf(ReviewMode.NEEDS_REVIEW) }
    var browsedMonth by rememberSaveable { mutableStateOf(month.toString()) }
    var wholeType by rememberSaveable { mutableStateOf(TransactionType.EXPENSE) }
    val snackbar = remember { SnackbarHostState() }
    val monthValue = remember(browsedMonth) { YearMonth.parse(browsedMonth) }
    PageScaffold(
        title = monthValue.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
        onBack = onBack,
        snackbarHost = snackbar,
        actions = {
                    if (mode == ReviewMode.WHOLE_MONTH) {
                        IconButton(onClick = { browsedMonth = monthValue.minusMonths(1).toString() }) { Icon(Icons.Default.ChevronLeft, "Previous month") }
                        IconButton(
                            enabled = monthValue < YearMonth.now(AccountingTime.clock),
                            onClick = { browsedMonth = monthValue.plusMonths(1).toString() },
                        ) { Icon(Icons.Default.ChevronRight, "Next month") }
                    }
        },
        belowBar = {
                ConnectedChoiceRow(
                    options = ReviewMode.entries,
                    selected = mode,
                    onSelect = { mode = it },
                    label = { it.label },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (mode) {
                ReviewMode.NEEDS_REVIEW -> NeedsReviewPane(viewModel, monthValue, snackbar, onBack, onOpenTransaction)
                ReviewMode.WHOLE_MONTH -> WholeMonthPane(viewModel, monthValue, wholeType, { wholeType = it }, onOpenTransaction)
            }
        }
    }
}

@Composable
private fun NeedsReviewPane(
    viewModel: ArthaViewModel,
    month: YearMonth,
    snackbar: SnackbarHostState,
    onDone: () -> Unit,
    onOpenTransaction: (UnifiedTransaction) -> Unit,
) {
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val categoryProgress by viewModel.categoryProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var session by rememberSaveable(month, stateSaver = ReviewSession.Saver) { mutableStateOf<ReviewSession?>(null) }
    val restoredKeys = remember(month) { session?.keys }

    val data by produceState<ReviewData?>(null, month) {
        // A restored screen opens before the saved ledger loads; an empty first read would freeze an empty queue.
        viewModel.ledgerState.first { it !is LedgerState.Loading }
        value = withContext(Dispatchers.Default) {
            val rows = viewModel.allTransactions.value
            val current = viewModel.analysisSnapshot.value
            val today = LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone))
            val items = if (restoredKeys != null) {
                val byKey = rows.associateBy { it.key }
                buildReviewItems(restoredKeys.mapNotNull(byKey::get), rows, current, today)
            } else {
                val progress = viewModel.categoryProgress.value
                buildReviewQueue(rows, current, progress.activeKeys + progress.queuedKeys, today, month)
            }
            ReviewData(items.map { it.tx.key }, items.associateBy { it.tx.key })
        }
    }
    val loaded = data
    if (loaded == null) {
        LoadingIndicator(Modifier.padding(32.dp).size(64.dp))
        return
    }
    val state = session ?: ReviewSession(loaded.order).also { session = it }
    val item = state.current?.let(loaded.items::get)

    var noteDraft by remember(item?.tx?.key) { mutableStateOf(item?.tx?.userNote.orEmpty()) }
    var showNote by remember(item?.tx?.key) { mutableStateOf(!item?.tx?.userNote.isNullOrBlank()) }
    var selected by remember(item?.tx?.key, state.cursor) { mutableStateOf(item?.let { state.selection(it.aiCategory) }.orEmpty()) }
    var applySimilar by remember(item?.tx?.key) { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }

    // The categorizer took over this payment; leave it to the automation.
    LaunchedEffect(item?.tx?.key, categoryProgress.activeKeys, categoryProgress.queuedKeys) {
        val key = item?.tx?.key ?: return@LaunchedEffect
        if (key in categoryProgress.activeKeys || key in categoryProgress.queuedKeys) session = state.skip()
    }

    if (item == null) {
        Column(
            Modifier.widthIn(max = 480.dp).padding(24.dp).padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (loaded.order.isEmpty()) "Nothing to review" else "All reviewed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canGoBack) OutlinedButton(onClick = { session = state.back() }) { Text("Back") }
                Button(onClick = onDone) { Text("Done") }
            }
        }
        return
    }

    val similar = item.siblingKeys.filter { it !in state.decisions && it in loaded.items }
    val categories = snapshot.categoriesFor(item.tx.type)
    fun applyCurrent() {
        val grouped = if (applySimilar) similar else emptyList()
        viewModel.confirmCategory(item.tx, selected) {}
        grouped.forEach { key -> loaded.items[key]?.let { viewModel.confirmCategory(it.tx, selected) {} } }
        session = state.apply(selected, grouped)
        if (grouped.isNotEmpty()) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("${selected} applied to ${grouped.size + 1} payments", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                val (reverted, cleared) = (session ?: return@launch).undo()
                cleared.forEach(viewModel::clearClassification)
                session = reverted
            }
        }
    }

    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinearWavyProgressIndicator(progress = { if (state.total == 0) 0f else (state.cursor.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("${state.position} of ${state.total}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().clickable { onOpenTransaction(item.tx) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.merchant, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.amountText}  ${item.dateText}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, "View transaction", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.reason?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val options = (listOf(item.aiCategory) + item.alternatives + listOfNotNull(selected.takeIf { it.isNotBlank() }))
            .filter { it.isNotBlank() }.distinct()
        Column(Modifier.selectableGroup()) {
            options.forEach { option ->
                ListItem(
                    modifier = Modifier.selectable(selected = selected == option, role = Role.RadioButton, onClick = { selected = option }),
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
                modifier = Modifier.clickable { picking = true },
                headlineContent = { Text("Other category", color = MaterialTheme.colorScheme.primary) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (similar.isNotEmpty()) {
            ListItem(
                modifier = Modifier.toggleable(value = applySimilar, role = Role.Checkbox, onValueChange = { applySimilar = it }),
                leadingContent = { Checkbox(checked = applySimilar, onCheckedChange = null) },
                headlineContent = { Text("Apply to ${similar.size} similar") },
                supportingContent = { Text(item.merchant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (!showNote) {
            TextButton(onClick = { showNote = true }, modifier = Modifier.align(Alignment.Start)) {
                Icon(Icons.Default.EditNote, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (noteDraft.isBlank()) "Add note for AI" else "Edit note")
            }
        } else {
            OutlinedTextField(
                value = noteDraft, onValueChange = { noteDraft = it },
                label = { Text("Note for AI") },
                supportingText = { Text("Saving the note re-runs AI categorization") },
                minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            if (noteDraft.isNotBlank()) TextButton(
                onClick = {
                    viewModel.setUserNote(item.tx, noteDraft.trim()) {}
                    viewModel.recategorize(RecategorizeScope.Payment(item.tx.key, replaceManual = true))
                    session = state.skip()
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Save and re-analyze") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = state.canGoBack, onClick = { session = state.back() }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { session = state.skip() }) { Text("Skip") }
            Button(enabled = selected.isNotBlank(), onClick = ::applyCurrent) {
                Text(if (state.cursor >= state.total - 1) "Confirm" else "Next")
            }
        }
    }

    if (picking) AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("Select category") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
                categories.forEach { category ->
                    ListItem(
                        headlineContent = { Text(category) },
                        leadingContent = { RadioButton(selected = category == selected, onClick = null) },
                        modifier = Modifier.selectable(selected = category == selected, role = Role.RadioButton, onClick = { selected = category; picking = false }),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WholeMonthPane(
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
    val topCategories = remember(rows, snapshot, type) {
        val used = rows.filter { it.type == type }.mapNotNull { snapshot.categories[it.key] ?: it.category }
            .filter { it in categories }.groupingBy { it }.eachCount()
        (used.entries.sortedByDescending { it.value }.map { it.key } + categories).distinct().take(4)
    }
    fun assign(group: MerchantGroup, category: String) {
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
        groups.forEach { group ->
            val id = txMerchantKey(group.transactions.first())
            item(key = id) {
                Column {
                    ListItem(
                        modifier = Modifier.clickable { expanded = if (expanded == id) null else id },
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
                        val chips = if (!group.mixed && group.category != null && group.category !in topCategories) listOf(group.category) + topCategories else topCategories
                        chips.forEach { category ->
                            FilterChip(
                                selected = !group.mixed && group.category == category,
                                onClick = { assign(group, category) },
                                label = { Text(category) },
                            )
                        }
                        AssistChip(onClick = { picking = group }, label = { Text("Other") })
                    }
                    if (expanded == id) group.transactions.forEach { tx ->
                        ListItem(
                            modifier = Modifier.clickable { onOpenTransaction(tx) }.padding(start = 16.dp),
                            headlineContent = { Text(formatReviewDate(tx.occurredAt, LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone)))) },
                            supportingContent = { Text(snapshot.categories[tx.key] ?: tx.category ?: UNCATEGORIZED_LABEL) },
                            trailingContent = { Text(paymentRowAmount(tx)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    picking?.let { group ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Select category") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    categories.forEach { category ->
                        ListItem(
                            headlineContent = { Text(category) },
                            modifier = Modifier.clickable { assign(group, category); picking = null },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}
