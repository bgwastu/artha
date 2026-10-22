package net.wastu.artha.ui

import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONArray
import org.json.JSONObject

private const val HUMAN_LINE_SEP = " - "

/** One-line title for lists and detail headers; ASCII separators only. */

internal fun transactionHumanLine(transaction: UnifiedTransaction): String {
    val payload = runCatching { JSONObject(transaction.rawPayload) }.getOrNull()
    val built = payload?.let { lineFromPayload(it) }
    val normalized = normalizeHumanLine(built ?: transaction.description)
    return normalized.ifBlank { transaction.description.trim() }
        .let { if (isOpaqueFallback(it)) opaqueTitle(payload) else it }
}

/** Jago-only GoPay rows carry no merchant; "004 - Gopay - Generic Jago payment" is Jago's own note. */
internal fun isOpaqueFallback(title: String): Boolean =
    title.contains("Generic Jago payment", ignoreCase = true) ||
        title.equals("Gopay Tabungan Transaction", ignoreCase = true)

private fun opaqueTitle(payload: JSONObject?): String {
    val pocket = payload?.optString("pocketName")?.trim().orEmpty()
    return if (pocket.isBlank() || pocket.equals("GoPay Tabungan", ignoreCase = true)) "GoPay Payment"
    else "GoPay Payment$HUMAN_LINE_SEP$pocket"
}

private fun lineFromPayload(payload: JSONObject): String? {
    val merchant = usableMerchant(payload.optString("merchantName"))
        ?: qrisMerchantHint(payload)
        ?: usableMerchant(payload.optString("counterpart"))
    // QRIS is a row tag, not part of the title.
    if (merchant != null) return merchant
    payload.optString("orderDisplayDescription").trim().takeIf { it.isNotBlank() }?.let { return it }
    val shop = payload.optString("shopName").ifBlank { payload.optString("restaurantName") }
    val items = payload.optJSONArray("items")
    if (shop.isNotBlank() || (items?.length() ?: 0) > 0) {
        val product = primaryItemLabel(items)
        val orderLine = listOf(shop, product).filter { it.isNotBlank() }.joinToString(HUMAN_LINE_SEP)
        if (orderLine.isNotBlank()) return orderLine
    }
    val pickup = payload.optString("pickupLocation").trim()
    val dropoff = payload.optString("dropoffLocation").trim()
    if (pickup.isNotBlank()) {
        val route = if (dropoff.isNotBlank()) "$pickup to $dropoff" else pickup
        val tier = payload.optString("serviceTier").trim()
        return if (tier.isNotBlank()) listOf(tier, route).joinToString(HUMAN_LINE_SEP) else route
    }
    return null
}

/**
 * QRIS card rows carry the acquirer bank name in `counterpart` (f2), not the seller. The
 * seller descriptor lives in `localized` (f14), e.g. "CIRCLE K - JAK0922 - QRIS"; strip the
 * rank/terminal tail and the "- QRIS" marker so the display reads the merchant instead.
 */
private fun qrisMerchantHint(payload: JSONObject): String? {
    if (!payload.optBoolean("isQris")) return null
    val localized = payload.optString("localized").trim()
    if (localized.isBlank()) return null
    val cleaned = localized
        .replace(Regex("(?i)\\s*-\\s*qr?is\\s*$"), "")
        .replace(Regex("\\s+[a-z]{0,3}\\d{3,6}\\s*$"), "")
        .trim()
    if (cleaned.isBlank() || usableMerchant(cleaned) == null) return null
    // Uppercase descriptors (e.g. "WEI QIANG", "CIRCLE K - JAK0922") read better title-cased;
    // mixed-case strings (e.g. "Dunia Kedol") keep their stored casing.
    val titled = if (cleaned == cleaned.uppercase()) {
        cleaned.lowercase().split(" - ").joinToString(" - ") { part ->
            part.split(" ").joinToString(" ") { w ->
                if (w.length <= 3 && w.all { ch -> ch.isDigit() }) w
                else w.lowercase().replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
            }
        }
    } else cleaned
    // The acquirer's own name, if it is the only remaining term, is not a merchant either.
    val lowered = titled.lowercase()
    val isAcquirerOnly = listOf("bank bca", "bank mandiri", "bank bni", "bank bri", "bsi", "bank syariah", "bank jago")
        .any { lowered.startsWith(it) || lowered.endsWith(it) }
    if (isAcquirerOnly) return null
    return titled
}

/** Values promoters stored as merchant evidence but that describe nothing are not titles. */
private fun usableMerchant(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.contains("••")) return null
    if (trimmed.contains("Generic Jago payment", ignoreCase = true)) return null
    if (trimmed.contains("Gopay Tabungan Transaction", ignoreCase = true)) return null
    if (trimmed.equals("GoPay", ignoreCase = true) || trimmed.equals("GOPAY_SAVINGS_TRANSACTION", ignoreCase = true)) return null
    return trimmed
}

private fun primaryItemLabel(items: JSONArray?): String {
    if (items == null || items.length() == 0) return ""
    val first = items.optJSONObject(0)?.optString("name")?.trim().orEmpty()
    if (first.isBlank()) return ""
    val extra = items.length() - 1
    return if (extra > 0) "$first (+$extra more)" else first
}

private fun normalizeHumanLine(text: String): String {
    var line = text.trim()
    if (line.isBlank()) return ""
    line = line.replace(Regex("(?i)\\s*\\(Reversed\\)\\s*"), "").trim()
    line = line.replace('·', '-').replace('•', '-')
    line = line.replace(Regex("(?i)^([^:]+):\\s+"), "$1$HUMAN_LINE_SEP")
    val parts = line.split(Regex("\\s*-\\s*")).map { it.trim() }.filter { it.isNotBlank() }
    val deduped = mutableListOf<String>()
    for (part in parts) {
        if (deduped.lastOrNull()?.equals(part, ignoreCase = true) != true) deduped += part
    }
    return deduped.joinToString(HUMAN_LINE_SEP)
}
