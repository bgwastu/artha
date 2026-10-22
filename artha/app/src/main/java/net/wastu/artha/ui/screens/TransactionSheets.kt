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
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import java.time.Instant
import org.json.JSONObject
import org.json.JSONException
import androidx.compose.ui.semantics.selected

class TransactionDetailsState internal constructor() {
    internal var editor by mutableStateOf<String?>(null)
    internal var saving by mutableStateOf(false)
    internal var categoryDraft by mutableStateOf("")
    internal var noteDraft by mutableStateOf("")
    internal var saveError by mutableStateOf<String?>(null)
    internal var savedFeedback by mutableStateOf<String?>(null)
    internal var linkedExpanded by mutableStateOf(false)
    internal var sourceExpanded by mutableStateOf(false)

    internal fun chooseCategory(category: String, current: String,
        persist: (String, (Result<Unit>) -> Unit) -> Unit, onDone: () -> Unit,
    ) {
        if (saving) return
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

    internal fun dismiss(onClose: () -> Unit) {
        if (!saving) {
            if (editor != null) editor = null else onClose()
        }
    }

    internal companion object {
        val saver = listSaver<TransactionDetailsState, Any>(
            save = { listOf(it.editor.orEmpty(), it.categoryDraft, it.noteDraft, it.saveError.orEmpty(),
                it.savedFeedback.orEmpty(), it.linkedExpanded, it.sourceExpanded) },
            restore = { values -> TransactionDetailsState().apply {
                editor = (values[0] as String).ifEmpty { null }
                categoryDraft = values[1] as String
                noteDraft = values[2] as String
                saveError = (values[3] as String).ifEmpty { null }
                savedFeedback = (values[4] as String).ifEmpty { null }
                linkedExpanded = values[5] as Boolean
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
        onDismissRequest = { state.dismiss(onDismiss) },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // Material hides the sheet before its Back callback. Keep it visible
            // while that callback returns an editor to the transaction details.
            confirmValueChange = { value ->
                value != SheetValue.Hidden || (state.editor == null && !state.saving)
            },
        ),
    ) {
        TransactionDetailsBody(transaction, viewModel, state)
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
                Text(if (state.editor == null) "Close" else "Back")
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
    val manualCategory = record.classificationIsManual || snapshot?.manualCategories?.contains(record.key) == true
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
    val grouped = payload?.optJSONArray("groupedTransactions")
    val detailViewItems = payload?.optJSONArray("detailViewItems")
    val showRoute = !pickup.isNullOrBlank() && !dropoff.isNullOrBlank()
    val showOrder = items != null && items.length() > 0
    val showGoPayDetails = detailViewItems != null && detailViewItems.length() > 0 && !showRoute && !showOrder
    val credit = record.direction == Direction.CREDIT
    val isReversal = record.isReversal || record.description.contains("(Reversed)", ignoreCase = true)
    val humanTitle = remember(record.key, record.description, record.rawPayload) { transactionHumanLine(record) }
    val shopLabel = payload?.detailText("shopName") ?: payload?.detailText("restaurantName")
    val showShopInItems = showOrder && shopLabel != null && !humanTitle.startsWith(shopLabel, ignoreCase = true)
    val paymentLabel = payload?.detailText("sourcePaymentMethod") ?: payload?.detailText("paymentMethod")
    val showCounterpart = counterpart != null && !humanTitle.contains(counterpart, ignoreCase = true)

    if (state.editor == "category") {
        val selectCategory: (String) -> Unit = { category ->
            state.chooseCategory(category, currentCategory,
                persist = { selected, complete ->
                    if (viewModel == null) complete(Result.failure(IllegalStateException("Editor unavailable")))
                    else viewModel.setTransactionCategory(record, selected, complete)
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
    } else if (state.editor == "note") {
        TransactionNoteEditor(
            text = state.noteDraft,
            onTextChange = { state.noteDraft = it; state.saveError = null },
            saving = state.saving,
            error = state.saveError,
            onDismiss = closeEditor,
            onSave = {
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(record.provider.iconRes),
                            contentDescription = record.provider.displayName,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                Text(
                    paymentRowAmount(record),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (!credit && isReversal) MaterialTheme.colorScheme.onSurfaceVariant
                        else if (credit) incomeColor() else expenseColor(),
                )
                transactionAccountingLabel(record)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                if (!credit && isReversal) DetailRow("Original charge", formatMoney(originalPaymentMoney(record)))
                state.savedFeedback?.let { SaveFeedback(it, false) }
                Text(humanTitle, style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailBadge(
                        record.provider.displayName,
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    DetailBadge(
                        if (credit) "Incoming" else "Outgoing",
                        if (credit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        if (credit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (record.isQris) {
                        DetailBadge("QRIS", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    if (isReversal) {
                        DetailBadge(if (credit) "Reversal" else "Reversed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (record.cardLast4 != null) {
                        DetailBadge(
                            "Card ${record.cardLast4}",
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    formatDate(record.occurredAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!credit && snapshot?.analysisEnabled != false) {
                    DetailEditRow(
                        label = "Category", value = currentCategory,
                        supporting = if (manualCategory) "Chosen by you" else null,
                        icon = categoryIconFor(currentCategory, snapshot?.categoryIcons.orEmpty()),
                        enabled = viewModel?.analysisStore != null,
                    ) {
                        state.categoryDraft = currentCategory; state.saveError = null; state.savedFeedback = null; state.editor = "category"
                    }
                }
                DetailEditRow(
                    label = "Note", value = currentNote.ifBlank { "Add note" },
                    icon = Icons.Default.EditNote, enabled = viewModel?.analysisStore != null,
                ) {
                    state.noteDraft = currentNote; state.saveError = null; state.savedFeedback = null; state.editor = "note"
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
                                item.detailText("price")?.let { DetailRow("Item price", it) }
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

                if (grouped != null && grouped.length() > 1) {
                    TextButton(onClick = { state.linkedExpanded = !state.linkedExpanded }) {
                        Text(if (state.linkedExpanded) "Hide linked records" else "Linked records (${grouped.length()})")
                    }
                    if (state.linkedExpanded) {
                        linkedRecordsExplanation(record)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        for (i in 0 until grouped.length()) {
                            val item = grouped.optJSONObject(i) ?: continue
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val provider = Provider.entries.firstOrNull { it.name == item.optString("provider") }
                                Text(provider?.displayName ?: "Source record", style = MaterialTheme.typography.titleSmall)
                                Text(linkedSourceRole(item), style = MaterialTheme.typography.labelMedium)
                                item.detailText("description")?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                val epoch = item.optLong("occurredAtEpochMillis")
                                if (epoch > 0L) Text(formatDate(Instant.ofEpochMilli(epoch)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = { state.sourceExpanded = !state.sourceExpanded }) {
                    Text(if (state.sourceExpanded) "Hide source details" else "Source details")
                }
                if (state.sourceExpanded) {
                    DetailRow("Reference", record.providerTransactionId)
                    record.cardLast4?.let { DetailRow("Card", it) }
                    if (grouped != null) for (i in 0 until grouped.length()) {
                        val item = grouped.optJSONObject(i) ?: continue
                        val provider = Provider.entries.firstOrNull { it.name == item.optString("provider") }
                        item.detailText("providerTransactionId")?.let { DetailRow(provider?.displayName ?: "Reference", it) }
                    }
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

@Composable
private fun DetailBadge(label: String, containerColor: Color, contentColor: Color) {
    Surface(color = containerColor, contentColor = contentColor, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
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
    enabled: Boolean, supporting: String? = null, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
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
        transaction.isReversal || transaction.description.contains("(Reversed)", ignoreCase = true) -> if (transaction.direction == Direction.CREDIT) "Incoming reversal" else "Reversed"
        transaction.direction == Direction.CREDIT -> "Incoming"
        transaction.money.currencyCode != "IDR" -> "Outside IDR spending"
        payload?.optBoolean("isWalletFunding") == true -> "Wallet funding"
        payload?.optBoolean("isPocketTransfer") == true || transaction.description.startsWith("Pocket Transfer:", ignoreCase = true) -> "Pocket transfer"
        payload?.optString("code") in setOf("TDD02", "TDC02", "TDC03", "TDC08", "TDC12", "SAD01", "SAD02", "SAC01", "SAC02") ||
            payload?.optJSONObject("fields")?.optString("f22") == "INTERNAL" -> "Transfer"
        else -> "Not included in spending"
    }
}

internal fun linkedRecordsExplanation(transaction: UnifiedTransaction): String? {
    val groups = try { JSONObject(transaction.rawPayload).optJSONArray("groupedTransactions") } catch (_: JSONException) { null } ?: return null
    return if (groups.length() > 1 && transaction.canonicalKey != null && BudgetAnalyzer.isSpending(transaction))
        "Counted once in spending." else null
}

internal fun linkedSourceRole(record: JSONObject): String = when (val role = record.optString("role")) {
    "Payment", "Order details", "Receipt", "Refund", "Original Charge", "Card Payment", "Wallet Payment", "Merchant Payment" -> role
    "Bank Payment" -> if (record.optString("provider") == Provider.JAGO.name) "Bank payment" else "Source record"
    "Marketplace Order" -> "Order details"
    else -> "Source record"
}
