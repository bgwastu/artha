package net.wastu.artha.core.model

import java.time.Instant
import java.util.Currency

enum class Direction { CREDIT, DEBIT }

data class Pocket(val providerPocketId: String, val name: String) {
    init {
        require(providerPocketId.isNotBlank())
        require(name.isNotBlank())
    }
}

/** Positive magnitude in ISO currency minor units. Direction carries the sign. */
data class Money(val amountMinor: Long, val currencyCode: String = "IDR") {
    init {
        require(amountMinor > 0) { "Amount must be positive; use Direction for money flow." }
        require(currencyCode.length == 3 && currencyCode == currencyCode.uppercase())
        require(Currency.getInstance(currencyCode).defaultFractionDigits >= 0)
    }

    companion object {
        /** The first UI accepts whole rupiah; 1 rupiah = 100 ISO IDR minor units. */
        fun fromRupiah(input: String): Money? {
            if (input.isEmpty() || !input.all { it in '0'..'9' }) return null
            val rupiah = input.toLongOrNull() ?: return null
            if (rupiah <= 0 || rupiah > Long.MAX_VALUE / 100) return null
            return Money(rupiah * 100)
        }
    }
}

data class UnifiedTransaction(
    val provider: Provider,
    val providerTransactionId: String,
    val direction: Direction,
    val money: Money,
    val occurredAt: Instant,
    val description: String,
    val rawPayload: String,
    val pocket: Pocket? = null,
    val isQris: Boolean = false,
    val cardLast4: String? = null,
    val userNote: String? = null,
    val category: String? = null,
    val aiReasoning: String? = null,
    val isReversal: Boolean = false,
) {
    init {
        require(providerTransactionId.isNotBlank())
        require(description.isNotBlank())
        require(rawPayload.isNotBlank())
    }
}
