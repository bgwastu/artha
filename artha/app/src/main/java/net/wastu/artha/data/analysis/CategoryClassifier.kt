package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject

object CategoryConstants {
    const val INVESTMENTS_TRANSFERS = "Investments & Transfers"
    const val UNCATEGORIZED = "Uncategorized"
}

data class ClassificationResult(
    val category: String,
    val reason: String,
)

object CategoryClassifier {
    fun classify(
        tx: UnifiedTransaction,
        userNote: String? = null,
    ): ClassificationResult {
        val payload = runCatching { JSONObject(tx.rawPayload) }.getOrNull()
        val code = payload?.optString("code").orEmpty()
        val f22 = payload?.optJSONObject("fields")?.optString("f22").orEmpty()
        val isInternalMove = code in setOf("SAD01", "SAD02", "SAC01", "SAC02", "TDD02", "TDC02", "TDC03", "TDC08", "TDC12") ||
            f22 == "INTERNAL" ||
            payload?.optBoolean("isPocketTransfer") == true ||
            tx.description.startsWith("Pocket Transfer:", ignoreCase = true) ||
            tx.description.equals("Term Deposit", ignoreCase = true)

        if (isInternalMove) {
            return ClassificationResult(
                CategoryConstants.INVESTMENTS_TRANSFERS,
                "Internal pocket or term deposit movement.",
            )
        }

        val text = listOf(tx.description, userNote.orEmpty(), payload?.optString("serviceTier").orEmpty(),
            payload?.optString("origin").orEmpty(), payload?.optString("shopName").orEmpty(), payload?.optString("restaurantName").orEmpty()).joinToString(" ").lowercase(java.util.Locale.ROOT)
        fun has(vararg words: String) = words.any { word -> Regex("(?<![a-z])${Regex.escape(word)}(?![a-z])").containsMatchIn(text) }
        val category = when {
            has("gofood", "grabfood", "shopeefood", "restaurant", "restoran", "coffee", "kopi", "pizza", "dinner", "lunch", "cafe") -> "Dining Out"
            has("goride", "gocar", "grabride", "grabbike", "grabcar", "transport", "taxi") || payload?.optString("pickupLocation")?.isNotBlank() == true -> "Transport"
            has("indomaret", "alfamart", "superindo", "groceries", "supermarket") -> "Groceries"
            has("github", "netflix", "spotify", "chatgpt", "openai", "anthropic", "cursor", "subscription") -> "Tech & Subscriptions"
            has("pln", "telkom", "pulsa", "electricity", "internet bill") -> "Utilities & Bills"
            code == "CWD04" || has("cash withdrawal") -> "Cash"
            code == "TXD99" || has("tax on interest") -> "Taxes & Fees"
            has("business", "work expense") -> "Business"
            tx.provider in setOf(net.wastu.artha.core.model.Provider.SHOPEE, net.wastu.artha.core.model.Provider.TOKOPEDIA) || has("shopee", "tokopedia") -> "Shopping & Hardware"
            else -> CategoryConstants.UNCATEGORIZED
        }
        return ClassificationResult(category, if (category == CategoryConstants.UNCATEGORIZED) "" else "Local provider and merchant rule; you can choose a different category.")
    }
}
