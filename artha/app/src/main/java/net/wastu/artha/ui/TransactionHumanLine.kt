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
}

private fun lineFromPayload(payload: JSONObject): String? {
    val merchant = payload.optString("merchantName").ifBlank { payload.optString("counterpart") }
    if (merchant.isNotBlank()) {
        return if (payload.optBoolean("isQris") && !merchant.contains("QRIS", ignoreCase = true)) {
            "QRIS$HUMAN_LINE_SEP$merchant"
        } else {
            merchant
        }
    }
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
