package net.wastu.artha.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AI_REASON_PREFIX
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.CategoryConstants
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.RecategorizeScope
import net.wastu.artha.data.analysis.MERCHANT_REASON_PREFIX
import net.wastu.artha.data.analysis.MerchantMemory
import net.wastu.artha.data.analysis.categoryReasonText
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.data.analysis.merchantKey
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.categoryIconFor
import net.wastu.artha.ui.formatDate
import net.wastu.artha.ui.formatTime
import net.wastu.artha.ui.paymentRowAmount
import net.wastu.artha.ui.transactionHumanLine

internal data class ReviewItem(
    val tx: UnifiedTransaction,
    val merchant: String,
    val amountText: String,
    val dateText: String,
    val aiCategory: String,
    val reason: String?,
    val alternatives: List<String>,
    val siblingKeys: List<String>,
)

internal object CategoryReviewLogic {
    fun transactionMerchant(record: UnifiedTransaction): String {
        val display = MerchantMemory.displayMerchant(record)
        if (!display.isNullOrBlank()) return display
        val human = transactionHumanLine(record).trim()
        return human.ifBlank { record.description.trim() }.ifBlank { "Payment" }
    }

    fun buildReviewQueue(
        rows: List<UnifiedTransaction>,
        snapshot: AnalysisSnapshot,
        busyKeys: Set<String>,
        today: LocalDate = LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone)),
    ): List<ReviewItem> = net.wastu.artha.ui.screens.buildReviewQueue(rows, snapshot, busyKeys, today)
}

internal fun txMerchantKey(tx: UnifiedTransaction): String {
    val key = merchantKey(tx)
    if (!key.isNullOrBlank()) return key
    val desc = tx.description.lowercase(Locale.ROOT).trim()
    return desc.ifBlank { tx.key }
}

internal fun formatReviewDate(instant: Instant, today: LocalDate): String {
    val txDate = instant.atZone(AccountingTime.zone).toLocalDate()
    val timeText = formatTime(instant)
    return when (txDate) {
        today -> "Today, $timeText"
        today.minusDays(1) -> "Yesterday, $timeText"
        else -> formatDate(instant)
    }
}

internal fun buildReviewQueue(
    rows: List<UnifiedTransaction>,
    snapshot: AnalysisSnapshot,
    busyKeys: Set<String>,
    today: LocalDate = LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone)),
): List<ReviewItem> {
    // 1. Merchant index built once across all transactions
    val merchantIndex: Map<String, List<UnifiedTransaction>> = rows.groupBy { txMerchantKey(it) }

    // 2. Precompute the user's most-used categories overall once
    val overallCategoryCounts: Map<String, Int> = rows
        .mapNotNull { tx ->
            val cat = snapshot.categories[tx.key] ?: tx.category
            cat?.trim()?.takeIf {
                it.isNotBlank() &&
                    it != CategoryConstants.UNCATEGORIZED &&
                    it != CategoryConstants.INVESTMENTS_TRANSFERS
            }
        }
        .groupingBy { it }
        .eachCount()

    val overallTopFromRows: List<String> = overallCategoryCounts
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    val fallbackCustom: List<String> = snapshot.customCategories.filter {
        it.isNotBlank() &&
            it != CategoryConstants.UNCATEGORIZED &&
            it != CategoryConstants.INVESTMENTS_TRANSFERS &&
            it !in overallCategoryCounts
    }

    val overallTopCategories: List<String> = overallTopFromRows + fallbackCustom

    // 3. Filter candidates: spending payments whose categorySource is REVIEW or FAILED, newest first, excluding busy
    val candidates: List<UnifiedTransaction> = rows.filter { tx ->
        if (tx.key in busyKeys) return@filter false
        if (tx.classificationIsManual || tx.key in snapshot.manualCategories) return@filter false
        if (!BudgetAnalyzer.isSpending(tx)) return@filter false
        val source = categorySource(tx, snapshot)
        source == CategorySource.REVIEW || source == CategorySource.FAILED
    }.sortedWith(compareByDescending<UnifiedTransaction> { it.occurredAt }.thenByDescending { it.key })

    // 4. Precompute sibling grouping within the queue items
    val candidateMerchantKeys = candidates.map { it to txMerchantKey(it) }
    val queueByMerchantKey: Map<String, List<UnifiedTransaction>> = candidateMerchantKeys.groupBy({ it.second }, { it.first })

    // 5. Build precomputed ReviewItem for each candidate
    return candidates.map { tx ->
        val mKey = txMerchantKey(tx)

        val display = MerchantMemory.displayMerchant(tx)
        val merchant = if (!display.isNullOrBlank()) {
            display
        } else {
            val human = transactionHumanLine(tx).trim()
            human.ifBlank { tx.description.trim() }.ifBlank { "Payment" }
        }

        val aiCategory = snapshot.categories[tx.key] ?: tx.category ?: CategoryConstants.UNCATEGORIZED

        val source = categorySource(tx, snapshot)
        val reason: String? = if (source == CategorySource.FAILED) {
            snapshot.categoryFailures[tx.key]?.takeIf { it.isNotBlank() } ?: "AI couldn't categorize"
        } else {
            categoryReasonText(snapshot, tx.key)
                ?: tx.aiReasoning?.let { r ->
                    when {
                        r.startsWith(AI_REASON_PREFIX) -> r.removePrefix(AI_REASON_PREFIX).trim()
                        r.startsWith(MERCHANT_REASON_PREFIX) -> r.removePrefix(MERCHANT_REASON_PREFIX).trim()
                        else -> r.trim()
                    }
                }?.takeIf { it.isNotBlank() }
        }

        // Alternatives: most common categories among other payments of the same merchant key, then user's most-used overall
        val peers = merchantIndex[mKey]?.filter { it.key != tx.key }.orEmpty()
        val peerCategoryCounts = peers
            .mapNotNull { peer ->
                val cat = snapshot.categories[peer.key] ?: peer.category
                cat?.trim()?.takeIf {
                    it.isNotBlank() &&
                        it != CategoryConstants.UNCATEGORIZED &&
                        it != CategoryConstants.INVESTMENTS_TRANSFERS &&
                        it != aiCategory
                }
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        val alternatives = mutableListOf<String>()
        for (cat in peerCategoryCounts) {
            if (cat !in alternatives) {
                alternatives.add(cat)
            }
            if (alternatives.size >= 2) break
        }
        if (alternatives.size < 2) {
            for (cat in overallTopCategories) {
                if (cat != aiCategory && cat !in alternatives) {
                    alternatives.add(cat)
                }
                if (alternatives.size >= 2) break
            }
        }

        val siblingKeys = queueByMerchantKey[mKey]
            ?.mapNotNull { peer -> peer.key.takeIf { it != tx.key } }
            ?: emptyList()

        ReviewItem(
            tx = tx,
            merchant = merchant,
            amountText = paymentRowAmount(tx),
            dateText = formatReviewDate(tx.occurredAt, today),
            aiCategory = aiCategory,
            reason = reason,
            alternatives = alternatives,
            siblingKeys = siblingKeys,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryReviewScreen(
    viewModel: ArthaViewModel,
    onBack: () -> Unit,
    onOpenTransaction: (UnifiedTransaction) -> Unit = {},
) {
    val snapshot by viewModel.analysisSnapshot.collectAsStateWithLifecycle()
    val categoryProgress by viewModel.categoryProgress.collectAsStateWithLifecycle()

    val initialQueueState = produceState<List<ReviewItem>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            val rows = viewModel.allTransactions.value
            val initialSnapshot = viewModel.analysisSnapshot.value
            val progress = viewModel.categoryProgress.value
            val busyKeys = progress.activeKeys + progress.queuedKeys
            val today = LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone))
            buildReviewQueue(rows, initialSnapshot, busyKeys, today)
        }
    }

    var queue by remember { mutableStateOf<List<ReviewItem>?>(null) }
    var totalCount by remember { mutableIntStateOf(0) }
    var reviewedCount by remember { mutableIntStateOf(0) }

    val initialQueue = initialQueueState.value
    if (queue == null && initialQueue != null) {
        queue = initialQueue
        totalCount = initialQueue.size
    }

    val currentQueue = queue
    val currentItem = currentQueue?.firstOrNull()

    var currentItemKey by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf("") }
    var customCategory by remember { mutableStateOf<String?>(null) }
    var applyToSiblings by remember { mutableStateOf(true) }
    var showOtherCategoryDialog by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }
    var showNoteInput by remember { mutableStateOf(false) }

    if (currentItem != null && currentItem.tx.key != currentItemKey) {
        currentItemKey = currentItem.tx.key
        selectedCategory = currentItem.aiCategory
        customCategory = null
        applyToSiblings = true
        showOtherCategoryDialog = false
        noteDraft = currentItem.tx.userNote.orEmpty()
        showNoteInput = currentItem.tx.userNote.isNullOrBlank().not()
    }

    // Auto-skip if the current item becomes busy
    LaunchedEffect(currentItem?.tx?.key, categoryProgress.activeKeys, categoryProgress.queuedKeys) {
        val key = currentItem?.tx?.key ?: return@LaunchedEffect
        if (key in categoryProgress.activeKeys || key in categoryProgress.queuedKeys) {
            queue = queue?.filterNot { it.tx.key == key }
            reviewedCount += 1
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = { Text("Categorization") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (totalCount > 0 && currentQueue?.isNotEmpty() == true) {
                            Text(
                                text = "${(totalCount - (currentQueue?.size ?: 0) + 1).coerceAtMost(totalCount)} of $totalCount",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                    },
                )
                val progressFraction = if (totalCount > 0) {
                    (reviewedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        when {
            currentQueue == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            currentQueue.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .widthIn(max = 480.dp)
                            .padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (totalCount == 0) "Nothing to review" else "All reviewed",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (totalCount == 0) "No transactions need category review." else "All transactions have been categorized.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onBack, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("Done")
                        }
                    }
                }
            }
            else -> {
                AnimatedContent(
                    targetState = currentItem,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                    },
                    contentKey = { it?.tx?.key },
                    label = "ReviewItemTransition",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) { item ->
                    if (item != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 600.dp)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                // Transaction Card
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onOpenTransaction(item.tx) },
                                        ) {
                                            Text(
                                                text = item.merchant,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = "View transaction",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = item.amountText,
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = "•",
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                            Text(
                                                text = item.dateText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (!item.reason.isNullOrBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.AutoAwesome,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Text(
                                                        text = item.reason,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Category Selection
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Category",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    OutlinedCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.outlinedCardColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                        ),
                                    ) {
                                        Column(modifier = Modifier.selectableGroup()) {
                                            // AI Suggestion item
                                            val isAiSelected = selectedCategory == item.aiCategory
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .selectable(
                                                        selected = isAiSelected,
                                                        role = Role.RadioButton,
                                                        onClick = { selectedCategory = item.aiCategory },
                                                    )
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                RadioButton(
                                                    selected = isAiSelected,
                                                    onClick = null,
                                                )
                                                Icon(
                                                    categoryIconFor(item.aiCategory, snapshot.categoryIcons),
                                                    contentDescription = null,
                                                    tint = if (isAiSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.aiCategory,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = if (isAiSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    )
                                                    Text(
                                                        text = "AI suggestion",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }

                                            // Alternatives
                                            item.alternatives.forEach { alt ->
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                val isAltSelected = selectedCategory == alt
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .selectable(
                                                            selected = isAltSelected,
                                                            role = Role.RadioButton,
                                                            onClick = { selectedCategory = alt },
                                                        )
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    RadioButton(
                                                        selected = isAltSelected,
                                                        onClick = null,
                                                    )
                                                    Icon(
                                                        categoryIconFor(alt, snapshot.categoryIcons),
                                                        contentDescription = null,
                                                        tint = if (isAltSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                    Text(
                                                        text = alt,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = if (isAltSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }

                                            // Custom category if chosen
                                            if (customCategory != null && customCategory != item.aiCategory && customCategory !in item.alternatives) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                val isCustomSelected = selectedCategory == customCategory
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .selectable(
                                                            selected = isCustomSelected,
                                                            role = Role.RadioButton,
                                                            onClick = { selectedCategory = customCategory!! },
                                                        )
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    RadioButton(
                                                        selected = isCustomSelected,
                                                        onClick = null,
                                                    )
                                                    Icon(
                                                        categoryIconFor(customCategory!!, snapshot.categoryIcons),
                                                        contentDescription = null,
                                                        tint = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                    Text(
                                                        text = customCategory!!,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = if (isCustomSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }

                                            // Other category button
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { showOtherCategoryDialog = true }
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Outlined.Label,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                                Text(
                                                    text = "Other category...",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }

                                    // Sibling propagation
                                    val siblingsStillInQueue = currentQueue.filter { it.tx.key in item.siblingKeys }
                                    if (siblingsStillInQueue.isNotEmpty()) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                                .toggleable(
                                                    value = applyToSiblings,
                                                    role = Role.Checkbox,
                                                    onValueChange = { applyToSiblings = it },
                                                ),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Checkbox(
                                                    checked = applyToSiblings,
                                                    onCheckedChange = null,
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Also apply to ${siblingsStillInQueue.size} more payments",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                    Text(
                                                        text = item.merchant,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Optional Note Section
                                if (!showNoteInput) {
                                    TextButton(
                                        onClick = { showNoteInput = true },
                                        modifier = Modifier.align(Alignment.Start),
                                    ) {
                                        Icon(
                                            Icons.Default.EditNote,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (noteDraft.isBlank()) "Add note for AI" else "Edit note")
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedTextField(
                                            value = noteDraft,
                                            onValueChange = { noteDraft = it },
                                            label = { Text("Note for AI") },
                                            placeholder = { Text("e.g. what this purchase was for") },
                                            supportingText = { Text("Saving note re-runs AI categorization") },
                                            minLines = 1,
                                            maxLines = 3,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        if (noteDraft.isNotBlank()) {
                                            Row(
                                                horizontalArrangement = Arrangement.End,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        val itm = currentItem ?: return@TextButton
                                                        viewModel.setUserNote(itm.tx, noteDraft.trim()) {}
                                                        viewModel.recategorize(RecategorizeScope.Payment(itm.tx.key, replaceManual = true))
                                                        queue = queue?.filterNot { it.tx.key == itm.tx.key }
                                                        reviewedCount += 1
                                                    },
                                                ) {
                                                    Text("Save & re-analyze")
                                                }
                                            }
                                        }
                                    }
                                }

                                // Action Buttons (Right below the form, NO wide gap!)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .padding(top = 8.dp, bottom = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val itm = currentItem ?: return@OutlinedButton
                                            queue = queue?.filterNot { it.tx.key == itm.tx.key }
                                            reviewedCount += 1
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text("Skip")
                                    }
                                    Button(
                                        onClick = {
                                            val itm = currentItem ?: return@Button
                                            val targetCategory = selectedCategory
                                            viewModel.confirmCategory(itm.tx, targetCategory) {}
                                            val siblings = currentQueue.filter { it.tx.key in itm.siblingKeys }
                                            if (applyToSiblings && siblings.isNotEmpty()) {
                                                for (sibling in siblings) {
                                                    viewModel.confirmCategory(sibling.tx, targetCategory) {}
                                                }
                                                val keysToRemove = setOf(itm.tx.key) + siblings.map { it.tx.key }
                                                queue = queue?.filterNot { it.tx.key in keysToRemove }
                                                reviewedCount += keysToRemove.size
                                            } else {
                                                queue = queue?.filterNot { it.tx.key == itm.tx.key }
                                                reviewedCount += 1
                                            }
                                        },
                                        enabled = selectedCategory.isNotBlank(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(if (currentQueue.size <= 1) "Confirm" else "Next")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOtherCategoryDialog) {
        val availableCategories = remember(snapshot.customCategories) {
            snapshot.customCategories.filter { it != CategoryConstants.INVESTMENTS_TRANSFERS }
        }
        AlertDialog(
            onDismissRequest = { showOtherCategoryDialog = false },
            title = { Text("Select category") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    availableCategories.forEach { category ->
                        val isSelected = category == selectedCategory
                        ListItem(
                            headlineContent = { Text(category) },
                            leadingContent = {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        selectedCategory = category
                                        customCategory = category
                                        showOtherCategoryDialog = false
                                    },
                                ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOtherCategoryDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
