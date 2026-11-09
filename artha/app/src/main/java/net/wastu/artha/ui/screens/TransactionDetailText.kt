package net.wastu.artha.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.TransactionClassifier
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.PaymentCategoryState
import org.json.JSONException
import org.json.JSONObject

internal fun transactionAccountingLabel(transaction: UnifiedTransaction): String? {
    if (transaction.isExpense) return null
    val payload = try { JSONObject(transaction.rawPayload) } catch (_: JSONException) { null }
    return when {
        transaction.isReversedRecord -> if (transaction.direction == Direction.CREDIT) "Incoming reversal" else "Reversed"
        transaction.direction == Direction.CREDIT -> "Incoming"
        transaction.money.currencyCode != "IDR" -> "Outside IDR spending"
        payload?.optBoolean("isWalletFunding") == true || payload?.optString("code") in TransactionClassifier.WALLET_FUNDING_CODES -> "Wallet funding"
        payload?.optBoolean("isPocketTransfer") == true || transaction.description.startsWith("Pocket Transfer:", ignoreCase = true) -> "Pocket transfer"
        payload?.optString("code") in TransactionClassifier.INTERNAL_TRANSFER_CODES ||
            payload?.optJSONObject("fields")?.optString("f22") == "INTERNAL" -> "Transfer"
        else -> "Not included in spending"
    }
}

internal fun linkedRecordsExplanation(transaction: UnifiedTransaction): String? {
    val groups = try { JSONObject(transaction.rawPayload).optJSONArray("groupedTransactions") } catch (_: JSONException) { null } ?: return null
    return if (groups.length() > 1 && transaction.canonicalKey != null && transaction.isExpense)
        "Counted once in spending." else null
}

internal fun paymentNoteRowSupporting(state: PaymentCategoryState): String? =
    if (state != PaymentCategoryState.IDLE) "Locked while categorizing" else null
