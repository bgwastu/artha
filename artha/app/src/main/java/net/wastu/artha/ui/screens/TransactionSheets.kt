package net.wastu.artha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.aiCategoryReason
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.data.analysis.AI_REASON_PREFIX
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryStateLabel
import net.wastu.artha.data.analysis.MERCHANT_REASON_PREFIX
import net.wastu.artha.data.analysis.RecategorizeScope
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.transactionHumanLine
import net.wastu.artha.ui.originalPaymentMoney
import net.wastu.artha.ui.GroupRecord
import net.wastu.artha.ui.TransactionTag
import net.wastu.artha.ui.TransactionTagKind
import net.wastu.artha.ui.formatTime
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.ui.formatMinorAmount
import net.wastu.artha.ui.groupNetMinor
import net.wastu.artha.ui.groupRecords
import net.wastu.artha.ui.transactionTags
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import java.time.Instant
import org.json.JSONObject
import org.json.JSONException
import androidx.compose.ui.semantics.selected

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
    viewModel: ArthaViewModel? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    state: TransactionDetailsState = rememberTransactionDetailsState(transaction.key),
) {
    val focus = LocalFocusManager.current
    val close = { if (!state.saving) { focus.clearFocus(); state.dismiss(onClose) } }
    BackHandler(onBack = close)
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Transaction", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
    val availableCategories = snapshot?.customCategories ?: net.wastu.artha.data.analysis.INITIAL_DEFAULT_CATEGORIES
    val projected = viewModel?.allTransactions?.collectAsStateWithLifecycle()?.value
    val record = projected?.firstOrNull { it.key == transaction.key } ?: projected?.firstOrNull {
        it.provider == transaction.provider && it.providerTransactionId == transaction.providerTransactionId
    } ?: transaction
    val currentCategory = snapshot?.categories?.get(record.key) ?: record.category ?: "Uncategorized"
    val currentNote = if (snapshot?.updatedAt?.containsKey("note:${record.key}") == true)
        snapshot.userNotes[record.key].orEmpty() else record.userNote.orEmpty()
    val categorySource = if (snapshot != null && snapshot.analysisEnabled) categorySource(record, snapshot) else CategorySource.NONE
    val aiReason = if (categorySource == CategorySource.AI && snapshot != null) aiCategoryReason(snapshot, record.key) else null
    val categoryProgress by viewModel?.categoryProgress?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(CategoryProgress()) }
    val paymentState = paymentCategoryState(record.key, categoryProgress)
    val isBusy = paymentState != PaymentCategoryState.IDLE
    val busyLabel = paymentCategoryStateLabel(paymentState)
    LaunchedEffect(isBusy) {
        if (isBusy) {
            state.onPaymentBusy()
        }
    }
    val rawReason = snapshot?.reasons?.get(record.key) ?: record.aiReasoning
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

    if (state.editor == "category") {
        val selectCategory: (String) -> Unit = { category ->
            val merchant = CategoryReviewLogic.transactionMerchant(record)
            state.chooseCategory(category, currentCategory, isBusy = isBusy,
                persist = { selected, complete ->
                    if (viewModel == null) complete(Result.failure(IllegalStateException("Editor unavailable")))
                    else viewModel.setTransactionCategory(record, selected) { result ->
                        complete(result)
                        if (result.isSuccess) {
                            val peerCount = viewModel.merchantPeerCount(record, selected)
                            if (peerCount > 0) {
                                state.peerPrompt = PeerPrompt(selected, merchant, peerCount)
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SourceHeader(record.provider, formatDate(record.occurredAt))
                Text(
                    paymentRowAmount(record),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (!credit && isReversal) MaterialTheme.colorScheme.onSurfaceVariant
                        else if (credit) incomeColor() else expenseColor(),
                )
                // A status tag (Reversed, Transfer, ...) already says why this is outside spending.
                if (tags.none { it.kind in StatusTagKinds }) transactionAccountingLabel(record)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                // With records shown, the original charge and net already appear in the Records section.
                if (!credit && isReversal && records.isEmpty()) DetailRow("Original charge", formatMoney(originalPaymentMoney(record)))
                state.savedFeedback?.let { SaveFeedback(it, false) }
                if (!isBusy) {
                    state.peerPrompt?.let { prompt ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Apply ${prompt.category} to ${prompt.peerCount} other ${prompt.merchant} payments?",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = { state.peerPrompt = null }) {
                                        Text("Dismiss")
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            state.applyMerchantPrompt(isBusy) { p ->
                                                val count = p.peerCount
                                                viewModel?.applyToMerchant(record, p.category) { result ->
                                                    state.peerPrompt = null
                                                    val updated = result.getOrDefault(count)
                                                    state.savedFeedback = "Updated $updated payments"
                                                }
                                            }
                                        },
                                        enabled = !isBusy,
                                    ) {
                                        Text("Apply")
                                    }
                                }
                            }
                        }
                    }
                }
                Text(humanTitle, style = MaterialTheme.typography.titleLarge)
                TagRow(tags)
                if (records.isNotEmpty()) GroupRecordsSection(records, record.occurredAt, linkedRecordsExplanation(record)) { state.recordIndex = it }

                if (!credit && snapshot?.analysisEnabled != false) {
                    DetailEditRow(
                        label = "Category", value = currentCategory,
                        supportingContent = if (isBusy && busyLabel != null) {
                            {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                    Text(busyLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else when (categorySource) {
                            CategorySource.MANUAL -> ({
                                Text("Set by you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            })
                            CategorySource.AI -> ({
                                Text("Suggested by AI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val reason = rawReason?.removePrefix(AI_REASON_PREFIX)?.takeIf { it.isNotBlank() }
                                if (reason != null) {
                                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            })
                            CategorySource.MERCHANT -> ({
                                Text("Same as earlier payments from this merchant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val reason = rawReason?.removePrefix(MERCHANT_REASON_PREFIX)?.takeIf { it.isNotBlank() }
                                if (reason != null) {
                                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            })
                            CategorySource.REVIEW -> ({
                                Text("Needs review", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                val reason = rawReason?.removePrefix(AI_REASON_PREFIX)?.takeIf { it.isNotBlank() }
                                if (reason != null) {
                                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            })
                            CategorySource.FAILED -> ({
                                Text("AI couldn't categorize", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                val failText = snapshot?.categoryFailures?.get(record.key)?.takeIf { it.isNotBlank() }
                                if (failText != null) {
                                    Text(failText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
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
                    if (!isBusy) {
                        var showReplaceManualDialog by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (categorySource == CategorySource.REVIEW) {
                                TextButton(
                                    onClick = {
                                        if (isBusy) return@TextButton
                                        viewModel?.confirmCategory(record, currentCategory) { result ->
                                            if (result.isSuccess) {
                                                state.savedFeedback = "Category saved"
                                            }
                                        }
                                    },
                                ) {
                                    Text("Looks right")
                                }
                            }
                            TextButton(
                                onClick = {
                                    if (isBusy) return@TextButton
                                    if (categorySource == CategorySource.MANUAL) {
                                        showReplaceManualDialog = true
                                    } else {
                                        viewModel?.recategorize(RecategorizeScope.Payment(record.key))
                                    }
                                },
                            ) {
                                Text("Re-categorize with AI")
                            }
                        }

                        if (showReplaceManualDialog) {
                            AlertDialog(
                                onDismissRequest = { showReplaceManualDialog = false },
                                title = { Text("Replace your pick?") },
                                text = { Text("AI will suggest a category for this payment.") },
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
                DetailEditRow(
                    label = "Note", value = currentNote.ifBlank { "Add note" },
                    icon = Icons.Default.EditNote,
                    enabled = viewModel?.analysisStore != null && !isBusy,
                    supporting = if (isBusy) "Available after categorizing" else null,
                ) {
                    if (!isBusy) {
                        state.noteDraft = currentNote; state.saveError = null; state.savedFeedback = null; state.editor = "note"
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
                                    RoundedCornerShape(12.dp),
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
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                item.detailText("qty")?.let {
                                    Text("Quantity: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                item.detailText("price")?.let { DetailRow("Item price", itemPriceText(it)) }
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

                paymentLabel?.let { DetailRow("Payment method", it) }
                if (showCounterpart) counterpart?.let { DetailRow("Counterpart", it) }
                record.pocket?.let { DetailRow("Pocket", it.name) }
                if (notes != null && !showOrder) DetailRow("Source note", notes)

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
}

private fun JSONObject.detailText(key: String): String? {
    if (isNull(key)) return null
    val value = opt(key)
    if (value is JSONObject || value is org.json.JSONArray) return null
    return value?.toString()?.trim()?.takeIf { it.isNotBlank() }
}

/** Providers store item prices as whole rupiah, sometimes with an "Rp" prefix; show them like other amounts. */
internal fun itemPriceText(raw: String): String {
    val digits = raw.trim().removePrefix("Rp").trim()
    val rupiah = digits.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toBigIntegerOrNull() ?: return raw
    return formatMinorAmount(rupiah.multiply(java.math.BigInteger.valueOf(100)))
}

private val StatusTagKinds = setOf(TransactionTagKind.REVERSED, TransactionTagKind.REFUND,
    TransactionTagKind.TRANSFER, TransactionTagKind.PARTIAL_REFUND)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRow(tags: List<TransactionTag>) {
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag -> TransactionTagPill(tag, Modifier.height(20.dp).semantics { contentDescription = tag.description }, compact = false) }
    }
}

@Composable
private fun SourceHeader(provider: Provider, supporting: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(provider.iconRes), contentDescription = null,
            modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.medium))
        Column {
            Text(provider.displayName, style = MaterialTheme.typography.titleSmall)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The source records behind one reconciled row, each openable, with a net line for refunds. */
@Composable
private fun GroupRecordsSection(records: List<GroupRecord>, parentTime: Instant, explanation: String?, onOpen: (Int) -> Unit) {
    val parentDay = parentTime.atZone(AccountingTime.zone).toLocalDate()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Records", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            Column {
                records.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListItem(
                        modifier = Modifier.clickable(onClickLabel = "Open record") { onOpen(index) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        overlineContent = { Text(item.role) },
                        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val at = item.transaction.occurredAt
                            Text(if (at.atZone(AccountingTime.zone).toLocalDate() == parentDay) formatTime(at) else formatDate(at))
                        },
                        leadingContent = {
                            Image(painterResource(item.transaction.provider.iconRes), item.transaction.provider.displayName,
                                Modifier.size(32.dp).clip(MaterialTheme.shapes.small))
                        },
                        trailingContent = {
                            Text(item.signedAmount, style = MaterialTheme.typography.labelLarge,
                                color = if (item.transaction.direction == Direction.CREDIT) incomeColor() else MaterialTheme.colorScheme.onSurface)
                        },
                    )
                }
                groupNetMinor(records)?.let { net ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val currency = records.first().transaction.money.currencyCode
                    val amount = formatMinorAmount(java.math.BigInteger.valueOf(kotlin.math.abs(net)), currency)
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("Net", fontWeight = FontWeight.SemiBold) },
                        trailingContent = {
                            Text(when { net > 0 -> "−$amount"; net < 0 -> "+$amount"; else -> amount },
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = if (net < 0) incomeColor() else MaterialTheme.colorScheme.onSurface)
                        },
                    )
                }
            }
        }
    }
}

/** One source record on its own: what that source saw, before reconciliation merged it. */
@Composable
private fun GroupRecordDetails(item: GroupRecord, minHeight: androidx.compose.ui.unit.Dp, showBack: Boolean, onBack: () -> Unit) {
    val tx = item.transaction
    val payload = remember(tx.rawPayload) { runCatching { JSONObject(tx.rawPayload) }.getOrNull() }
    Column(Modifier.fillMaxWidth().heightIn(min = minHeight)) {
        // Pinned header; the 12dp start inset puts the arrow glyph on the 24dp content edge.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (showBack) 12.dp else 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to transaction") }
                Spacer(Modifier.width(4.dp))
            }
            Text(item.role, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SourceHeader(tx.provider, formatDate(tx.occurredAt))
            Text(item.signedAmount, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = if (tx.direction == Direction.CREDIT) incomeColor() else expenseColor())
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            TagRow(item.tags)
            DetailRow("Reference", tx.providerTransactionId)
            (payload?.detailText("sourcePaymentMethod") ?: payload?.detailText("paymentMethod"))?.let { DetailRow("Payment method", it) }
            payload?.detailText("counterpart")?.takeUnless { item.title.contains(it, ignoreCase = true) }?.let { DetailRow("Counterpart", it) }
            tx.pocket?.let { DetailRow("Pocket", it.name) }
            payload?.detailText("notes")?.let { DetailRow("Source note", it) }
            tx.description.takeUnless { item.title.equals(it, ignoreCase = true) }?.let { DetailRow("Source description", it) }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailEditRow(
    label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, supporting: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            if (supportingContent != null) {
                supportingContent()
            } else {
                supporting?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (enabled) Icon(Icons.Default.ChevronRight, contentDescription = "Edit $label")
    }
}

@Composable
private fun SaveFeedback(message: String, error: Boolean) {
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CategoryPickerView(
    currentCategory: String,
    availableCategories: List<String>,
    categoryIcons: Map<String, String>,
    onSelectCategory: (String) -> Unit,
    saving: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredCategories = remember(searchQuery, availableCategories) {
        if (searchQuery.isBlank()) {
            availableCategories
        } else {
            availableCategories.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }
    val queryTrimmed = searchQuery.trim()
    val isExactMatch = availableCategories.any { it.equals(queryTrimmed, ignoreCase = true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header with Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, enabled = !saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Category",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (saving) CircularProgressIndicator(Modifier.size(20.dp))
        }

        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search categories") },
            enabled = !saving,
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }, enabled = !saving) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            SaveFeedback(it, true)
            TextButton(onClick = onRetry, enabled = !saving) { Text("Retry") }
        }

        // A typed category can be assigned without changing the shared picker.
        if (queryTrimmed.isNotBlank() && !isExactMatch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(enabled = !saving) {
                        onSelectCategory(queryTrimmed)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.AddCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "Use \"$queryTrimmed\"",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Clean flat category rows (no cards on top of cards)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (cat in filteredCategories) {
                val isSelected = cat == currentCategory
                val icon = categoryIconFor(cat, categoryIcons)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else Color.Transparent
                        )
                        .semantics { selected = isSelected }
                        .clickable(enabled = !saving) { onSelectCategory(cat) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        cat,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TransactionNoteEditor(
    text: String, onTextChange: (String) -> Unit, saving: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Note", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = text, onValueChange = onTextChange, label = { Text("Personal note") },
            enabled = !saving, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
        )
        Text("Notes add context. Your chosen category stays.", style = MaterialTheme.typography.bodySmall)
        error?.let { SaveFeedback(it, true) }
        if (text.isNotEmpty()) TextButton(onClick = { onTextChange("") }, enabled = !saving) { Text("Clear") }
        EditorActions(saving, onDismiss, onSave)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EditorActions(saving: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
        FilledTonalButton(onClick = onSave, enabled = !saving) { Text(if (saving) "Saving" else "Save") }
        if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

internal fun transactionAccountingLabel(transaction: UnifiedTransaction): String? {
    if (BudgetAnalyzer.isSpending(transaction)) return null
    val payload = try { JSONObject(transaction.rawPayload) } catch (_: JSONException) { null }
    return when {
        transaction.isReversedRecord -> if (transaction.direction == Direction.CREDIT) "Incoming reversal" else "Reversed"
        transaction.direction == Direction.CREDIT -> "Incoming"
        transaction.money.currencyCode != "IDR" -> "Outside IDR spending"
        payload?.optBoolean("isWalletFunding") == true || payload?.optString("code") in BudgetAnalyzer.WALLET_FUNDING_CODES -> "Wallet funding"
        payload?.optBoolean("isPocketTransfer") == true || transaction.description.startsWith("Pocket Transfer:", ignoreCase = true) -> "Pocket transfer"
        payload?.optString("code") in BudgetAnalyzer.INTERNAL_TRANSFER_CODES ||
            payload?.optJSONObject("fields")?.optString("f22") == "INTERNAL" -> "Transfer"
        else -> "Not included in spending"
    }
}

internal fun linkedRecordsExplanation(transaction: UnifiedTransaction): String? {
    val groups = try { JSONObject(transaction.rawPayload).optJSONArray("groupedTransactions") } catch (_: JSONException) { null } ?: return null
    return if (groups.length() > 1 && transaction.canonicalKey != null && BudgetAnalyzer.isSpending(transaction))
        "Counted once in spending." else null
}

internal fun paymentCategoryRowSupporting(state: PaymentCategoryState): String? =
    paymentCategoryStateLabel(state)

internal fun paymentNoteRowSupporting(state: PaymentCategoryState): String? =
    if (state != PaymentCategoryState.IDLE) "Available after categorizing" else null

internal fun canEditTransaction(state: PaymentCategoryState, hasStore: Boolean): Boolean =
    hasStore && state == PaymentCategoryState.IDLE
