package net.wastu.artha.core.model

import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

private val IndonesianLocale = Locale.forLanguageTag("id-ID")

private val CachedCurrencyFormat = ThreadLocal.withInitial {
    NumberFormat.getCurrencyInstance(IndonesianLocale).apply {
        currency = Currency.getInstance("IDR")
        minimumFractionDigits = 0
        maximumFractionDigits = 0
    }
}

private val FractionalCurrencyFormat = ThreadLocal.withInitial {
    NumberFormat.getCurrencyInstance(IndonesianLocale).apply {
        currency = Currency.getInstance("IDR"); minimumFractionDigits = 0; maximumFractionDigits = 2
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
        return requireNotNull(FractionalCurrencyFormat.get()).format(BigDecimal(amountMinor, 2))
    }
    val currency = Currency.getInstance(currencyCode)
    val amount = BigDecimal(amountMinor, currency.defaultFractionDigits)
    return NumberFormat.getCurrencyInstance(IndonesianLocale).apply {
        this.currency = currency
        minimumFractionDigits = if (amount.stripTrailingZeros().scale() <= 0) 0 else currency.defaultFractionDigits
        maximumFractionDigits = currency.defaultFractionDigits
    }.format(amount)
}

