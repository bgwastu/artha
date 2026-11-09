package net.wastu.artha.api.service

import io.ktor.http.Parameters
import java.time.Instant
import java.time.YearMonth
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.MerchantMemory

/** A query parameter that could not be read; [expected] describes what would have been accepted. */
internal class BadParam(val name: String, val expected: String) : Exception("Invalid '$name': expected $expected")

/** Matches [value] to a transaction type ignoring case; null when it names none. */
internal fun parseType(value: String): TransactionType? =
    TransactionType.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }

/** The filters, ordering and paging of `GET /api/v1/transactions`. */
internal class TransactionQuery private constructor(
    val limit: Int,
    val offset: Int,
    private val month: YearMonth?,
    private val from: Instant?,
    private val to: Instant?,
    private val provider: Provider?,
    private val direction: Direction?,
    private val type: TransactionType?,
    private val category: String?,
    private val search: String?,
    private val unreviewedOnly: Boolean,
    private val minAmountMinor: Long?,
    private val maxAmountMinor: Long?,
    private val sort: Sort?,
) {
    private enum class Sort(val param: String) {
        DATE_DESC("date_desc"),
        DATE_ASC("date_asc"),
        AMOUNT_DESC("amount_desc"),
        AMOUNT_ASC("amount_asc"),
    }

    /** Every match in the requested order, before paging. */
    fun select(transactions: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): List<UnifiedTransaction> {
        val zone = AccountingTime.zone
        val filtered = transactions.asSequence()
            .filter { month == null || YearMonth.from(it.occurredAt.atZone(zone)) == month }
            .filter { from == null || it.occurredAt >= from }
            .filter { to == null || it.occurredAt < to }
            .filter { provider == null || it.provider == provider }
            .filter { direction == null || it.direction == direction }
            .filter { type == null || it.type == type }
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
            .filter { !unreviewedOnly || it.category == null || (!it.classificationIsManual && it.key !in snapshot.manualCategories) }
            .filter { search == null || mentions(it, search.trim()) }
            .filter { minAmountMinor == null || it.money.amountMinor >= minAmountMinor }
            .filter { maxAmountMinor == null || it.money.amountMinor <= maxAmountMinor }
        return when (sort) {
            Sort.DATE_DESC -> filtered.sortedByDescending { it.occurredAt }
            Sort.DATE_ASC -> filtered.sortedBy { it.occurredAt }
            Sort.AMOUNT_DESC -> filtered.sortedByDescending { it.money.amountMinor }
            Sort.AMOUNT_ASC -> filtered.sortedBy { it.money.amountMinor }
            null -> filtered
        }.toList()
    }

    private fun mentions(tx: UnifiedTransaction, needle: String): Boolean =
        tx.description.lowercase().contains(needle) ||
            tx.userNote?.lowercase()?.contains(needle) == true ||
            MerchantMemory.displayMerchant(tx)?.lowercase()?.contains(needle) == true

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200
        private const val TIMESTAMP_HINT = "an ISO-8601 timestamp such as"

        /** Reads the parameters in a fixed order; throws [BadParam] for the first one that is invalid. */
        fun parse(params: Parameters): TransactionQuery {
            fun text(name: String) = params[name]?.takeIf { it.isNotBlank() }
            fun <T : Any> read(name: String, expected: String, parse: (String) -> T?): T? =
                text(name)?.let { parse(it) ?: throw BadParam(name, expected) }

            val limit = params["limit"]?.let { it.toIntOrNull() ?: throw BadParam("limit", "an integer") } ?: DEFAULT_LIMIT
            val offset = params["offset"]?.let { it.toIntOrNull() ?: throw BadParam("offset", "an integer") } ?: 0
            return TransactionQuery(
                limit = limit.coerceIn(1, MAX_LIMIT),
                offset = offset.coerceAtLeast(0),
                month = read("month", "YYYY-MM") { runCatching { YearMonth.parse(it) }.getOrNull() },
                from = read("from", "$TIMESTAMP_HINT 2026-04-01T00:00:00Z") { runCatching { Instant.parse(it) }.getOrNull() },
                to = read("to", "$TIMESTAMP_HINT 2026-05-01T00:00:00Z") { runCatching { Instant.parse(it) }.getOrNull() },
                provider = read("provider", Provider.entries.joinToString(", ") { it.name }) { value ->
                    Provider.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                },
                direction = read("direction", Direction.entries.joinToString(" or ") { it.name }) { value ->
                    Direction.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                },
                type = read("type", "expense, income or transfer", ::parseType),
                category = text("category"),
                search = text("search")?.lowercase(),
                unreviewedOnly = params["unreviewedOnly"]?.let { it.toBooleanStrictOrNull() ?: throw BadParam("unreviewedOnly", "true or false") } == true,
                minAmountMinor = read("minAmountMinor", "an integer") { it.toLongOrNull() },
                maxAmountMinor = read("maxAmountMinor", "an integer") { it.toLongOrNull() },
                sort = read("sort", "date_desc, date_asc, amount_desc or amount_asc") { value ->
                    Sort.entries.firstOrNull { it.param == value }
                },
            )
        }
    }
}
