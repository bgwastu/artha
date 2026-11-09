package net.wastu.artha.notifications

import java.time.Duration
import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.model.formatMoney
import net.wastu.artha.data.ledger.ProviderObservations

/** Decisions about provider push notifications and newly imported payments, kept free of Android types. */
internal object ProviderNotificationRules {
    /** Older payments were backfilled by an import, not just made, so they are not worth an alert. */
    private val FRESH_WINDOW: Duration = Duration.ofDays(2)

    /** Marketplace and ride apps push mostly promotions; only these words suggest money moved. */
    private val PAYMENT_WORDS = Regex(
        "\\brp\\s?\\d|pembayaran|dibayar|transaksi|top.?up|transfer|pesanan|order|saldo|refund|berhasil",
        RegexOption.IGNORE_CASE,
    )

    fun providerFor(packageName: String): Provider? = Provider.entries.firstOrNull { it.appPackage == packageName }

    /** A bank's notifications are all account activity; other apps need payment wording. */
    fun worthSyncing(provider: Provider, title: CharSequence?, text: CharSequence?): Boolean =
        provider == Provider.JAGO || PAYMENT_WORDS.containsMatchIn("${title.orEmpty()} ${text.orEmpty()}")

    /** Confirmed payments that appeared since [knownKeys]; none on the very first import, which only backfills. */
    fun freshPayments(knownKeys: Set<String>, current: List<UnifiedTransaction>, now: Instant): List<UnifiedTransaction> {
        if (knownKeys.isEmpty()) return emptyList()
        val cutoff = now.minus(FRESH_WINDOW)
        return current
            .filter { it.isConfirmed && !ProviderObservations.isObservation(it) && it.key !in knownKeys && it.occurredAt >= cutoff }
            .sortedByDescending { it.occurredAt }
    }

    data class Alert(val title: String, val text: String)

    fun alertFor(payments: List<UnifiedTransaction>): Alert? {
        val latest = payments.firstOrNull() ?: return null
        if (payments.size == 1) {
            val sign = if (latest.direction == Direction.CREDIT) "+" else ""
            return Alert("$sign${formatMoney(latest.money)} · ${latest.provider.displayName}", latest.description)
        }
        val providers = payments.map { it.provider.displayName }.distinct().joinToString(", ")
        return Alert("${payments.size} new transactions", providers)
    }

    private fun CharSequence?.orEmpty(): CharSequence = this ?: ""
}
