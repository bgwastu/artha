package net.wastu.artha.ui

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject

enum class TransactionTagKind { REVERSED, REFUND, TRANSFER, PARTIAL_REFUND, GROUPED, QRIS, CARD }

data class TransactionTag(val kind: TransactionTagKind, val label: String) {
    /** Spoken form; the card tag is only digits on screen. */
    val description: String get() = if (kind == TransactionTagKind.CARD) "Card ending $label" else label
}

/**
 * Short facts shown before a payment title. Reversals, transfers and partial refunds are grouped
 * records too, so they replace the generic Grouped tag instead of joining it.
 */
internal fun transactionTags(transaction: UnifiedTransaction): List<TransactionTag> {
    val payload = runCatching { JSONObject(transaction.rawPayload) }.getOrNull()
    val credit = transaction.direction == Direction.CREDIT
    val reversed = transaction.isReversedRecord
    val transfer = payload?.optBoolean("isPocketTransfer") == true ||
        payload?.optBoolean("isWalletFunding") == true ||
        payload?.optBoolean("isOwnTransfer") == true ||
        transaction.description.startsWith("Pocket Transfer:", ignoreCase = true)
    // Only the reference-matched refund path records refundedAmountMinor; amount guesses never do.
    val partialRefund = !reversed && !credit && (payload?.optLong("refundedAmountMinor") ?: 0L) > 0L
    val grouped = (payload?.optJSONArray("groupedTransactions")?.length() ?: 0) > 1
    val card = (transaction.cardLast4 ?: payload?.optString("cardLast4"))
        ?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    return buildList {
        when {
            reversed -> add(TransactionTag(if (credit) TransactionTagKind.REFUND else TransactionTagKind.REVERSED, if (credit) "Refund" else "Reversed"))
            transfer -> add(TransactionTag(TransactionTagKind.TRANSFER, "Transfer"))
            partialRefund -> add(TransactionTag(TransactionTagKind.PARTIAL_REFUND, "Partial refund"))
            grouped -> add(TransactionTag(TransactionTagKind.GROUPED, "Grouped"))
        }
        if (transaction.isQris || payload?.optBoolean("isQris") == true) add(TransactionTag(TransactionTagKind.QRIS, "QRIS"))
        card?.let { add(TransactionTag(TransactionTagKind.CARD, it)) }
    }
}
