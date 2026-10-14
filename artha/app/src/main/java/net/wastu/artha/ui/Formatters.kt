package net.wastu.artha.ui

import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.UnifiedTransaction
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private val IndonesianLocale = Locale.forLanguageTag("id-ID")

private val TransactionDateFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("d MMM yyyy, HH:mm", Locale.US)
    .withZone(net.wastu.artha.core.model.AccountingTime.zone)

fun formatMoney(money: Money): String = net.wastu.artha.core.model.formatMoney(money)
fun formatMinorAmount(amountMinor: BigInteger, currencyCode: String = "IDR"): String = net.wastu.artha.core.model.formatMinorAmount(amountMinor, currencyCode)

fun formatDate(instant: Instant): String = TransactionDateFormatter.format(instant)

private val TransactionTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm", Locale.US)
    .withZone(net.wastu.artha.core.model.AccountingTime.zone)

fun formatTime(instant: Instant): String = TransactionTimeFormatter.format(instant)

/** A reversing credit is still incoming money; only a voided outgoing charge has zero net spending. */
internal fun paymentRowAmount(transaction: UnifiedTransaction): String {
    val credit = transaction.direction == Direction.CREDIT
    val reversed = transaction.isReversal || transaction.description.contains("(Reversed)", ignoreCase = true)
    return if (!credit && reversed) "Rp0" else "${if (credit) "+" else "−"}${formatMoney(transaction.money)}"
}

/** Keep the original charge available when a refund changes its displayed net amount. */
internal fun originalPaymentMoney(transaction: UnifiedTransaction): Money {
    val gross = runCatching { org.json.JSONObject(transaction.rawPayload).opt("grossPaidAmountMinor") }.getOrNull()
    val amount = when (gross) {
        is Byte, is Short, is Int, is Long -> (gross as Number).toLong()
        is String -> gross.toLongOrNull()
        else -> null
    }?.takeIf { it > 0L } ?: transaction.money.amountMinor
    return transaction.money.copy(amountMinor = amount)
}
