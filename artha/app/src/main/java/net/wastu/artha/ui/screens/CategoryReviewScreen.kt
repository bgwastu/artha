package net.wastu.artha.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AI_REASON_PREFIX
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.transactionHumanLine
import org.json.JSONObject

internal object CategoryReviewLogic {
    fun transactionMerchant(record: UnifiedTransaction): String {
        val payload = runCatching { JSONObject(record.rawPayload) }.getOrNull()
        val merchant = payload?.optString("merchantName")?.trim()?.takeIf { it.isNotBlank() }
            ?: payload?.optString("shopName")?.trim()?.takeIf { it.isNotBlank() }
            ?: payload?.optString("restaurantName")?.trim()?.takeIf { it.isNotBlank() }
            ?: payload?.optString("counterpart")?.trim()?.takeIf { it.isNotBlank() }
        if (!merchant.isNullOrBlank()) return merchant
        val human = transactionHumanLine(record).trim()
        return human.ifBlank { record.description.trim() }
    }

    fun filterReviewPayments(
        transactions: List<UnifiedTransaction>,
        snapshot: AnalysisSnapshot,
        confirmedKeys: Set<String> = emptySet(),
    ): List<UnifiedTransaction> {
        return transactions.filter { tx ->
            if (tx.key in confirmedKeys) return@filter false
            val source = categorySource(tx, snapshot)
            source == CategorySource.REVIEW || source == CategorySource.FAILED
        }
    }

    fun groupByMerchant(
        payments: List<UnifiedTransaction>,
    ): Map<String, List<UnifiedTransaction>> {
        return payments.groupBy { transactionMerchant(it) }
    }

    fun canConfirmAll(
        payments: List<UnifiedTransaction>,
        snapshot: AnalysisSnapshot,
    ): Pair<Boolean, String?> {
        if (payments.isEmpty()) return false to null
        val categories = payments.map {
            snapshot.categories[it.key] ?: it.category ?: "Uncategorized"
        }.distinct()
        return if (categories.size == 1 && categories.first().isNotBlank() && categories.first() != "Uncategorized") {
            true to categories.first()
        } else {
            false to null
        }
    }

    fun suggestedAlternatives(
        currentCategory: String,
        merchant: String,
        allTransactions: List<UnifiedTransaction>,
        categoriesMap: Map<String, String>,
        topCategories: List<String>,
        limit: Int = 2,
    ): List<String> {
        val candidates = mutableListOf<String>()
        val merchantPeers = allTransactions.filter {
            transactionMerchant(it) == merchant
        }
        val peerCategoryCounts = merchantPeers
            .mapNotNull { tx ->
                val cat = categoriesMap[tx.key] ?: tx.category
                cat?.takeIf { it.isNotBlank() && it != "Uncategorized" && it != currentCategory }
            }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .map { it.first }

        for (cat in peerCategoryCounts) {
            if (cat !in candidates) candidates.add(cat)
            if (candidates.size >= limit) return candidates
        }

        for (cat in topCategories) {
            if (cat.isNotBlank() && cat != "Uncategorized" && cat != currentCategory && cat !in candidates) {
                candidates.add(cat)
            }
            if (candidates.size >= limit) break
        }

        return candidates.take(limit)
    }

    fun displayReason(
        tx: UnifiedTransaction,
        snapshot: AnalysisSnapshot,
    ): String? {
        val failText = snapshot.categoryFailures[tx.key]?.takeIf { it.isNotBlank() }
        if (failText != null) return failText
        val source = categorySource(tx, snapshot)
        if (source == CategorySource.FAILED) return "AI couldn't categorize"
        val rawReason = snapshot.reasons[tx.key] ?: tx.aiReasoning
        return rawReason?.removePrefix(AI_REASON_PREFIX)?.takeIf { it.isNotBlank() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryReviewScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
) {
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    var confirmedKeys by remember { mutableStateOf(setOf<String>()) }
    var pickMoreTransaction by remember { mutableStateOf<UnifiedTransaction?>(null) }

    val pendingReview = remember(allTransactions, snapshot, confirmedKeys) {
        CategoryReviewLogic.filterReviewPayments(allTransactions, snapshot, confirmedKeys)
    }

    val groupedByMerchant = remember(pendingReview) {
        CategoryReviewLogic.groupByMerchant(pendingReview)
    }

    val topCategories = remember(snapshot.customCategories) {
        snapshot.customCategories
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (pendingReview.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing to review",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                groupedByMerchant.forEach { (merchant, txs) ->
                    stickyHeader(key = "header_$merchant") {
                        val (canConfirm, sharedCat) = CategoryReviewLogic.canConfirmAll(txs, snapshot)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = merchant,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (canConfirm && sharedCat != null) {
                                    TextButton(
                                        onClick = {
                                            val keysToConfirm = txs.map { it.key }.toSet()
                                            confirmedKeys = confirmedKeys + keysToConfirm
                                            txs.forEach { tx ->
                                                viewModel.confirmCategory(tx, sharedCat) {}
                                            }
                                        },
                                    ) {
                                        Text("Confirm all")
                                    }
                                }
                            }
                        }
                    }

                    items(txs, key = { it.key }) { tx ->
                        Box(modifier = Modifier.animateItem()) {
                            val currentCategory = snapshot.categories[tx.key] ?: tx.category ?: "Uncategorized"
                            val alternatives = remember(tx.key, currentCategory, merchant) {
                                CategoryReviewLogic.suggestedAlternatives(
                                    currentCategory = currentCategory,
                                    merchant = merchant,
                                    allTransactions = allTransactions,
                                    categoriesMap = snapshot.categories,
                                    topCategories = topCategories,
                                )
                            }
                            val reason = CategoryReviewLogic.displayReason(tx, snapshot)

                            ReviewPaymentRow(
                                transaction = tx,
                                currentCategory = currentCategory,
                                alternatives = alternatives,
                                reason = reason,
                                onSelectCategory = { chosenCategory ->
                                    confirmedKeys = confirmedKeys + tx.key
                                    viewModel.confirmCategory(tx, chosenCategory) {}
                                },
                                onOpenMore = {
                                    pickMoreTransaction = tx
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pickMoreTransaction?.let { tx ->
        val currentCategory = snapshot.categories[tx.key] ?: tx.category ?: "Uncategorized"
        CategoryPickerDialog(
            currentCategory = currentCategory,
            categories = snapshot.customCategories,
            onDismiss = { pickMoreTransaction = null },
            onSelect = { selectedCategory ->
                pickMoreTransaction = null
                confirmedKeys = confirmedKeys + tx.key
                viewModel.confirmCategory(tx, selectedCategory) {}
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewPaymentRow(
    transaction: UnifiedTransaction,
    currentCategory: String,
    alternatives: List<String>,
    reason: String?,
    onSelectCategory: (String) -> Unit,
    onOpenMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            val title = transactionHumanLine(transaction).ifBlank { transaction.description }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
                text = paymentRowAmount(transaction),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = formatDate(transaction.occurredAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!reason.isNullOrBlank()) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = true,
                onClick = { onSelectCategory(currentCategory) },
                label = { Text(currentCategory) },
            )
            alternatives.forEach { alt ->
                FilterChip(
                    selected = false,
                    onClick = { onSelectCategory(alt) },
                    label = { Text(alt) },
                )
            }
            AssistChip(
                onClick = onOpenMore,
                label = { Text("More") },
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun CategoryPickerDialog(
    currentCategory: String,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select category") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                categories.forEach { cat ->
                    ListItem(
                        headlineContent = { Text(cat) },
                        trailingContent = if (cat == currentCategory) {
                            { Text("Current", style = MaterialTheme.typography.labelSmall) }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(cat) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
