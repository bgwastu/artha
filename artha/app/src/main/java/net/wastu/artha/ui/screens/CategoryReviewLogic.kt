package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.flow.first
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AI_REASON_PREFIX
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategorySource
import net.wastu.artha.data.analysis.MERCHANT_REASON_PREFIX
import net.wastu.artha.data.analysis.MerchantMemory
import net.wastu.artha.data.analysis.attentionSource
import net.wastu.artha.data.analysis.categoryReasonText
import net.wastu.artha.data.analysis.categorySource
import net.wastu.artha.data.analysis.inMonth
import net.wastu.artha.data.analysis.merchantKey
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
    month: YearMonth? = null,
): List<ReviewItem> {
    // Spending payments whose categorySource is REVIEW or FAILED, newest first, excluding busy.
    val candidates: List<UnifiedTransaction> = rows.filter { tx ->
        tx.key !in busyKeys && inMonth(tx, month) && attentionSource(tx, snapshot) != null
    }.sortedWith(compareByDescending<UnifiedTransaction> { it.occurredAt }.thenByDescending { it.key })
    return buildReviewItems(candidates, rows, snapshot, today)
}

/** Review cards for [candidates], in that order; [rows] supplies merchant peers and the user's habits. */
internal fun buildReviewItems(
    candidates: List<UnifiedTransaction>,
    rows: List<UnifiedTransaction>,
    snapshot: AnalysisSnapshot,
    today: LocalDate,
): List<ReviewItem> {
    // 1. Merchant index built once across all transactions
    val merchantIndex: Map<String, List<UnifiedTransaction>> = rows.groupBy { txMerchantKey(it) }

    // 2. Precompute the user's most-used categories overall once
    val overallCategoryCounts: Map<String, Int> = rows
        .mapNotNull { tx ->
            val cat = snapshot.categories[tx.key] ?: tx.category
            cat?.trim()?.takeIf {
                it.isNotBlank()
            }
        }
        .groupingBy { it }
        .eachCount()

    val overallTopFromRows: List<String> = overallCategoryCounts
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    fun overallTopFor(type: TransactionType): List<String> {
        val own = snapshot.categoriesFor(type)
        return overallTopFromRows.filter { it in own } + own.filter { it !in overallCategoryCounts }
    }

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

        val aiCategory = snapshot.categories[tx.key] ?: tx.category.orEmpty()

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
            for (cat in overallTopFor(tx.type)) {
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

/** A merchant's payments in a month, categorized together in whole-month mode. */
internal data class MerchantGroup(
    val merchant: String,
    val transactions: List<UnifiedTransaction>,
    val totalMinor: Long,
    /** The shared category, or null when payments differ or none is set. */
    val category: String?,
    val mixed: Boolean,
)

internal fun buildMonthGroups(
    rows: List<UnifiedTransaction>,
    snapshot: AnalysisSnapshot,
    month: YearMonth,
    type: TransactionType,
): List<MerchantGroup> = rows
    .filter { it.type == type && it.money.currencyCode == "IDR" && inMonth(it, month) }
    .groupBy { txMerchantKey(it) }
    .values
    .map { txs ->
        val categories = txs.map { snapshot.categories[it.key] ?: it.category }.distinct()
        MerchantGroup(
            merchant = CategoryReviewLogic.transactionMerchant(txs.first()),
            transactions = txs.sortedByDescending { it.occurredAt },
            totalMinor = txs.sumOf { it.money.amountMinor },
            category = categories.singleOrNull(),
            mixed = categories.size > 1,
        )
    }
    .sortedByDescending { it.totalMinor }
