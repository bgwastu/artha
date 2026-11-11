package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.INITIAL_DEFAULT_CATEGORIES
import net.wastu.artha.data.analysis.INITIAL_INCOME_CATEGORIES
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.components.BannerHost
import net.wastu.artha.ui.components.collectOrDefault
import net.wastu.artha.ui.groupRecords

class TransactionDetailsState internal constructor() {
    internal var editor by mutableStateOf<String?>(null)
    internal var saving by mutableStateOf(false)
    internal var categoryDraft by mutableStateOf("")
    internal var noteDraft by mutableStateOf("")
    internal var saveError by mutableStateOf<String?>(null)
    internal var savedFeedback by mutableStateOf<String?>(null)
    /** Index into the group records being viewed, or -1 for the transaction itself. */
    internal var recordIndex by mutableStateOf(-1)
    internal var sourceExpanded by mutableStateOf(false)
    /** Last measured height of the transaction page; a record page keeps it so the sheet doesn't jump. */
    internal var pageHeightPx by mutableStateOf(0)

    internal fun chooseCategory(
        category: String,
        current: String,
        persist: (String, (Result<Unit>) -> Unit) -> Unit,
        isBusy: Boolean = false,
        onDone: () -> Unit,
    ) {
        if (isBusy || saving) return
        if (category == current) { onDone(); return }
        categoryDraft = category
        saveError = null
        saving = true
        persist(category) { result ->
            saving = false
            if (result.isSuccess) {
                savedFeedback = "Category saved"
                onDone()
            } else saveError = "Couldn’t save category. Try again."
        }
    }

    internal fun onPaymentBusy() {
        if (editor != null) {
            editor = null
            saveError = null
            saving = false
            savedFeedback = "Categorizing. Try again in a moment"
        }
    }

    internal fun dismiss(onClose: () -> Unit) {
        if (!saving) {
            when {
                editor != null -> editor = null
                recordIndex >= 0 -> recordIndex = -1
                else -> onClose()
            }
        }
    }

    internal companion object {
        val saver = listSaver<TransactionDetailsState, Any>(
            save = { listOf(it.editor.orEmpty(), it.categoryDraft, it.noteDraft, it.saveError.orEmpty(),
                it.savedFeedback.orEmpty(), it.recordIndex, it.sourceExpanded) },
            restore = { values -> TransactionDetailsState().apply {
                editor = (values[0] as String).ifEmpty { null }
                categoryDraft = values[1] as String
                noteDraft = values[2] as String
                saveError = (values[3] as String).ifEmpty { null }
                savedFeedback = (values[4] as String).ifEmpty { null }
                recordIndex = values[5] as Int
                sourceExpanded = values[6] as Boolean
            } },
        )
    }
}

@Composable
fun rememberTransactionDetailsState(transactionKey: String): TransactionDetailsState =
    rememberSaveable(transactionKey, saver = TransactionDetailsState.saver) { TransactionDetailsState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsSheet(
    transaction: UnifiedTransaction,
    viewModel: ArthaViewModel? = null,
    state: TransactionDetailsState = rememberTransactionDetailsState(transaction.key),
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        // Back from a record is handled inside the sheet, so a dismiss here is a swipe or scrim
        // tap and closes everything; editors still step back first.
        onDismissRequest = { if (state.editor == null) { state.recordIndex = -1; onDismiss() } else state.dismiss(onDismiss) },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // Material hides the sheet before its Back callback. Keep it visible
            // while that callback returns an editor (with unsaved drafts) to the details.
            confirmValueChange = { value ->
                value != SheetValue.Hidden || (state.editor == null && !state.saving)
            },
        ),
    ) {
        // The sheet's own Back would be vetoed while an editor is open, so step back here instead:
        // editor -> details, record -> details. Back is ignored while a save is in flight.
        BackHandler(enabled = state.editor != null || state.recordIndex >= 0) { state.dismiss(onDismiss) }
        TransactionDetailsBody(transaction, viewModel, state, showRecordBack = true)
    }
}

/** Inline pane with its own scrolling, editor, IME padding and Close action. */
@Composable
fun TransactionDetailsContent(
    transaction: UnifiedTransaction,
    viewModel: ArthaViewModel?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    state: TransactionDetailsState = rememberTransactionDetailsState(transaction.key),
) {
    val focus = LocalFocusManager.current
    val close = { if (!state.saving) { focus.clearFocus(); state.dismiss(onClose) } }
    BackHandler(onBack = close)
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Expense", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = close, enabled = !state.saving) {
                Text(if (state.editor == null && state.recordIndex < 0) "Close" else "Back")
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TransactionDetailsBody(transaction, viewModel, state)
        }
    }
}

@Composable
private fun TransactionDetailsBody(
    transaction: UnifiedTransaction,
    viewModel: ArthaViewModel?,
    state: TransactionDetailsState,
    showRecordBack: Boolean = false,
) {
    val snapshot = viewModel?.analysisSnapshot.collectOrDefault(null).value
    val projected by viewModel?.allTransactions.collectOrDefault(emptyList())
    val record = projected.firstOrNull { it.key == transaction.key } ?: projected.firstOrNull {
        it.provider == transaction.provider && it.providerTransactionId == transaction.providerTransactionId
    } ?: transaction
    val availableCategories = snapshot?.categoriesFor(record.type)
        ?: if (record.type == TransactionType.INCOME) INITIAL_INCOME_CATEGORIES else INITIAL_DEFAULT_CATEGORIES
    val currentCategory = snapshot?.categories?.get(record.key) ?: record.category ?: "Uncategorized"
    val currentNote = if (snapshot?.updatedAt?.containsKey("note:${record.key}") == true)
        snapshot.userNotes[record.key].orEmpty() else record.userNote.orEmpty()
    val categorySource = if (snapshot != null) categorySource(record, snapshot) else CategorySource.NONE

    val categoryProgress by viewModel?.categoryProgress.collectOrDefault(CategoryProgress())
    val paymentState = paymentCategoryState(record.key, categoryProgress)
    val isBusy = paymentState != PaymentCategoryState.IDLE
    LaunchedEffect(isBusy) {
        if (isBusy) state.onPaymentBusy()
    }

    val focus = LocalFocusManager.current
    val closeEditor = { focus.clearFocus(); state.editor = null; state.saveError = null }
    val records = remember(record.rawPayload) { groupRecords(record) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth()) {
        when {
            state.editor == "category" -> CategoryEditorPane(
                record, viewModel, state, snapshot, currentCategory, availableCategories, isBusy, snackbarHostState, scope, closeEditor,
            )
            state.editor == null && state.recordIndex in records.indices -> GroupRecordDetails(
                records[state.recordIndex],
                minHeight = with(LocalDensity.current) { state.pageHeightPx.toDp() },
                showBack = showRecordBack,
                onBack = { state.recordIndex = -1 },
            )
            state.editor == "note" -> NoteEditorPane(record, viewModel, state, isBusy, closeEditor)
            else -> TransactionDetailPage(
                record, records, viewModel, state, snapshot, snackbarHostState,
                categorySource, currentCategory, currentNote, paymentState,
            )
        }
        BannerHost(
            state = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}
