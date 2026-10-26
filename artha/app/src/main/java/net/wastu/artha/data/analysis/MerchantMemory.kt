package net.wastu.artha.data.analysis

import java.util.Locale
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject

object MerchantMemory {
    private val unusablePatterns = listOf(
        "••",
        "Generic Jago payment",
        "Gopay Tabungan Transaction",
    )

    private fun isUnusableMerchant(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return true
        if (unusablePatterns.any { trimmed.contains(it, ignoreCase = true) }) return true
        if (trimmed.equals("GoPay", ignoreCase = true) || trimmed.equals("GOPAY_SAVINGS_TRANSACTION", ignoreCase = true)) return true
        return false
    }

    fun rawMerchant(tx: UnifiedTransaction): String? {
        val payload = runCatching { JSONObject(tx.rawPayload) }.getOrNull()
        val fromPayload = payload?.optString("merchantName")?.takeIf { it.isNotBlank() }
            ?: (if (tx.isQris || payload?.optBoolean("isQris") == true) payload?.optString("localized")?.takeIf { it.isNotBlank() } else null)
            ?: payload?.optString("counterpart")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("shopName")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("restaurantName")?.takeIf { it.isNotBlank() }
        val candidate = fromPayload ?: tx.description
        return candidate.trim().takeIf { it.isNotBlank() }
    }

    fun displayMerchant(tx: UnifiedTransaction): String? {
        val raw = rawMerchant(tx) ?: return null
        if (isUnusableMerchant(raw)) return null
        val cleaned = cleanMerchantName(raw)
        if (cleaned.none(Char::isLetterOrDigit)) return null
        return cleaned.ifBlank { null }
    }

    fun merchantKey(tx: UnifiedTransaction): String? {
        val display = displayMerchant(tx) ?: return null
        return display.lowercase(Locale.ROOT).trim().takeIf { it.isNotBlank() }
    }

    private fun cleanMerchantName(raw: String): String {
        var text = raw.trim()
        text = text.replace(Regex("(?i)\\s*\\(reversed\\)\\s*"), "").trim()
        text = text.replace(Regex("(?i)^qris\\s*[-:]?\\s*"), "").trim()
        text = text.replace(Regex("(?i)\\s*-\\s*qr?is\\s*$"), "").trim()

        val parts = text.split(Regex("\\s+-\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val prefix = parts[0]
            val isDeliveryPlatform = listOf("gofood", "grabfood", "shopeefood").any { prefix.equals(it, ignoreCase = true) }
            text = if (isDeliveryPlatform && parts.size >= 2) {
                parts[1]
            } else if (prefix.length >= 2) {
                prefix
            } else {
                text
            }
        }

        text = text.replace(Regex("(?i)\\s+[a-z]{0,3}\\d{3,6}$"), "").trim()
        text = text.replace(Regex("\\s+"), " ").trim()

        if (text.length > 3 && text == text.uppercase(Locale.ROOT) && text.any(Char::isLetter)) {
            text = text.lowercase(Locale.ROOT).split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
            }
        }

        return text
    }

    /** Spending payments by merchant key. Build once per pass: merchantKey parses each payload. */
    fun index(rows: List<UnifiedTransaction>): Map<String, List<UnifiedTransaction>> =
        rows.filter(BudgetAnalyzer::isSpending).groupBy { merchantKey(it) ?: "" } - ""

    fun remembered(
        tx: UnifiedTransaction,
        rows: List<UnifiedTransaction>,
        snapshot: AnalysisSnapshot,
        byMerchant: Map<String, List<UnifiedTransaction>> = index(rows),
    ): CategorySuggestion? {
        if (!BudgetAnalyzer.isSpending(tx)) return null
        val key = merchantKey(tx) ?: return null
        val display = displayMerchant(tx) ?: return null

        val peers = byMerchant[key].orEmpty().filter { it.key != tx.key }
        if (peers.isEmpty()) return null

        // 1. User manually picked a category for 1+ payments of that merchant (latest pick wins)
        val manualPeers = peers.filter { it.classificationIsManual || it.key in snapshot.manualCategories }
        if (manualPeers.isNotEmpty()) {
            val latest = manualPeers.maxByOrNull {
                snapshot.updatedAt["cat:${it.key}"] ?: it.occurredAt.toEpochMilli()
            }
            val category = latest?.let { snapshot.categories[it.key] ?: it.category }
                ?.takeIf { it.isNotBlank() && it != CategoryConstants.UNCATEGORIZED && it != CategoryConstants.INVESTMENTS_TRANSFERS && it in AiCategorization.categories(snapshot) }
            if (category != null) {
                return CategorySuggestion(
                    category = category,
                    reason = MERCHANT_REASON_PREFIX + "Same as earlier $display payments",
                    merchantConsistent = true,
                )
            }
        }

        // 2. 2+ AI-labeled payments of that merchant agree and their stored merchantConsistent flag is true
        val aiPeers = peers.filter { peer ->
            !peer.classificationIsManual &&
                peer.key !in snapshot.manualCategories &&
                peer.key in snapshot.aiMerchantConsistent &&
                !needsReview(peer, snapshot) &&
                (snapshot.reasons[peer.key]?.startsWith(AI_REASON_PREFIX) == true || (snapshot.reasons[peer.key] == null && peer.aiReasoning?.startsWith(AI_REASON_PREFIX) == true))
        }

        val aiCategories = aiPeers.mapNotNull { peer ->
            (snapshot.categories[peer.key] ?: peer.category)?.takeIf {
                it.isNotBlank() && it != CategoryConstants.UNCATEGORIZED && it != CategoryConstants.INVESTMENTS_TRANSFERS && it in AiCategorization.categories(snapshot)
            }
        }

        if (aiCategories.size >= 2) {
            val distinctCategories = aiCategories.distinct()
            if (distinctCategories.size == 1) {
                return CategorySuggestion(
                    category = distinctCategories.single(),
                    reason = MERCHANT_REASON_PREFIX + "Same as earlier $display payments",
                    merchantConsistent = true,
                )
            }
        }

        return null
    }
}

fun merchantKey(tx: UnifiedTransaction): String? = MerchantMemory.merchantKey(tx)
fun remembered(tx: UnifiedTransaction, rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): CategorySuggestion? =
    MerchantMemory.remembered(tx, rows, snapshot)
