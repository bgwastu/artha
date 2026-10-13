package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction

object TransactionMetadata {
    fun applyAutomatic(snapshot: AnalysisSnapshot, updates: Map<String, Pair<String, String>>, now: Long): AnalysisSnapshot {
        val allowed = updates.filterKeys { it !in snapshot.manualCategories }
        return snapshot.copy(
            categories = snapshot.categories + allowed.mapValues { it.value.first },
            reasons = snapshot.reasons + allowed.mapValues { it.value.second },
            updatedAt = snapshot.updatedAt + allowed.keys.associate { "cat:$it" to now },
        )
    }

    /** Keep ambiguous legacy entries in storage; never assign one user's edit to several payments. */
    fun migrate(snapshot: AnalysisSnapshot, transactions: List<UnifiedTransaction>): AnalysisSnapshot {
        val candidates = transactions.groupBy { it.providerTransactionId.substringAfter(":") }
        val aliases = (snapshot.userNotes.keys + snapshot.categories.keys + snapshot.reasons.keys).mapNotNull { legacy ->
            if (Provider.entries.any { legacy.startsWith("${it.name}:") }) return@mapNotNull null
            candidates[legacy]?.singleOrNull()?.let { legacy to it.key }
        }.toMap()
        if (aliases.isEmpty()) return snapshot
        fun migrateMap(values: Map<String, String>) = values.toMutableMap().apply {
            for ((old, key) in aliases) values[old]?.let { value -> putIfAbsent(key, value); remove(old) }
        }
        val times = snapshot.updatedAt.toMutableMap()
        for ((old, key) in aliases) for (prefix in listOf("note:", "cat:")) {
            times["$prefix$old"]?.let { times.putIfAbsent("$prefix$key", it); times.remove("$prefix$old") }
        }
        val categories = migrateMap(snapshot.categories)
        // Legacy storage did not distinguish manual from AI. Preserve existing decisions conservatively.
        val manual = snapshot.manualCategories + aliases.values.filter { it in categories }
        return snapshot.copy(userNotes = migrateMap(snapshot.userNotes), categories = categories,
            reasons = migrateMap(snapshot.reasons), updatedAt = times, manualCategories = manual)
    }

    fun enrich(transaction: UnifiedTransaction, snapshot: AnalysisSnapshot): UnifiedTransaction {
        val note = snapshot.userNotes[transaction.key] ?: transaction.userNote
        val classification = CategoryClassifier.classify(transaction, note)
        return transaction.copy(userNote = note,
            category = snapshot.categories[transaction.key] ?: classification.category,
            aiReasoning = snapshot.reasons[transaction.key] ?: classification.reason)
    }
}
