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

        return ClassificationResult(CategoryConstants.UNCATEGORIZED, "")
    }
}
