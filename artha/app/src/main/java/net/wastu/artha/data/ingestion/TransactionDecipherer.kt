package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Direction
import java.util.Locale

object TransactionDecipherer {

    data class Deciphered(
        val title: String,
        val isQris: Boolean,
        val paymentMethod: String,
        val cardLast4: String? = null,
    )

    fun decipher(
        code: String,
        merchantOrCounterpart: String,
        description: String,
        notes: String,
        direction: Direction,
    ): Deciphered {
        val m = merchantOrCounterpart.trim()
        val d = description.trim()
        val n = notes.trim()
        val c = code.trim().uppercase(Locale.ROOT)

        // 1. QRIS Payments
        if (c == "QRD01" || d.startsWith("QRIS", ignoreCase = true) || n.contains("QRIS", ignoreCase = true)) {
            val merchantRaw = d.replace(Regex("(?i)^qris\\s*[-:]?\\s*"), "").trim()
                .replace(Regex("\\s+[0-9]{3,}$"), "").trim()
            val cleanTitle = when {
                merchantRaw.isNotBlank() && !merchantRaw.equals("QRIS", ignoreCase = true) -> titleCase(merchantRaw)
                m.isNotBlank() && !m.equals("QRIS", ignoreCase = true) -> "QRIS ${titleCase(m)}"
                else -> "QRIS Payment"
            }
            return Deciphered(
                title = cleanTitle,
                isQris = true,
                paymentMethod = "QRIS",
            )
        }

        // 2. Card Payments (Visa Debit / Virtual Cards)
        if (m.contains("••••") || c in setOf("PSD02", "PSD02-R") || c.startsWith("VISA")) {
            val last4 = Regex("""\b(\d{4})\b""").find(m)?.groupValues?.get(1)
            val merchantRaw = d.replace(Regex("""[\d\-+&#]+.*$"""), "").trim()
            val title = if (merchantRaw.isNotBlank()) titleCase(merchantRaw) else "Card Payment"
            return Deciphered(
                title = title,
                isQris = false,
                paymentMethod = "Visa Debit",
                cardLast4 = last4,
            )
        }

        // 3. Bank Transfers (Inter-bank, BI-FAST, Online Transfer)
        if (c in setOf("OTD10", "TFD70", "TFC51", "WTD01", "WTC01")) {
            val counterpartClean = if (d.isNotBlank()) titleCase(d) else if (m.isNotBlank()) titleCase(m) else "Account"
            val title = if (direction == Direction.DEBIT) "Transfer to $counterpartClean" else "Transfer from $counterpartClean"
            return Deciphered(
                title = title,
                isQris = false,
                paymentMethod = "Transfer",
            )
        }

        // 4. Term Deposits, Pockets, Cash, Taxes & Interest
        if (c in setOf("TDD02", "TDC08", "TDC03")) {
            return Deciphered("Term Deposit", isQris = false, paymentMethod = "Savings")
        }
        if (c in setOf("SAD01", "SAD02", "SAC01", "SAC02") || d.startsWith("Pocket Transfer", ignoreCase = true)) {
            val target = if (d.isNotBlank()) d else "Pocket"
            return Deciphered(target, isQris = false, paymentMethod = "Pocket")
        }
        if (c == "CWD04" || d.contains("cash", ignoreCase = true)) {
            return Deciphered("Cash Withdrawal", isQris = false, paymentMethod = "Cash")
        }
        if (c == "TXD99" || d.contains("tax", ignoreCase = true)) {
            return Deciphered("Tax on Interest", isQris = false, paymentMethod = "Tax")
        }
        if (c == "ISC99" || d.contains("interest", ignoreCase = true)) {
            return Deciphered("Interest Earned", isQris = false, paymentMethod = "Interest")
        }

        // 5. General Fallback
        val fallback = when {
            d.isNotBlank() -> titleCase(d)
            m.isNotBlank() -> titleCase(m)
            n.isNotBlank() -> titleCase(n)
            else -> "Payment"
        }
        return Deciphered(
            title = fallback,
            isQris = false,
            paymentMethod = "Bank Payment",
        )
    }

    private fun titleCase(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.split(" ").filter(String::isNotBlank).joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
    }
}
