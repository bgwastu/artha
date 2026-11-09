package net.wastu.artha.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.TransactionClassifier
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.formatMoney
import org.json.JSONArray
import org.json.JSONObject

enum class PaymentMethod(val label: String) {
    QRIS("QRIS"),
    TRANSFER("Transfer"),
    CARD("Card");

    fun matches(transaction: UnifiedTransaction): Boolean {
        val payload = runCatching { JSONObject(transaction.rawPayload) }.getOrNull()
        return when (this) {
            QRIS -> transaction.isQris ||
                payload?.optBoolean("isQris") == true ||
                payload?.optString("f1").equals("QRD01", ignoreCase = true) ||
                payload?.optString("code").equals("QRD01", ignoreCase = true) ||
                payload?.optString("paymentMethod").equals("QRIS", ignoreCase = true) ||
                transaction.description.startsWith("QRIS", ignoreCase = true)

            TRANSFER -> payload?.optBoolean("isPocketTransfer") == true ||
                payload?.optBoolean("isWalletFunding") == true ||
                payload?.optBoolean("isOwnTransfer") == true ||
                payload?.optString("paymentMethod") in setOf("Transfer", "Top Up", "Pocket", "Savings") ||
                payload?.optString("code") in TransactionClassifier.INTERNAL_TRANSFER_CODES ||
                payload?.optString("code") in TransactionClassifier.WALLET_FUNDING_CODES ||
                payload?.optString("code") in setOf("OTD10", "TFD70", "TFC51", "WTD01", "WTC01") ||
                transaction.description.startsWith("Pocket Transfer:", ignoreCase = true) ||
                transaction.description.startsWith("Transfer ", ignoreCase = true) ||
                transaction.description.startsWith("Top up", ignoreCase = true) ||
                transaction.description.startsWith("Topup", ignoreCase = true)

            CARD -> transaction.cardLast4 != null ||
                payload?.optString("cardLast4")?.isNotBlank() == true ||
                payload?.optString("cardMask")?.isNotBlank() == true ||
                payload?.optString("paymentMethod") in setOf("Visa Debit", "Credit Card", "Card") ||
                payload?.optString("code") in setOf("PSD02", "PSD02-R")
        }
    }
}

data class TransactionFilters(
    val provider: Provider? = null,
    val direction: Direction? = null,
    val type: PaymentMethod? = null,
) {
    fun matches(transaction: UnifiedTransaction): Boolean =
        transaction.isConfirmed &&
        (provider == null || transaction.provider == provider) &&
        (direction == null || transaction.direction == direction) &&
        (type == null || type.matches(transaction))
}

internal fun transactionSearchScope(
    allSaved: List<UnifiedTransaction>,
    filters: TransactionFilters = TransactionFilters(),
    range: Pair<Long?, Long?>? = null,
): List<UnifiedTransaction> {
    val start = range?.first
    val endExclusive = range?.second?.let(AccountingTime::dayAfter)
    return allSaved.filter {
        val millis = it.occurredAt.toEpochMilli()
        filters.matches(it) && (start == null || millis >= start) && (endExclusive == null || millis < endExclusive)
    }
}

val TransactionFiltersSaver = listSaver<TransactionFilters, String>(
    save = { listOf(it.provider?.name.orEmpty(), it.direction?.name.orEmpty(), it.type?.name.orEmpty()) },
    restore = { list ->
        TransactionFilters(
            provider = list.getOrNull(0)?.takeIf(String::isNotBlank)?.let { name ->
                runCatching { Provider.valueOf(name) }.getOrNull()
            },
            direction = list.getOrNull(1)?.takeIf(String::isNotBlank)?.let { name ->
                runCatching { Direction.valueOf(name) }.getOrNull()
            },
            type = list.getOrNull(2)?.takeIf(String::isNotBlank)?.let { name ->
                PaymentMethod.entries.firstOrNull { it.name == name }
            },
        )
    },
)

enum class TransactionPeriod(val label: String) {
    ALL("All time"), THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_30_DAYS("Last 30 days"), CUSTOM("Custom dates");
    fun range(today: LocalDate): Pair<Long?, Long?>? {
        val days = when (this) {
            ALL, CUSTOM -> return null
            THIS_MONTH -> today.withDayOfMonth(1) to today
            LAST_MONTH -> today.minusMonths(1).let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
            LAST_30_DAYS -> today.minusDays(29) to today
        }
        return days.first.atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli() to
            days.second.atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli()
    }
}

// Money rejects zero, but an empty period or day legitimately totals nothing.
internal fun formatSpendingTotal(totalMinor: Long): String = if (totalMinor <= 0L) "Rp0" else formatMoney(Money(totalMinor))

internal fun rangeLabel(range: Pair<Long?, Long?>): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    fun date(value: Long?) = value?.let { Instant.ofEpochMilli(it).atZone(AccountingTime.zone).toLocalDate().format(formatter) }
    val start = date(range.first)
    val end = date(range.second)
    return if (start != null && end != null && start != end) "$start to $end" else start ?: end ?: "Dates"
}

internal data class PeriodSummary(
    val totalMinor: Long,
    val count: Int,
    val label: String,
    val incomeMinor: Long = 0L,
    val incomeCount: Int = 0,
) {
    val netMinor: Long get() = incomeMinor - totalMinor
    val rows: Int get() = count + incomeCount
}

/** "+Rp1.000", "-Rp1.000" or "Rp0"; Money rejects zero so the sign and amount are formatted separately. */
internal fun formatNetTotal(netMinor: Long): String = when {
    netMinor > 0L -> "+" + formatMoney(Money(netMinor))
    netMinor < 0L -> "-" + formatMoney(Money(-netMinor))
    else -> "Rp0"
}

internal fun periodSummary(
    rows: List<UnifiedTransaction>,
    range: Pair<Long?, Long?>?,
    today: LocalDate,
    category: String? = null,
): PeriodSummary {
    val spending = rows.filter(UnifiedTransaction::isExpense)
    val totalMinor = spending.sumOf { it.money.amountMinor }
    val count = spending.size
    val income = rows.filter(UnifiedTransaction::isIncome)
    val label = when {
        range != null -> periodLabel(range, today)
        category != null -> category
        else -> ""
    }
    return PeriodSummary(totalMinor, count, label, income.sumOf { it.money.amountMinor }, income.size)
}

/** Preset name when the range matches one ("This month"), otherwise the dates. Used by the summary and the chip. */
internal fun periodLabel(range: Pair<Long?, Long?>, today: LocalDate): String = when (range) {
    TransactionPeriod.THIS_MONTH.range(today) -> TransactionPeriod.THIS_MONTH.label
    TransactionPeriod.LAST_MONTH.range(today) -> TransactionPeriod.LAST_MONTH.label
    TransactionPeriod.LAST_30_DAYS.range(today) -> TransactionPeriod.LAST_30_DAYS.label
    else -> rangeLabel(range)
}

internal data class DayGroup(val date: LocalDate, val transactions: List<UnifiedTransaction>)

internal fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now(AccountingTime.clock)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US))
    }
}

// Traverse JSON values so nested notes, counterpart names and item names are searchable.
internal fun payloadTextValues(rawPayload: String): List<String> = runCatching {
    buildList {
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> collect(value.opt(key)) }
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
                is String -> if (value.isNotBlank()) add(value)
            }
        }
        collect(org.json.JSONTokener(rawPayload).nextValue())
    }
}.getOrElse { listOf(rawPayload) }
