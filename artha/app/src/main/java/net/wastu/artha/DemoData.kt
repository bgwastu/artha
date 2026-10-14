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

internal fun demoModel(dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default): ArthaViewModel {
    val rows = samplePayments()
    return ArthaViewModel(
        rootAccess = RootAccess { false },
        ledger = { error("Demo mode must never open a real ledger") },
        clock = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneOffset.UTC),
        computationDispatcher = dispatcher,
        spendingState = MutableStateFlow(SpendingState.Ready(rows, rows.groupingBy { it.provider }.eachCount())),
        initialSnapshot = AnalysisSnapshot(monthlySummaries = mapOf("2026-10" to
            "Shopping is the largest category this month, led by the headphones purchase. Groceries are next. " +
            "The reversed subscription contributes Rp0 to spending. This commentary uses fictional demo data.")),
    )
}

internal fun samplePayments(): List<UnifiedTransaction> {
    fun payment(id: String, provider: Provider, rupiah: Long, date: String, name: String,
                category: String, direction: Direction = Direction.DEBIT, reversed: Boolean = false) =
        UnifiedTransaction(provider, "demo-$id", direction, Money(rupiah * 100), Instant.parse(date), name,
            """{"demo":true}""", category = category, isConfirmed = true, isReversal = reversed)
    return listOf(
        payment("coffee", Provider.JAGO, 38000, "2026-10-14T03:20:00Z", "Morning Coffee", "Dining Out"),
        payment("refund", Provider.SHOPEE, 85000, "2026-10-14T02:10:00Z", "Book Corner refund", "", Direction.CREDIT),
        payment("grocer", Provider.JAGO, 685000, "2026-10-13T11:30:00Z", "Demo Grocer", "Groceries"),
        payment("food", Provider.GOJEK, 76000, "2026-10-13T05:15:00Z", "Garden Kitchen", "Dining Out"),
        payment("ride", Provider.GRAB, 32000, "2026-10-12T01:45:00Z", "Ride to city centre", "Transport"),
        payment("hardware", Provider.SHOPEE, 1850000, "2026-10-11T09:00:00Z", "Wireless headphones", "Shopping & Hardware"),
        payment("salary", Provider.JAGO, 12000000, "2026-10-10T02:00:00Z", "Sample salary", "", Direction.CREDIT),
        payment("bill", Provider.JAGO, 550000, "2026-10-09T07:00:00Z", "Monthly broadband", "Utilities & Bills"),
        payment("reversal", Provider.JAGO, 125000, "2026-10-08T04:00:00Z", "Sample subscription (Reversed)", "Tech & Subscriptions", reversed = true),
        payment("market", Provider.JAGO, 610000, "2026-10-07T08:00:00Z", "Weekend Market", "Groceries"),
        payment("gym", Provider.JAGO, 400000, "2026-10-06T03:00:00Z", "Neighbourhood Fitness", "Health & Fitness"),
        payment("meal", Provider.SHOPEE, 94000, "2026-10-05T05:00:00Z", "Lunch Box", "Dining Out"),
        payment("prev-grocery", Provider.JAGO, 620000, "2026-09-25T05:00:00Z", "Previous sample groceries", "Groceries"),
        payment("prev-bills", Provider.JAGO, 350000, "2026-09-23T05:00:00Z", "Previous sample broadband", "Utilities & Bills"),
        payment("prev-food", Provider.GOJEK, 420000, "2026-09-20T05:00:00Z", "Previous sample meals", "Dining Out"),
        payment("prev-shopping", Provider.SHOPEE, 680000, "2026-09-18T05:00:00Z", "Previous sample shopping", "Shopping & Hardware"),
        payment("prev-gym", Provider.JAGO, 200000, "2026-09-16T05:00:00Z", "Previous sample fitness", "Health & Fitness"),
        payment("prev-transport", Provider.GRAB, 180000, "2026-09-12T05:00:00Z", "Previous sample transport", "Transport"),
    )
}
