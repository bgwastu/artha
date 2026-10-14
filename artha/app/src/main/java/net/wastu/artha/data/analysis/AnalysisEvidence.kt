package net.wastu.artha.data.analysis

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONArray
import org.json.JSONObject

enum class AnalysisWindow(val months: Int, val label: String) {
    THREE_MONTHS(3, "3 months"), YEAR(12, "1 year");
    fun key(month: YearMonth): String = "$month:${months}m"
}

enum class AnalysisStage(val label: String) {
    COMPARING("Comparing months"), EXAMINING("Investigating spending"),
    CATEGORIES("Checking categories"), MERCHANTS("Checking merchants"), PAYMENTS("Checking large payments"),
    REVIEWING("Reviewing findings"), SAVING("Saving analysis")
}

/** Bounded, auditable evidence. No raw provider payloads, payment IDs or credentials leave the app. */
internal fun analysisEvidence(month: YearMonth, window: AnalysisWindow, rows: List<UnifiedTransaction>, today: LocalDate): JSONObject {
    val start = month.minusMonths(window.months - 1L)
    val end = minOf(today, month.atEndOfMonth())
    val spending = rows.filter { BudgetAnalyzer.isSpending(it) &&
        it.occurredAt.atZone(AccountingTime.zone).toLocalDate() in start.atDay(1)..end }
    fun rupiah(minor: Long) = BigDecimal.valueOf(minor, 2)
    fun group(items: List<UnifiedTransaction>, name: (UnifiedTransaction) -> String, limit: Int): JSONArray =
        JSONArray(items.groupBy(name).entries.sortedByDescending { (_, payments) -> payments.sumOf { it.money.amountMinor } }
            .take(limit).map { (label, payments) -> JSONObject().put("name", label.take(160))
                .put("amountRupiah", rupiah(payments.sumOf { it.money.amountMinor })).put("paymentCount", payments.size) })
    return JSONObject().put("selectedMonth", month.toString()).put("from", start.toString()).put("through", end.toString())
        .put("months", JSONArray((0 until window.months).map { offset ->
            val current = start.plusMonths(offset.toLong())
            val payments = spending.filter { YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) == current }
            JSONObject().put("month", current.toString()).put("partial", current.atEndOfMonth() > today)
                .put("hasRecordedPayments", payments.isNotEmpty()).put("paymentCount", payments.size)
                .put("spendingRupiah", rupiah(payments.sumOf { it.money.amountMinor }))
                .put("categories", group(payments, { it.category ?: "Uncategorized" }, 50))
        })).put("merchants", group(spending, { it.description }, 15))
        .put("repeatedMerchants", JSONArray(spending.groupBy { it.description }.entries
            .filter { (_, payments) -> payments.map { YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) }.distinct().size >= 2 }
            .sortedByDescending { (_, payments) -> payments.sumOf { it.money.amountMinor } }.take(12)
            .map { (name, payments) -> JSONObject().put("name", name.take(160)).put("monthsObserved",
                payments.map { YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) }.distinct().size)
                .put("paymentCount", payments.size).put("amountRupiah", rupiah(payments.sumOf { it.money.amountMinor })) }))
        .put("largestPayments", JSONArray(spending.sortedByDescending { it.money.amountMinor }.take(8).map {
            JSONObject().put("merchant", it.description.take(160)).put("amountRupiah", rupiah(it.money.amountMinor))
                .put("date", it.occurredAt.atZone(AccountingTime.zone).toLocalDate().toString())
        }))
}
