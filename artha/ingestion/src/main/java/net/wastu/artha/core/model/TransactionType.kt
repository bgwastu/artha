package net.wastu.artha.core.model

import org.json.JSONObject

/** What a payment means for the user's money: moving it around, spending it, or earning it. */
enum class TransactionType { TRANSFER, EXPENSE, INCOME }

object TransactionClassifier {
    /** Jago codes for moves between the user's own pockets and deposits. */
    val INTERNAL_TRANSFER_CODES = setOf("TDD02", "TDC02", "TDC03", "TDC08", "TDC12", "SAD01", "SAD02", "SAC01", "SAC02")

    /** Jago codes for top-ups into the user's own e-wallets. */
    val WALLET_FUNDING_CODES = setOf("WTD02", "WTD03", "WTD06", "WTD-AJ-EWALLET")

    /** Jago marks interest and its tax "INTERNAL" too, but they are real income and expense. */
    val INTEREST_CODES = setOf("ISC99", "TXD99")

    /** Source evidence only; a user override is applied separately by [UnifiedTransaction.type]. */
    fun classify(tx: UnifiedTransaction): TransactionType {
        if (!tx.isConfirmed || tx.isReversedRecord) return TransactionType.TRANSFER
        val payload = runCatching { JSONObject(tx.rawPayload) }.getOrNull()
        val debit = tx.direction == Direction.DEBIT
        if (payload?.optBoolean("isOwnTransfer") == true) return TransactionType.TRANSFER
        // Money sent to a wallet Artha can't read is the last place Artha sees that spending.
        if (debit && payload?.optBoolean("untrackedWalletTopUp") == true) return TransactionType.EXPENSE
        if (payload?.optBoolean("isWalletFunding") == true) return TransactionType.TRANSFER
        if (payload?.optBoolean("isPocketTransfer") == true || tx.description.startsWith("Pocket Transfer:", ignoreCase = true)) {
            return TransactionType.TRANSFER
        }
        val code = payload?.optString("code").orEmpty()
        if (code in INTERNAL_TRANSFER_CODES || code in WALLET_FUNDING_CODES) return TransactionType.TRANSFER
        if (code in INTEREST_CODES) return if (debit) TransactionType.EXPENSE else TransactionType.INCOME
        if (payload?.optJSONObject("fields")?.optString("f22") == "INTERNAL") return TransactionType.TRANSFER
        return if (debit) TransactionType.EXPENSE else TransactionType.INCOME
    }
}
