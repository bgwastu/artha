package net.wastu.artha.data.ledger

import androidx.room.Entity
import androidx.room.Index
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.Instant

@Entity(tableName = "unified_transactions", primaryKeys = ["provider", "providerTransactionId"],
    indices = [Index(value = ["occurredAtEpochMillis", "provider", "providerTransactionId"], orders = [Index.Order.DESC, Index.Order.ASC, Index.Order.ASC])])
data class TransactionEntity(
    val provider: String,
    val providerTransactionId: String,
    val direction: String,
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAtEpochMillis: Long,
    val description: String,
    val rawPayload: String,
    val pocketId: String?,
    val pocketName: String?,
    val ingestedAtEpochMillis: Long,
) {
    fun toDomain() = UnifiedTransaction(
        provider = Provider.valueOf(provider),
        providerTransactionId = providerTransactionId,
        direction = Direction.valueOf(direction),
        money = Money(amountMinor, currencyCode),
        occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis),
        description = description,
        rawPayload = rawPayload,
        pocket = pocketId?.let { Pocket(it, requireNotNull(pocketName)) },
        isQris = rawPayload.contains("\"isQris\":true") || rawPayload.contains("\"f1\":\"QRD01\"") || rawPayload.contains("\"code\":\"QRD01\""),
        cardLast4 = Regex("\"cardLast4\":\"(\\d{4})\"").find(rawPayload)?.groupValues?.get(1),
    )

    companion object {
        fun fromDomain(transaction: UnifiedTransaction, ingestedAt: Instant) = TransactionEntity(
            provider = transaction.provider.name,
            providerTransactionId = transaction.providerTransactionId,
            direction = transaction.direction.name,
            amountMinor = transaction.money.amountMinor,
            currencyCode = transaction.money.currencyCode,
            occurredAtEpochMillis = transaction.occurredAt.toEpochMilli(),
            description = transaction.description,
            rawPayload = transaction.rawPayload,
            pocketId = transaction.pocket?.providerPocketId,
            pocketName = transaction.pocket?.name,
            ingestedAtEpochMillis = ingestedAt.toEpochMilli(),
        )
    }
}
