package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
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
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.LedgerState
import net.wastu.artha.ui.components.ConnectedChoiceRow
import net.wastu.artha.ui.components.PageScaffold

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
                IconButton(onClick = { browsedMonth = monthValue.minusMonths(1).toString() }) {
                    Icon(Icons.Default.ChevronLeft, "Previous month")
                }
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

/**
 * The one-by-one queue. The queue is built once per month and its [ReviewSession] survives rotation;
 * this pane owns the side effects (confirming categories, undo, re-analysis) that the session itself avoids.
 */
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

    // The categorizer took over this payment; leave it to the automation.
    LaunchedEffect(item?.tx?.key, categoryProgress.activeKeys, categoryProgress.queuedKeys) {
        val key = item?.tx?.key ?: return@LaunchedEffect
        if (key in categoryProgress.activeKeys || key in categoryProgress.queuedKeys) session = state.skip()
    }

    if (item == null) {
        ReviewFinished(nothingQueued = loaded.order.isEmpty(), canGoBack = state.canGoBack, onBack = { session = state.back() }, onDone = onDone)
        return
    }

    val similar = item.siblingKeys.filter { it !in state.decisions && it in loaded.items }
    fun applySelection(category: String, applyToSimilar: Boolean) {
        val grouped = if (applyToSimilar) similar else emptyList()
        viewModel.confirmCategory(item.tx, category) {}
        grouped.forEach { key -> loaded.items[key]?.let { viewModel.confirmCategory(it.tx, category) {} } }
        session = state.apply(category, grouped)
        if (grouped.isNotEmpty()) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("$category applied to ${grouped.size + 1} payments", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                val (reverted, cleared) = (session ?: return@launch).undo()
                cleared.forEach(viewModel::clearClassification)
                session = reverted
            }
        }
    }
    ReviewItemPane(
        item = item,
        session = state,
        snapshot = snapshot,
        similarCount = similar.size,
        onOpenTransaction = { onOpenTransaction(item.tx) },
        onApply = ::applySelection,
        onSkip = { session = state.skip() },
        onBack = { session = state.back() },
        onSaveNote = { note ->
            viewModel.setUserNote(item.tx, note) {}
            viewModel.recategorize(RecategorizeScope.Payment(item.tx.key, replaceManual = true))
            session = state.skip()
        },
    )
}

@Composable
private fun ReviewFinished(nothingQueued: Boolean, canGoBack: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.widthIn(max = 480.dp).padding(24.dp).padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            if (nothingQueued) "Nothing to review" else "All reviewed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canGoBack) OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
