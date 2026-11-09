package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.INITIAL_DEFAULT_CATEGORIES
import net.wastu.artha.data.analysis.INITIAL_INCOME_CATEGORIES
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.categoryReasonText
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryStateLabel
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.groupRecords
import net.wastu.artha.ui.originalPaymentMoney
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import net.wastu.artha.ui.transactionHumanLine
import net.wastu.artha.ui.transactionTags
import org.json.JSONException
import org.json.JSONObject

data class PeerPrompt(val category: String, val merchant: String, val peerCount: Int)
class TransactionDetailsState internal constructor() {
    internal var editor by mutableStateOf<String?>(null)
    internal var saving by mutableStateOf(false)
    internal var categoryDraft by mutableStateOf("")
    internal var noteDraft by mutableStateOf("")
    internal var saveError by mutableStateOf<String?>(null)
    internal var savedFeedback by mutableStateOf<String?>(null)
    internal var peerPrompt by mutableStateOf<PeerPrompt?>(null)
    /** Index into the group records being viewed, or -1 for the transaction itself. */
    internal var recordIndex by mutableStateOf(-1)
    internal var sourceExpanded by mutableStateOf(false)
    /** Last measured height of the transaction page; a record page keeps it so the sheet doesn't jump. */
    internal var pageHeightPx by mutableStateOf(0)

    internal fun chooseCategory(
        category: String,
        current: String,
        persist: (String, (Result<Unit>) -> Unit) -> Unit,
        onDone: () -> Unit,
    ) = chooseCategory(category, current, isBusy = false, persist = persist, onDone = onDone)

    internal fun chooseCategory(
        category: String,
        current: String,
        isBusy: Boolean,
        persist: (String, (Result<Unit>) -> Unit) -> Unit,
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

    internal fun applyMerchantPrompt(
        isBusy: Boolean = false,
        action: (PeerPrompt) -> Unit,
    ) {
        if (isBusy) return
        peerPrompt?.let(action)
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
        BackHandler(enabled = state.editor == null && state.recordIndex >= 0) { state.recordIndex = -1 }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransactionDetailsBody(
    transaction: UnifiedTransaction,
    viewModel: ArthaViewModel?,
    state: TransactionDetailsState,
    showRecordBack: Boolean = false,
) {
    val snapshot = viewModel?.analysisSnapshot?.collectAsStateWithLifecycle()?.value
    val projected = viewModel?.allTransactions?.collectAsStateWithLifecycle()?.value
    val record = projected?.firstOrNull { it.key == transaction.key } ?: projected?.firstOrNull {
        it.provider == transaction.provider && it.providerTransactionId == transaction.providerTransactionId
    } ?: transaction
    val recordType = record.type
    val availableCategories = snapshot?.categoriesFor(recordType)
        ?: if (recordType == TransactionType.INCOME) INITIAL_INCOME_CATEGORIES else INITIAL_DEFAULT_CATEGORIES
    val currentCategory = snapshot?.categories?.get(record.key) ?: record.category ?: "Uncategorized"
    val currentNote = if (snapshot?.updatedAt?.containsKey("note:${record.key}") == true)
        snapshot.userNotes[record.key].orEmpty() else record.userNote.orEmpty()
    val categorySource = if (snapshot != null) categorySource(record, snapshot) else CategorySource.NONE
    val categoryProgress by viewModel?.categoryProgress?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(CategoryProgress()) }
    val paymentState = paymentCategoryState(record.key, categoryProgress)
    val isBusy = paymentState != PaymentCategoryState.IDLE
    val busyLabel = paymentCategoryStateLabel(paymentState)
    LaunchedEffect(isBusy) {
        if (isBusy) {
            state.onPaymentBusy()
        }
    }
    val focus = LocalFocusManager.current
    val closeEditor = { focus.clearFocus(); state.editor = null; state.saveError = null }
    val payload = remember(record.rawPayload) {
        try { JSONObject(record.rawPayload) } catch (_: JSONException) { null }
    }
    val pickup = payload?.detailText("pickupLocation")
    val dropoff = payload?.detailText("dropoffLocation")
    val notes = payload?.detailText("notes")
    val counterpart = payload?.detailText("counterpart")
    val items = payload?.optJSONArray("items")
    val records = remember(record.rawPayload) { groupRecords(record) }
    val tags = remember(record) { transactionTags(record) }
    val detailViewItems = payload?.optJSONArray("detailViewItems")
    val showRoute = !pickup.isNullOrBlank() && !dropoff.isNullOrBlank()
    val showOrder = items != null && items.length() > 0
    val showGoPayDetails = detailViewItems != null && detailViewItems.length() > 0 && !showRoute && !showOrder
    val credit = record.direction == Direction.CREDIT
    val isReversal = record.isReversedRecord
    val humanTitle = remember(record.key, record.description, record.rawPayload) { transactionHumanLine(record) }
    val shopLabel = payload?.detailText("shopName") ?: payload?.detailText("restaurantName")
    val showShopInItems = showOrder && shopLabel != null && !humanTitle.startsWith(shopLabel, ignoreCase = true)
    val paymentLabel = payload?.detailText("sourcePaymentMethod") ?: payload?.detailText("paymentMethod")
    val showCounterpart = counterpart != null && !humanTitle.contains(counterpart, ignoreCase = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showReplaceManualDialog by remember(record.key) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        if (state.editor == "category") {
            val selectCategory: (String) -> Unit = { category ->
                val merchant = CategoryReviewLogic.transactionMerchant(record)
                state.chooseCategory(category, currentCategory, isBusy = isBusy,
                    persist = { selected, complete ->
                        if (viewModel == null) complete(Result.failure(IllegalStateException("Editor unavailable")))
                        else viewModel.setTransactionCategory(record, selected) { result ->
                            complete(result)
                            if (result.isSuccess) {
                                state.savedFeedback = null
                                val peerCount = viewModel.merchantPeerCount(record, selected)
                                scope.launch {
                                    val snackbarResult = snackbarHostState.showSnackbar(
                                        message = "Category saved",
                                        actionLabel = if (peerCount > 0) "Apply to $peerCount more" else null,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed && peerCount > 0) {
                                        viewModel.applyToMerchant(record, selected) { applyResult ->
                                            val updated = applyResult.getOrDefault(peerCount)
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    message = "Updated $updated payments",
                                                    duration = SnackbarDuration.Short,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }, onDone = closeEditor)
            }
        CategoryPickerView(
            currentCategory = currentCategory,
            availableCategories = (availableCategories + currentCategory).distinct(),
            categoryIcons = snapshot?.categoryIcons.orEmpty(),
            saving = state.saving,
            error = state.saveError,
            onSelectCategory = selectCategory,
            onBack = closeEditor,
            onRetry = { selectCategory(state.categoryDraft) },
        )
    } else if (state.editor == null && state.recordIndex in records.indices) {
        GroupRecordDetails(records[state.recordIndex], minHeight = with(LocalDensity.current) { state.pageHeightPx.toDp() },
            showBack = showRecordBack, onBack = { state.recordIndex = -1 })
    } else if (state.editor == "note") {
        TransactionNoteEditor(
            text = state.noteDraft,
            onTextChange = { state.noteDraft = it; state.saveError = null },
            saving = state.saving,
            error = state.saveError,
            onDismiss = closeEditor,
            onSave = {
                if (isBusy) return@TransactionNoteEditor
                state.saving = true
                viewModel?.setUserNote(record, state.noteDraft) { result ->
                    state.saving = false
                    if (result.isSuccess) {
                        state.savedFeedback = "Note saved"
                        closeEditor()
                    } else state.saveError = "Couldn’t save note. Try again."
                }
            },
        )
    } else {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { state.pageHeightPx = it.height }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SourceHeader(record.provider, formatDate(record.occurredAt))
                Text(
                    paymentRowAmount(record),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = if (!credit && isReversal) MaterialTheme.colorScheme.onSurfaceVariant
                        else if (credit) incomeColor() else expenseColor(),
                )
                // A status tag (Reversed, Transfer, ...) already says why this is outside spending.
                if (tags.none { it.kind in StatusTagKinds }) transactionAccountingLabel(record)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                // With records shown, the original charge and net already appear in the Records section.
                if (!credit && isReversal && records.isEmpty()) DetailRow("Original charge", formatMoney(originalPaymentMoney(record)))
                val feedback = state.savedFeedback
                if (feedback != null && feedback != "Category saved" && !feedback.startsWith("Updated ")) {
                    SaveFeedback(feedback, false)
                }
                Text(humanTitle, style = MaterialTheme.typography.titleLargeEmphasized)
                DisableSelection {
                    TagRow(tags)
                    if (records.isNotEmpty()) GroupRecordsSection(records, record.occurredAt, linkedRecordsExplanation(record)) { state.recordIndex = it }

                    if (recordType != TransactionType.TRANSFER) {
                        Column(Modifier.padding(top = if (records.isNotEmpty()) SectionGap else 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DetailEditRow(
                                label = "Category", value = currentCategory,
                                supportingContent = if (isBusy && busyLabel != null) {
                                    {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            LoadingIndicator(
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                busyLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                } else when (categorySource) {
                                    CategorySource.MANUAL -> ({
                                        Text("Set by you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    })
                                    CategorySource.AI -> ({
                                        Text(sureText("Suggested by AI", snapshot?.aiRecords?.get(record.key)?.confidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    })
                                    CategorySource.MERCHANT -> ({
                                        Text("Same merchant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    })
                                    CategorySource.REVIEW -> ({
                                        Text(sureText("Needs review", snapshot?.aiRecords?.get(record.key)?.confidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                    })
                                    CategorySource.FAILED -> ({
                                        Text("Couldn't categorize", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    })
                                    CategorySource.PENDING -> ({
                                        Text("Waiting for AI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    })
                                    CategorySource.NONE -> null
                                },
                                icon = categoryIconFor(currentCategory, snapshot?.categoryIcons.orEmpty()),
                                enabled = viewModel?.analysisStore != null && !isBusy,
                            ) {
                                if (!isBusy) {
                                    state.categoryDraft = currentCategory; state.saveError = null; state.savedFeedback = null; state.editor = "category"
                                }
                            }
                            if (snapshot != null) {
                                val reason = categoryReasonText(snapshot, record.key)
                                if (reason != null && !isBusy) Text(
                                    reason,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 36.dp),
                                )
                                FlowRow(
                                    modifier = Modifier.padding(start = 36.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AssistChip(
                                        onClick = {
                                            if (categorySource == CategorySource.MANUAL) {
                                                showReplaceManualDialog = true
                                            } else {
                                                viewModel?.recategorize(RecategorizeScope.Payment(record.key))
                                            }
                                        },
                                        enabled = !isBusy,
                                        label = { Text(if (isBusy) "Categorizing" else "Re-categorize") },
                                        leadingIcon = {
                                            if (isBusy) {
                                                LoadingIndicator(
                                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                                )
                                            } else {
                                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                                            }
                                        },
                                    )
                                    if (categorySource == CategorySource.REVIEW && !isBusy) {
                                        SuggestionChip(
                                            onClick = {
                                                viewModel?.confirmCategory(record, currentCategory) { result ->
                                                    if (result.isSuccess) {
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar("Category saved")
                                                        }
                                                    }
                                                }
                                            },
                                            label = { Text("Looks right") },
                                            icon = {
                                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(SuggestionChipDefaults.IconSize))
                                            },
                                        )
                                    }
                                }
                                if (showReplaceManualDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showReplaceManualDialog = false },
                                        title = { Text("Replace your pick?") },
                                        text = { Text("AI will choose a new category for this payment.") },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    showReplaceManualDialog = false
                                                    viewModel?.recategorize(RecategorizeScope.Payment(record.key, replaceManual = true))
                                                },
                                            ) {
                                                Text("Replace")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showReplaceManualDialog = false }) {
                                                Text("Cancel")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (viewModel?.analysisStore != null && records.isEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                TransactionType.EXPENSE to "Expense",
                                TransactionType.INCOME to "Income",
                                TransactionType.TRANSFER to "Transfer",
                            ).forEach { (type, label) ->
                                FilterChip(
                                    selected = recordType == type,
                                    onClick = { if (recordType != type) viewModel.setTransactionType(record, type) },
                                    label = { Text(label) },
                                )
                            }
                            if (snapshot?.typeOverrides?.containsKey(record.key) == true) {
                                TextButton(onClick = { viewModel.setTransactionType(record, null) }) { Text("Reset") }
                            }
                        }
                    }
                    DetailEditRow(
                        label = "Note", value = currentNote.ifBlank { "Add note" },
                        icon = Icons.Default.EditNote,
                        enabled = viewModel?.analysisStore != null && !isBusy,
                        supporting = paymentNoteRowSupporting(paymentState),
                    ) {
                        if (!isBusy) {
                            state.noteDraft = currentNote; state.saveError = null; state.savedFeedback = null; state.editor = "note"
                        }
                    }
                }

                if (showRoute) {
                    DetailCard("Trip") {
                        payload?.detailText("serviceTier")?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    MaterialTheme.shapes.small,
                                )
                                .padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = "Pickup",
                                    tint = incomeColor(),
                                    modifier = Modifier.size(20.dp).padding(top = 2.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Pick-up",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        pickup.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .padding(start = 9.dp, top = 4.dp, bottom = 4.dp)
                                    .width(2.dp)
                                    .height(20.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = "Dropoff",
                                    tint = expenseColor(),
                                    modifier = Modifier.size(20.dp).padding(top = 2.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Drop-off",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        dropoff.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                if (showOrder) {
                    DetailCard("Items") {
                        if (showShopInItems) {
                            Text(shopLabel.orEmpty(), style = MaterialTheme.typography.titleSmall)
                        }
                        for (index in 0 until (items?.length() ?: 0)) {
                            val item = items?.optJSONObject(index) ?: continue
                            val name = item.detailText("name") ?: continue
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                    item.detailText("qty")?.let {
                                        Text("Qty $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                item.detailText("price")?.let { Text(itemPriceText(it), style = MaterialTheme.typography.titleSmall) }
                            }
                        }
                        notes?.let {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                Column(Modifier.weight(1f)) {
                                    Text("Order Note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                if (showGoPayDetails) {
                    DetailCard("Payment details") {
                        for (index in 0 until detailViewItems!!.length()) {
                            val row = detailViewItems.optJSONObject(index) ?: continue
                            val label = row.detailText("title") ?: row.detailText("label")
                            val value = row.detailText("value") ?: continue
                            if (label != null) {
                                DetailRow(label, value)
                            } else {
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // Set apart from the sections above, so these read as facts about the whole payment.
                val hasFacts = paymentLabel != null || (showCounterpart && counterpart != null) || record.pocket != null || (notes != null && !showOrder)
                if (hasFacts) Column(Modifier.padding(top = SectionGap), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    paymentLabel?.let { DetailRow("Payment method", it) }
                    if (showCounterpart) counterpart?.let { DetailRow("Counterpart", it) }
                    record.pocket?.let { DetailRow("Pocket", it.name) }
                    if (notes != null && !showOrder) DetailRow("Source note", notes)
                }

                TextButton(onClick = { state.sourceExpanded = !state.sourceExpanded }) {
                    Text(if (state.sourceExpanded) "Hide source details" else "Source details")
                }
                if (state.sourceExpanded) {
                    DetailRow("Reference", record.providerTransactionId)
                    record.cardLast4?.let { DetailRow("Card", it) }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

internal fun JSONObject.detailText(key: String): String? {
    if (isNull(key)) return null
    val value = opt(key)
    if (value is JSONObject || value is org.json.JSONArray) return null
    return value?.toString()?.trim()?.takeIf { it.isNotBlank() }
}
