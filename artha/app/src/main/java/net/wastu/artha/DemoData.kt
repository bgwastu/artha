package net.wastu.artha

import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.SpendingState
import net.wastu.artha.ui.ArthaViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.DayOfWeek
import net.wastu.artha.data.analysis.analysisKey
import net.wastu.artha.data.analysis.BudgetAnalyzer

internal fun demoModel(dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default): ArthaViewModel {
    val rows = samplePayments()
    return ArthaViewModel(
        rootAccess = RootAccess { false },
        ledger = { error("Demo mode must never open a real ledger") },
        clock = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneOffset.UTC),
        computationDispatcher = dispatcher,
        spendingState = MutableStateFlow(SpendingState.Ready(rows, rows.groupingBy { it.provider }.eachCount())),
        initialSnapshot = AnalysisSnapshot(monthlySummaries = sampleAnalyses(rows)),
    )
}

internal fun samplePayments(): List<UnifiedTransaction> {
    fun payment(id: String, provider: Provider, rupiah: Long, date: String, name: String,
                category: String, direction: Direction = Direction.DEBIT, reversed: Boolean = false, flags: String = "") =
        UnifiedTransaction(provider, "demo-$id", direction, Money(rupiah * 100), Instant.parse(date), name,
            """{"demo":true$flags}""", category = category, isConfirmed = true, isReversal = reversed)
    val current = listOf(
        payment("coffee", Provider.JAGO, 38000, "2026-10-14T03:20:00Z", "QRIS Kopi Kenangan", "Dining Out"),
        payment("refund", Provider.SHOPEE, 85000, "2026-10-14T02:10:00Z", "Shopee refund: Buku Kita", "", Direction.CREDIT),
        payment("grocer", Provider.JAGO, 685000, "2026-10-13T11:30:00Z", "Superindo", "Groceries"),
        payment("food", Provider.GOJEK, 76000, "2026-10-13T05:15:00Z", "GoFood: Dapur Mentari", "Dining Out"),
        payment("ride", Provider.GRAB, 32000, "2026-10-12T01:45:00Z", "GrabCar to Sudirman", "Transport"),
        payment("hardware", Provider.SHOPEE, 1850000, "2026-10-11T09:00:00Z", "Shopee: Wireless headphones", "Shopping & Hardware"),
        payment("salary", Provider.JAGO, 12000000, "2026-10-10T02:00:00Z", "Payroll PT Contoh Digital", "", Direction.CREDIT),
        payment("bill", Provider.JAGO, 550000, "2026-10-09T07:00:00Z", "Internet monthly bill", "Utilities & Bills"),
        payment("reversal", Provider.JAGO, 125000, "2026-10-08T04:00:00Z", "Cloud storage (Reversed)", "Tech & Subscriptions", reversed = true),
        payment("market", Provider.JAGO, 610000, "2026-10-07T08:00:00Z", "Pasar Minggu", "Groceries"),
        payment("gym", Provider.JAGO, 400000, "2026-10-06T03:00:00Z", "Monthly gym membership", "Health & Fitness"),
        payment("meal", Provider.SHOPEE, 94000, "2026-10-05T05:00:00Z", "ShopeeFood: Lunch Box", "Dining Out"),
    )
    val history = buildList {
        var month = YearMonth.of(2023, 1)
        val last = YearMonth.of(2026, 9)
        while (month <= last) {
            val random = java.util.Random(month.year * 100L + month.monthValue)
            var sequence = 0
            fun addPayment(day: Int, provider: Provider, rupiah: Long, name: String, category: String,
                           direction: Direction = Direction.DEBIT, reversed: Boolean = false, flags: String = "") {
                val time = month.atDay(day.coerceAtMost(month.lengthOfMonth()))
                    .atTime(7 + random.nextInt(14), random.nextInt(60)).atZone(AccountingTime.zone).toInstant()
                add(payment("$month-${sequence++}", provider, rupiah, time.toString(), name, category, direction, reversed, flags))
            }
            addPayment(25, Provider.JAGO, 12000000, "Payroll PT Contoh Digital", "", Direction.CREDIT)
            addPayment(1, Provider.JAGO, 1500000, "Monthly rent", "Housing")
            addPayment(3, Provider.JAGO, 350000, "Internet monthly bill", "Utilities & Bills")
            addPayment(4, Provider.JAGO, 195000 + random.nextInt(45) * 1000L, "PLN electricity", "Utilities & Bills")
            addPayment(5, Provider.JAGO, 80000, "Telkomsel data package", "Utilities & Bills")
            addPayment(6, Provider.JAGO, 200000, "Monthly gym membership", "Health & Fitness")
            addPayment(9, Provider.JAGO, 54900, "Music subscription", "Tech & Subscriptions")
            addPayment(11, Provider.JAGO, 29000, "Cloud storage", "Tech & Subscriptions")
            addPayment(15, Provider.JAGO, 65000, "Video subscription", "Tech & Subscriptions")
            repeat(4) { i -> addPayment(3 + i * 7, Provider.JAGO, 170000 + random.nextInt(46) * 1000L,
                listOf("Superindo", "Pasar Minggu", "Alfamidi", "Superindo")[i], "Groceries") }
            repeat(12) { i -> addPayment(1 + i * 2, listOf(Provider.GOJEK, Provider.SHOPEE, Provider.GRAB)[i % 3],
                24000 + random.nextInt(19) * 1000L, listOf("GoFood: Dapur Mentari", "ShopeeFood: Ayam Bakar", "GrabFood: Warung Sari")[i % 3], "Dining Out") }
            repeat(8) { i -> addPayment(2 + i * 3, Provider.JAGO, 18000 + random.nextInt(13) * 1000L,
                listOf("QRIS Kopi Kenangan", "QRIS Toko Kopi", "QRIS Roti Pagi")[i % 3], "Dining Out") }
            (1..month.lengthOfMonth()).filter { month.atDay(it).dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
                .forEachIndexed { index, day -> addPayment(day, if (index % 2 == 0) Provider.GOJEK else Provider.GRAB,
                    12000 + random.nextInt(11) * 1000L, if (index % 2 == 0) "GoRide to office" else "GrabBike to station", "Transport") }
            addPayment(18, Provider.SHOPEE, 100000 + random.nextInt(21) * 10000L,
                listOf("Shopee: Desk accessories", "Shopee: Running socks", "Shopee: Books")[month.monthValue % 3], "Shopping & Hardware")
            addPayment(20, Provider.JAGO, 150000, "Expense reimbursement", "", Direction.CREDIT)
            addPayment(26, Provider.JAGO, 1000000, "Pocket Transfer: Savings", "", flags = ",\"isPocketTransfer\":true")
            addPayment(26, Provider.JAGO, 1000000, "Pocket Transfer: Main Pocket", "", Direction.CREDIT, flags = ",\"isPocketTransfer\":true")
            addPayment(8, Provider.JAGO, 300000, "GoPay top-up", "", flags = ",\"isWalletFunding\":true")
            addPayment(18, Provider.JAGO, 2500, "Bank transfer fee", "Utilities & Bills")
            if (month.monthValue % 3 == 0) {
                addPayment(21, Provider.SHOPEE, 89000, "Shopee refund: Returned item", "", Direction.CREDIT)
                addPayment(16, Provider.JAGO, 99000, "Card payment (Reversed)", "Shopping & Hardware", reversed = true)
            }
            month = month.plusMonths(1)
        }
    }
    return (current + history).sortedByDescending { it.occurredAt }
}

/** Saved examples, never generated from personal data or sent to an AI provider. */
private fun sampleAnalyses(rows: List<UnifiedTransaction>): Map<String, String> = buildMap {
    var month = YearMonth.of(2023, 1)
    while (month <= YearMonth.of(2026, 10)) {
        val text = if (month == YearMonth.of(2026, 10)) {
            "- Headphones are the largest purchase this month; groceries are the next biggest category.\n" +
            "- October is still in progress, so its total cannot be compared directly with a full month.\n" +
            "- Internet, rent and subscriptions recur across the saved history.\n\n### Recommendations\n" +
            "- Space out other large purchases after the headphones.\n" +
            "- Review the next grocery shop before adding another delivery."
        } else {
            val largest = BudgetAnalyzer.analyzeMonth(month, rows).categories.first().category
            "- $largest is the largest recorded spending category.\n" +
            "- Rent, internet and subscriptions recur in the recorded history.\n\n### Recommendations\n" +
            "- Review recurring subscriptions before the next renewal."
        }
        put(analysisKey(month), text)
        month = month.plusMonths(1)
    }
}
