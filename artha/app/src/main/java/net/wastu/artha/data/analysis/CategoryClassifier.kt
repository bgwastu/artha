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
    private fun words(vararg values: String) = Regex("(?<![a-z])(?:${values.joinToString("|") { Regex.escape(it) }})(?![a-z])")
    private val patterns = mapOf(
        "Dining Out" to words("gofood", "grabfood", "shopeefood", "restaurant", "restoran", "coffee", "kopi", "pizza", "dinner", "lunch", "cafe"),
        "Transport" to words("goride", "gocar", "grabride", "grabbike", "grabcar", "transport", "taxi"),
        "Groceries" to words("indomaret", "alfamart", "superindo", "groceries", "supermarket"),
        "Tech & Subscriptions" to words("github", "netflix", "spotify", "chatgpt", "openai", "anthropic", "cursor", "subscription"),
        "Utilities & Bills" to words("pln", "telkom", "pulsa", "electricity", "internet bill"),
        "Cash" to words("cash withdrawal"), "Taxes & Fees" to words("tax on interest"),
        "Business" to words("business", "work expense"), "Shopping & Hardware" to words("shopee", "tokopedia"),
    )
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
        fun has(category: String) = patterns.getValue(category).containsMatchIn(text)
        val category = when {
            has("Dining Out") -> "Dining Out"
            has("Transport") || payload?.optString("pickupLocation")?.isNotBlank() == true -> "Transport"
            has("Groceries") -> "Groceries"
            has("Tech & Subscriptions") -> "Tech & Subscriptions"
            has("Utilities & Bills") -> "Utilities & Bills"
            code == "CWD04" || has("Cash") -> "Cash"
            code == "TXD99" || has("Taxes & Fees") -> "Taxes & Fees"
            has("Business") -> "Business"
            tx.provider in setOf(net.wastu.artha.core.model.Provider.SHOPEE, net.wastu.artha.core.model.Provider.TOKOPEDIA) || has("Shopping & Hardware") -> "Shopping & Hardware"
            else -> CategoryConstants.UNCATEGORIZED
        }
        return ClassificationResult(category, if (category == CategoryConstants.UNCATEGORIZED) "" else "Local provider and merchant rule; you can choose a different category.")
    }
}
