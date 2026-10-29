package net.wastu.artha.data.analysis

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

    /** Old rule output stays stored for recovery, but is no longer presented as a category. */
    fun category(snapshot: AnalysisSnapshot, key: String): String? = snapshot.categories[key]?.takeUnless {
        key !in snapshot.manualCategories && snapshot.reasons[key]?.startsWith("Local provider and merchant rule") == true
    }

    fun enrich(transaction: UnifiedTransaction, snapshot: AnalysisSnapshot): UnifiedTransaction {
        val note = snapshot.userNotes[transaction.key] ?: transaction.userNote

        return transaction.copy(userNote = note, classificationIsManual = transaction.key in snapshot.manualCategories,
            category = category(snapshot, transaction.key),
            aiReasoning = if (category(snapshot, transaction.key) != null) snapshot.reasons[transaction.key].orEmpty() else "")
    }
}
