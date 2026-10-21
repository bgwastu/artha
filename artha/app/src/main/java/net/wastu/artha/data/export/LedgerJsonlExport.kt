package net.wastu.artha.data.export

import java.time.Instant
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.SpendingProjector
import net.wastu.artha.data.ledger.ProviderObservations
import org.json.JSONArray
import org.json.JSONObject

/** Manual export for offline analysis; one JSON object per line (JSONL). */
object LedgerJsonlExport {
    const val VERSION = 2

    fun build(rawLedger: List<UnifiedTransaction>, snapshot: AnalysisSnapshot, exportedAt: Instant): String {
        val ready = SpendingProjector().project(rawLedger, snapshot)
        val counts = Provider.entries.associateWith { provider ->
            rawLedger.count { it.provider == provider && !ProviderObservations.isObservation(it) }
        }
        val lines = buildList {
            add(metaLine(ready.transactions.size, counts, snapshot, exportedAt))
            ready.transactions.forEach { add(transactionLine(it, snapshot)) }
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun metaLine(
        transactionCount: Int,
        counts: Map<Provider, Int>,
        snapshot: AnalysisSnapshot,
        exportedAt: Instant,
    ): String = JSONObject()
        .put("record", "meta")
        .put("version", VERSION)
        .put("exportedAt", exportedAt.toString())
        .put("transactionCount", transactionCount)
        .put("providers", JSONObject(counts.mapKeys { (provider, _) -> provider.name }))
        .put(
            "analysis",
            JSONObject()
                .put("knowledge", snapshot.knowledge)
                .put("customCategories", JSONArray(snapshot.customCategories))
                .put("categoryIcons", JSONObject(snapshot.categoryIcons as Map<*, *>))
                .put("monthlyNotes", JSONObject(snapshot.monthlyNotes as Map<*, *>))
                .put("monthlySummaries", JSONObject(snapshot.monthlySummaries as Map<*, *>))
                .put("userNotes", JSONObject(snapshot.userNotes as Map<*, *>))
                .put("categories", JSONObject(snapshot.categories as Map<*, *>))
                .put("reasons", JSONObject(snapshot.reasons as Map<*, *>))
                .put("manualCategories", JSONArray(snapshot.manualCategories.toList()))
                .put("updatedAt", JSONObject(snapshot.updatedAt.mapValues { it.value.toString() } as Map<*, *>)),
        )
        .toString()

    private fun transactionLine(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): String {
        val reason = snapshot.reasons[tx.key].orEmpty().ifBlank { tx.aiReasoning.orEmpty() }
        return JSONObject()
            .put("record", "transaction")
            .put("key", tx.key)
            .put("provider", tx.provider.name)
            .put("providerTransactionId", tx.providerTransactionId)
            .put("direction", tx.direction.name)
            .put("amountMinor", tx.money.amountMinor)
            .put("currencyCode", tx.money.currencyCode)
            .put("occurredAt", tx.occurredAt.toString())
            .put("description", tx.description)
            .put("pocketId", tx.pocket?.providerPocketId)
            .put("pocketName", tx.pocket?.name)
            .put("isQris", tx.isQris)
            .put("cardLast4", tx.cardLast4)
            .put("isReversal", tx.isReversal)
            .put("isConfirmed", tx.isConfirmed)
            .put("canonicalKey", tx.canonicalKey)
            .put("category", tx.category)
            .put("userNote", tx.userNote)
            .put("classificationIsManual", tx.classificationIsManual)
            .put("aiReasoning", reason)
            .put("rawPayload", tx.rawPayload)
            .toString()
    }
}
