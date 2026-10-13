package net.wastu.artha.ui

import net.wastu.artha.core.model.Money
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
    .withZone(ZoneId.systemDefault())

private val CachedCurrencyFormat = ThreadLocal.withInitial {
    NumberFormat.getCurrencyInstance(IndonesianLocale).apply {
        currency = Currency.getInstance("IDR")
        minimumFractionDigits = 0
        maximumFractionDigits = 0
    }
}

fun formatMoney(money: Money): String {
    if (money.currencyCode == "IDR" && money.amountMinor % 100L == 0L) {
        val rupiah = money.amountMinor / 100L
        return requireNotNull(CachedCurrencyFormat.get()).format(rupiah)
    }
    return formatMinorAmount(BigInteger.valueOf(money.amountMinor), money.currencyCode)
}

fun formatMinorAmount(amountMinor: BigInteger, currencyCode: String = "IDR"): String {
    if (currencyCode == "IDR") {
        val rupiah = amountMinor.divide(BigInteger.valueOf(100)).toLong()
        return requireNotNull(CachedCurrencyFormat.get()).format(rupiah)
    }
    val currency = Currency.getInstance(currencyCode)
    val amount = BigDecimal(amountMinor, currency.defaultFractionDigits)
    return NumberFormat.getCurrencyInstance(IndonesianLocale).apply {
        this.currency = currency
        minimumFractionDigits = if (amount.stripTrailingZeros().scale() <= 0) 0 else currency.defaultFractionDigits
        maximumFractionDigits = currency.defaultFractionDigits
    }.format(amount)
}

fun formatDate(instant: Instant): String = TransactionDateFormatter.format(instant)

private val TransactionTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm", Locale.US)
    .withZone(ZoneId.systemDefault())

fun formatTime(instant: Instant): String = TransactionTimeFormatter.format(instant)
