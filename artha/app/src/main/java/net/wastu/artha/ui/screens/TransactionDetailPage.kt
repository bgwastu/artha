package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryStateLabel
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.GroupRecord
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.originalPaymentMoney
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import net.wastu.artha.ui.transactionHumanLine
import net.wastu.artha.ui.transactionTags
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Facts read from a payment's raw payload; each detail card decides from these what to show. */
private class PayloadFacts(payload: JSONObject?, humanTitle: String) {
    val pickup = payload?.detailText("pickupLocation")
    val dropoff = payload?.detailText("dropoffLocation")
    val serviceTier = payload?.detailText("serviceTier")
    val notes = payload?.detailText("notes")
    val paymentLabel = payload?.detailText("sourcePaymentMethod") ?: payload?.detailText("paymentMethod")
    val items: JSONArray? = payload?.optJSONArray("items")?.takeIf { it.length() > 0 }
    private val detailViewItems: JSONArray? = payload?.optJSONArray("detailViewItems")?.takeIf { it.length() > 0 }
    private val shopLabel = payload?.detailText("shopName") ?: payload?.detailText("restaurantName")

    val showRoute = !pickup.isNullOrBlank() && !dropoff.isNullOrBlank()

    /** GoPay detail rows are a fallback for payments with no route and no order items. */
    val goPayDetails: JSONArray? = detailViewItems?.takeIf { !showRoute && items == null }

    /** The shop name, unless the title already starts with it. */
    val shopInItems: String? = shopLabel?.takeIf { items != null && !humanTitle.startsWith(it, ignoreCase = true) }

    /** The counterpart, unless the title already mentions it. */
    val counterpart: String? = payload?.detailText("counterpart")?.takeIf { !humanTitle.contains(it, ignoreCase = true) }
}

/** The scrolling summary of one payment: amount, tags, linked records, category, note and source cards. */
@Composable
internal fun TransactionDetailPage(
    record: UnifiedTransaction,
    records: List<GroupRecord>,
    viewModel: ArthaViewModel?,
    state: TransactionDetailsState,
    snapshot: AnalysisSnapshot?,
    snackbarHostState: SnackbarHostState,
    categorySource: CategorySource,
    currentCategory: String,
    currentNote: String,
    paymentState: PaymentCategoryState,
) {
    val isBusy = paymentState != PaymentCategoryState.IDLE
    val humanTitle = remember(record.key, record.description, record.rawPayload) { transactionHumanLine(record) }
    val facts = remember(record.rawPayload, humanTitle) {
        PayloadFacts(try { JSONObject(record.rawPayload) } catch (_: JSONException) { null }, humanTitle)
    }
    val tags = remember(record) { transactionTags(record) }
    val credit = record.direction == Direction.CREDIT
    val isReversal = record.isReversedRecord
    val canEdit = viewModel?.analysisStore != null

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

                if (record.type != TransactionType.TRANSFER) CategorySection(
                    record = record, viewModel = viewModel, snapshot = snapshot,
                    categorySource = categorySource, currentCategory = currentCategory,
                    isBusy = isBusy, busyLabel = paymentCategoryStateLabel(paymentState), hasRecords = records.isNotEmpty(),
                    snackbarHostState = snackbarHostState,
                    onEdit = {
                        if (!isBusy) { state.categoryDraft = currentCategory; state.saveError = null; state.savedFeedback = null; state.editor = "category" }
                    },
                )
                if (canEdit && records.isEmpty()) TransactionTypeChips(
                    current = record.type,
                    overridden = snapshot?.typeOverrides?.containsKey(record.key) == true,
                    onSelect = { viewModel?.setTransactionType(record, it) },
                    onReset = { viewModel?.setTransactionType(record, null) },
                )
                DetailEditRow(
                    label = "Note", value = currentNote.ifBlank { "Add note" },
                    icon = Icons.Default.EditNote,
                    enabled = canEdit && !isBusy,
                    supporting = paymentNoteRowSupporting(paymentState),
                ) {
                    if (!isBusy) {
                        state.noteDraft = currentNote; state.saveError = null; state.savedFeedback = null; state.editor = "note"
                    }
                }
            }

            if (facts.showRoute) TripCard(facts.serviceTier, facts.pickup.orEmpty(), facts.dropoff.orEmpty())
            facts.items?.let { OrderItemsCard(it, facts.shopInItems, facts.notes) }
            facts.goPayDetails?.let { PaymentDetailsCard(it) }
            PaymentFacts(record, facts)
            SourceDetails(record, state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Set apart from the sections above, so these read as facts about the whole payment. */
@Composable
private fun PaymentFacts(record: UnifiedTransaction, facts: PayloadFacts) {
    val sourceNote = facts.notes.takeIf { facts.items == null }
    if (facts.paymentLabel == null && facts.counterpart == null && record.pocket == null && sourceNote == null) return
    Column(Modifier.padding(top = SectionGap), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        facts.paymentLabel?.let { DetailRow("Payment method", it) }
        facts.counterpart?.let { DetailRow("Counterpart", it) }
        record.pocket?.let { DetailRow("Pocket", it.name) }
        sourceNote?.let { DetailRow("Source note", it) }
    }
}

@Composable
private fun SourceDetails(record: UnifiedTransaction, state: TransactionDetailsState) {
    TextButton(onClick = { state.sourceExpanded = !state.sourceExpanded }) {
        Text(if (state.sourceExpanded) "Hide source details" else "Source details")
    }
    if (state.sourceExpanded) {
        DetailRow("Reference", record.providerTransactionId)
        record.cardLast4?.let { DetailRow("Card", it) }
    }
}

internal fun JSONObject.detailText(key: String): String? {
    if (isNull(key)) return null
    val value = opt(key)
    if (value is JSONObject || value is JSONArray) return null
    return value?.toString()?.trim()?.takeIf { it.isNotBlank() }
}
