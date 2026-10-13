package net.wastu.artha.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatMoney
import net.wastu.artha.ui.theme.expenseColor
import net.wastu.artha.ui.theme.incomeColor
import java.time.Instant
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsSheet(
    transaction: UnifiedTransaction,
    viewModel: ArthaViewModel? = null,
    onDismiss: () -> Unit,
) {
    val snapshot = viewModel?.analysisSnapshot?.collectAsStateWithLifecycle()?.value
    val availableCategories = snapshot?.customCategories ?: net.wastu.artha.data.analysis.INITIAL_DEFAULT_CATEGORIES
    var showCategoryPicker by remember { mutableStateOf(false) }
    var currentCategory by remember(transaction.providerTransactionId, transaction.category) {
        mutableStateOf(transaction.category ?: "Uncategorized")
    }
    val txIdKey = remember(transaction.providerTransactionId) { transaction.providerTransactionId.substringAfter(":") }
    var currentNote by remember(transaction.providerTransactionId, transaction.userNote, snapshot?.userNotes) {
        mutableStateOf(snapshot?.userNotes?.get(txIdKey) ?: transaction.userNote.orEmpty())
    }
    var showNoteDialog by remember { mutableStateOf(false) }
    val payload = remember(transaction.rawPayload) {
        runCatching { JSONObject(transaction.rawPayload) }.getOrNull()
    }
    val pickup = payload?.detailText("pickupLocation")
    val dropoff = payload?.detailText("dropoffLocation")
    val notes = payload?.detailText("notes")
    val counterpart = payload?.detailText("counterpart")
    val items = payload?.optJSONArray("items")
    val grouped = payload?.optJSONArray("groupedTransactions")
    val showRoute = !pickup.isNullOrBlank() && !dropoff.isNullOrBlank()
    val showOrder = items != null && items.length() > 0
    val credit = transaction.direction == Direction.CREDIT
    val isReversal = transaction.description.contains("(Reversed)", ignoreCase = true) || payload?.optBoolean("isReversal") == true
    val isZeroAmount = isReversal || transaction.money.amountMinor == 0L

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (showCategoryPicker) {
            CategoryPickerView(
                currentCategory = currentCategory,
                availableCategories = availableCategories,
                categoryIcons = snapshot?.categoryIcons ?: emptyMap(),
                onSelectCategory = { selected ->
                    currentCategory = selected
                    viewModel?.setTransactionCategory(transaction.providerTransactionId, selected)
                    showCategoryPicker = false
                },
                onCreateCategory = { created ->
                    viewModel?.addCustomCategory(created)
                },
                onBack = { showCategoryPicker = false },
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
                                painter = painterResource(transaction.provider.iconRes),
                                contentDescription = transaction.provider.displayName,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                            )
                        }
                        if (grouped != null && grouped.length() > 1 && transaction.provider != Provider.JAGO) {
                            Image(
                                painter = painterResource(Provider.JAGO.iconRes),
                                contentDescription = Provider.JAGO.displayName,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                    Text(
                        if (isZeroAmount) "Rp0" else "${if (credit) "+" else "−"}${formatMoney(transaction.money)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isZeroAmount) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        } else if (credit) {
                            incomeColor()
                        } else {
                            expenseColor()
                        },
                    )
                    Text(transaction.description.trim(), style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailBadge(
                            transaction.provider.displayName,
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        DetailBadge(
                            if (credit) "Incoming" else "Outgoing",
                            if (credit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            if (credit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (transaction.isQris) {
                            DetailBadge("QRIS", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        if (isReversal) {
                            DetailBadge("Reversed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        }
                        if (transaction.cardLast4 != null) {
                            DetailBadge(
                                "Card ${transaction.cardLast4}",
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Text(
                        formatDate(transaction.occurredAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!credit) {
                        // Clean single-level Category row
                        Surface(
                            onClick = { showCategoryPicker = true },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val icon = categoryIconFor(currentCategory, snapshot?.categoryIcons ?: emptyMap())
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "Category",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = currentCategory,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Change Category",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    // Clean single-level Personal Note row
                    Surface(
                        onClick = { showNoteDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Note",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (currentNote.isNotBlank()) currentNote else "Add personal note...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (currentNote.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                                color = if (currentNote.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Edit Note",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    if (showRoute) {
                        DetailCard("Route / Trip Details") {
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
                        DetailCard("Order Items") {
                            (payload?.detailText("shopName") ?: payload?.detailText("restaurantName"))?.let {
                                Text(it, style = MaterialTheme.typography.titleSmall)
                            }
                            for (index in 0 until (items?.length() ?: 0)) {
                                val item = items?.optJSONObject(index) ?: continue
                                val name = item.detailText("name") ?: continue
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.bodyMedium)
                                        item.detailText("qty")?.let {
                                            Text("Quantity: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    item.detailText("price")?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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

                    DetailCard("Payment & Transfer Details") {
                        payload?.detailText("paymentMethod")?.let { DetailRow("Payment Method", it) }
                        counterpart?.let { DetailRow("Counterpart", it) }
                        transaction.pocket?.let { DetailRow("Pocket", it.name) }
                        if (notes != null && !showOrder) DetailRow("Notes", notes)
                        if (transaction.cardLast4 != null) DetailRow("Card", "Visa Debit (Card ${transaction.cardLast4})")
                        val refLabel = if (transaction.providerTransactionId.startsWith("shopee:")) "Order ID" else "Reference"
                        val refValue = transaction.providerTransactionId.substringAfter(":")
                        DetailRow(refLabel, refValue)
                    }

                    if (grouped != null && grouped.length() > 1) {
                        DetailCard("Grouped Transactions (${grouped.length()})") {
                            for (i in 0 until grouped.length()) {
                                val item = grouped.optJSONObject(i) ?: continue
                                val itemProviderName = item.optString("provider")
                                val itemProvider = Provider.entries.firstOrNull { it.name == itemProviderName } ?: transaction.provider
                                val itemDesc = item.optString("description")
                                val itemRole = item.optString("role").ifBlank { "Transaction" }
                                val itemSub = item.optString("subtitle")
                                val itemEpoch = item.optLong("occurredAtEpochMillis")
                                val itemAmt = item.optLong("amountMinor", 0L)
                                val itemDir = item.optString("direction")
                                val isCredit = itemDir == "CREDIT"

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Image(
                                                painter = painterResource(itemProvider.iconRes),
                                                contentDescription = itemProvider.displayName,
                                                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
                                            )
                                            Text(
                                                itemProvider.displayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            DetailBadge(
                                                itemRole,
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                            Spacer(Modifier.weight(1f))
                                            if (itemAmt > 0L) {
                                                Text(
                                                    "${if (isCredit) "+" else "−"}${formatMoney(net.wastu.artha.core.model.Money(itemAmt))}",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isCredit) incomeColor() else expenseColor(),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(itemDesc, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        if (itemSub.isNotBlank()) {
                                            Text(itemSub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (itemEpoch > 0L) {
                                            Text(formatDate(Instant.ofEpochMilli(itemEpoch)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (showNoteDialog) {
            TransactionNoteDialog(
                initialNote = currentNote,
                onDismiss = { showNoteDialog = false },
                onSave = { updated ->
                    currentNote = updated
                    viewModel?.setUserNote(transaction, updated)
                    showNoteDialog = false
                },
            )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
private fun CategoryPickerView(
    currentCategory: String,
    availableCategories: List<String>,
    categoryIcons: Map<String, String>,
    onSelectCategory: (String) -> Unit,
    onCreateCategory: (String) -> Unit,
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
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header with Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Select Category",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search or create category...") },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        // "+ Create & Set" option if typed query does not exist
        if (queryTrimmed.isNotBlank() && !isExactMatch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable {
                        onCreateCategory(queryTrimmed)
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
                    "Create \"$queryTrimmed\"",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Clean flat category rows (no cards on top of cards)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filteredCategories, key = { it }) { cat ->
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
                        .clickable { onSelectCategory(cat) }
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
                            contentDescription = "Selected",
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
private fun TransactionNoteDialog(
    initialNote: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction Note", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Add personal context or #tags. AI uses this note to automatically recategorize the transaction and refine monthly insights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g. Dinner with team, project supplies...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onSave(text.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (initialNote.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
