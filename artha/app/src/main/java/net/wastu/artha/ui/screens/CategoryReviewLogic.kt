package net.wastu.artha.ui.screens

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
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

/** The name shown for a payment: the remembered merchant, else its human-readable line, else the raw description. */
internal fun transactionMerchant(tx: UnifiedTransaction): String {
    val display = MerchantMemory.displayMerchant(tx)
    if (!display.isNullOrBlank()) return display
    return transactionHumanLine(tx).trim().ifBlank { tx.description.trim() }.ifBlank { "Payment" }
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

/** Payments the AI was unsure about or failed on, newest first, minus those the categorizer is working on. */
internal fun buildReviewQueue(
    rows: List<UnifiedTransaction>,
    snapshot: AnalysisSnapshot,
    busyKeys: Set<String>,
    today: LocalDate = LocalDate.now(AccountingTime.clock.withZone(AccountingTime.zone)),
    month: YearMonth? = null,
): List<ReviewItem> {
    val candidates = rows
        .filter { tx -> tx.key !in busyKeys && inMonth(tx, month) && attentionSource(tx, snapshot) != null }
        .sortedWith(compareByDescending<UnifiedTransaction> { it.occurredAt }.thenByDescending { it.key })
    return buildReviewItems(candidates, rows, snapshot, today)
}

/** Review cards for [candidates], in that order; [rows] supplies merchant peers and the user's habits. */
internal fun buildReviewItems(
    candidates: List<UnifiedTransaction>,
    rows: List<UnifiedTransaction>,
    snapshot: AnalysisSnapshot,
    today: LocalDate,
): List<ReviewItem> {
    val peersByMerchant = rows.groupBy(::txMerchantKey)
    val queueByMerchant = candidates.groupBy(::txMerchantKey)
    val overallUsage = categoriesByUsage(rows, snapshot)
    return candidates.map { tx ->
        val merchantKey = txMerchantKey(tx)
        val aiCategory = snapshot.categories[tx.key] ?: tx.category.orEmpty()
        val peers = peersByMerchant[merchantKey].orEmpty().filter { it.key != tx.key }
        ReviewItem(
            tx = tx,
            merchant = transactionMerchant(tx),
            amountText = paymentRowAmount(tx),
            dateText = formatReviewDate(tx.occurredAt, today),
            aiCategory = aiCategory,
            reason = reviewReason(tx, snapshot),
            alternatives = alternativeCategories(tx, aiCategory, peers, overallUsage, snapshot),
            siblingKeys = queueByMerchant[merchantKey].orEmpty().map { it.key }.filter { it != tx.key },
        )
    }
}

/** Why the payment needs a look: the failure, else the AI's own reasoning with its internal prefix removed. */
private fun reviewReason(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): String? {
    if (categorySource(tx, snapshot) == CategorySource.FAILED) {
        return snapshot.categoryFailures[tx.key]?.takeIf { it.isNotBlank() } ?: "AI couldn't categorize"
    }
    return categoryReasonText(snapshot, tx.key) ?: tx.aiReasoning
        ?.removePrefixes(AI_REASON_PREFIX, MERCHANT_REASON_PREFIX)
        ?.takeIf { it.isNotBlank() }
}

private fun String.removePrefixes(vararg prefixes: String): String {
    val prefix = prefixes.firstOrNull { startsWith(it) }
    return (if (prefix != null) removePrefix(prefix) else this).trim()
}

/** Every category in use, most used first, ties by name. */
private fun categoriesByUsage(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): List<String> = rows
    .mapNotNull { tx -> (snapshot.categories[tx.key] ?: tx.category)?.trim()?.takeIf { it.isNotBlank() } }
    .mostUsed()

private fun List<String>.mostUsed(): List<String> = groupingBy { it }.eachCount().entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .map { it.key }

/**
 * Up to two other categories worth offering: what the same merchant's other payments use, then the user's
 * overall favourites for this type of payment, then categories they have never used.
 */
private fun alternativeCategories(
    tx: UnifiedTransaction,
    aiCategory: String,
    peers: List<UnifiedTransaction>,
    overallUsage: List<String>,
    snapshot: AnalysisSnapshot,
): List<String> {
    val fromPeers = peers
        .mapNotNull { peer -> (snapshot.categories[peer.key] ?: peer.category)?.trim()?.takeIf { it.isNotBlank() && it != aiCategory } }
        .mostUsed()
    val own = snapshot.categoriesFor(tx.type)
    val fromHabits = overallUsage.filter { it in own } + own.filter { it !in overallUsage }
    return (fromPeers + fromHabits.filter { it != aiCategory }).distinct().take(MAX_ALTERNATIVES)
}

private const val MAX_ALTERNATIVES = 2

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
            merchant = transactionMerchant(txs.first()),
            transactions = txs.sortedByDescending { it.occurredAt },
            totalMinor = txs.sumOf { it.money.amountMinor },
            category = categories.singleOrNull(),
            mixed = categories.size > 1,
        )
    }
    .sortedByDescending { it.totalMinor }
