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
        val legacyKeys = (snapshot.userNotes.keys + snapshot.categories.keys + snapshot.reasons.keys).filter { key ->
            Provider.entries.none { key.startsWith("${it.name}:") }
        }
        if (legacyKeys.isEmpty()) return snapshot
        val candidates = transactions.groupBy { it.providerTransactionId.substringAfter(":") }
        val aliases = legacyKeys.mapNotNull { legacy ->
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

    /** Old rule output stays stored for recovery, but is no longer presented as a category. */
    fun category(snapshot: AnalysisSnapshot, key: String): String? = snapshot.categories[key]?.takeUnless {
        key !in snapshot.manualCategories && snapshot.reasons[key]?.startsWith("Local provider and merchant rule") == true
    }

    fun enrich(transaction: UnifiedTransaction, snapshot: AnalysisSnapshot): UnifiedTransaction {
        val note = snapshot.userNotes[transaction.key] ?: transaction.userNote

        return transaction.copy(userNote = note, classificationIsManual = transaction.key in snapshot.manualCategories,
            category = category(snapshot, transaction.key) ?: CategoryConstants.UNCATEGORIZED,
            aiReasoning = if (category(snapshot, transaction.key) != null) snapshot.reasons[transaction.key].orEmpty() else "")
    }
}
