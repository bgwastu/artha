package net.wastu.artha.data.demo

import kotlinx.coroutines.delay
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.data.analysis.AiCategorization
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.CategoryCandidate
import net.wastu.artha.data.analysis.CategorySuggestion
import java.time.Instant

/** Stands in for the model in the demo build: waits like a request would, then answers from the generated ledger. */
object DemoAi {
    private val known: Map<String, String> by lazy {
        DemoLedger.generate(Instant.now(), AccountingTime.zone).transactions.mapNotNull { tx -> tx.category?.let { tx.key to it } }.toMap()
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun categorize(config: AiConfig, snapshot: AnalysisSnapshot, batch: List<CategoryCandidate>): Map<String, CategorySuggestion> {
        delay(900L + batch.size * 120L)
        return batch.associate { candidate ->
            val tx = candidate.transaction
            val options = AiCategorization.categories(snapshot, tx.type)
            val category = known[tx.key]?.takeIf { it in options } ?: options.first()
            candidate.transaction.key to CategorySuggestion(category, "Matched the merchant and amount to $category", 97, true)
        }
    }
}
