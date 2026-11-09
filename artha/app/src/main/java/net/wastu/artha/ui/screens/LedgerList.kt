package net.wastu.artha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.ui.components.ShapeBadge
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor

@Composable
internal fun TransactionList(
    transactions: List<UnifiedTransaction>,
    onTransaction: (UnifiedTransaction) -> Unit,
    highlightQuery: String = "",
    resetKey: Any? = null,
    activeFilters: List<ActiveTransactionFilter> = emptyList(),
    onClearFilters: () -> Unit = {},
    emptyTitle: String? = null,
    resultCount: Int? = null,
    isPopulating: Boolean = false,
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
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
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
                if (isPopulating) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LoadingIndicator(Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Syncing transactions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Checking connected accounts for new activity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 56.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ShapeBadge(
                            icon = if (activeFilters.isNotEmpty()) Icons.Default.FilterList else Icons.Default.ReceiptLong,
                            polygon = if (activeFilters.isNotEmpty()) MaterialShapes.Cookie6Sided else MaterialShapes.SoftBurst,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = emptyTitle ?: if (activeFilters.isNotEmpty()) "No matching transactions" else "No transactions yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (activeFilters.isNotEmpty())
                                "Try adjusting or clearing your filters to see more results."
                            else
                                "Your transactions will appear here once your bank accounts and e-wallets sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        if (activeFilters.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            FilledTonalButton(
                                onClick = onClearFilters,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text("Clear filters")
                            }
                        }
                    }
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
                    style = MaterialTheme.typography.headlineMediumEmphasized,
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
            Text(dayLabel(group.date), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.semantics { heading() })
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
