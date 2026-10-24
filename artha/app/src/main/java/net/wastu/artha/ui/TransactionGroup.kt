package net.wastu.artha.ui

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject

/** One source record inside a reconciled transaction, rebuilt from its stored group entry. */
data class GroupRecord(val role: String, val transaction: UnifiedTransaction) {
    val title: String get() = transactionHumanLine(transaction)
    /** Records only show how they were paid; grouping facts belong to the parent row. */
    val tags: List<TransactionTag> get() = transactionTags(transaction)
        .filter { it.kind == TransactionTagKind.QRIS || it.kind == TransactionTagKind.CARD }
    val signedAmount: String get() = "${if (transaction.direction == Direction.CREDIT) "+" else ""}${formatMoney(transaction.money)}"
}

internal fun groupRecords(transaction: UnifiedTransaction): List<GroupRecord> {
    val groups = runCatching { JSONObject(transaction.rawPayload).optJSONArray("groupedTransactions") }.getOrNull() ?: return emptyList()
    if (groups.length() < 2) return emptyList()
    return (0 until groups.length()).mapNotNull { index ->
        val entry = groups.optJSONObject(index) ?: return@mapNotNull null
        runCatching {
            val pocketName = entry.optString("pocketName").takeUnless { entry.isNull("pocketName") || it.isBlank() }
            GroupRecord(linkedSourceRole(entry), UnifiedTransaction(
                provider = Provider.valueOf(entry.getString("provider")),
                providerTransactionId = entry.getString("providerTransactionId"),
                direction = Direction.valueOf(entry.getString("direction")),
                money = Money(entry.getLong("amountMinor"), entry.optString("currencyCode", "IDR")),
                occurredAt = Instant.ofEpochMilli(entry.getLong("occurredAtEpochMillis")),
                description = entry.getString("description"),
                rawPayload = entry.optString("rawPayload").ifBlank { "{}" },
                pocket = pocketName?.let { Pocket(it, it) },
                isQris = entry.optBoolean("isQris"),
                cardLast4 = entry.optString("cardLast4").takeUnless { entry.isNull("cardLast4") || it.isBlank() },
            ))
        }.getOrNull()
    }.takeIf { it.size >= 2 }.orEmpty()
}

/**
 * Net money across a group that moved in both directions (a charge and its refunds), as
 * debits minus credits. Same-direction groups describe one payment seen by several sources,
 * so adding them up would double count; those return null.
 */
internal fun groupNetMinor(records: List<GroupRecord>): Long? {
    val directions = records.map { it.transaction.direction }.toSet()
    if (directions.size < 2 || records.map { it.transaction.money.currencyCode }.toSet().size != 1) return null
    return records.sumOf { if (it.transaction.direction == Direction.DEBIT) it.transaction.money.amountMinor else -it.transaction.money.amountMinor }
}

/** Sentence-case role for a group entry; unknown or cross-source-ambiguous roles stay neutral. */
internal fun linkedSourceRole(record: JSONObject): String = when (val role = record.optString("role")) {
    "Payment", "Order details", "Receipt", "Refund" -> role
    "Original Charge" -> "Original charge"
    "Partial Refund" -> "Partial refund"
    "Card Payment" -> "Card payment"
    "Wallet Payment" -> "Wallet payment"
    "Merchant Payment" -> "Merchant payment"
    "Digital Purchase" -> "Digital purchase"
    "Bank Payment" -> if (record.optString("provider") == Provider.JAGO.name) "Bank payment" else "Source record"
    "Marketplace Order" -> "Order details"
    else -> if (role.startsWith("From ") || role.startsWith("To ")) role else "Source record"
}
