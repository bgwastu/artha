package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction

object AnalysisSyncMerger {

    /**
     * Deterministic two-way Last-Write-Wins (LWW) + tombstone merge of local and remote analysis state.
     */
    fun mergeAnalysis(local: AnalysisSnapshot, remote: AnalysisSnapshot): AnalysisSnapshot {
        val mergedUpdatedAt = mutableMapOf<String, Long>()
        for (k in (local.updatedAt.keys + remote.updatedAt.keys)) {
            mergedUpdatedAt[k] = maxOf(local.updatedAt[k] ?: 0L, remote.updatedAt[k] ?: 0L)
        }

        // 1. Categories & Reasons (keyed by "cat:$txKey")
        val mergedCategories = mutableMapOf<String, String>()
        val mergedReasons = mutableMapOf<String, String>()
        for (k in (local.categories.keys + remote.categories.keys +
            local.updatedAt.keys.filter { it.startsWith("cat:") }.map { it.removePrefix("cat:") } +
            remote.updatedAt.keys.filter { it.startsWith("cat:") }.map { it.removePrefix("cat:") })) {
            val localTime = local.updatedAt["cat:$k"] ?: if (k in local.categories) 1L else 0L
            val remoteTime = remote.updatedAt["cat:$k"] ?: if (k in remote.categories) 1L else 0L
            if (remoteTime > localTime || (remoteTime == localTime && remote.categories[k].orEmpty() > local.categories[k].orEmpty())) {
                remote.categories[k]?.let { mergedCategories[k] = it }
                remote.reasons[k]?.let { mergedReasons[k] = it }
            } else {
                local.categories[k]?.let { mergedCategories[k] = it }
                local.reasons[k]?.let { mergedReasons[k] = it }
            }
        }

        // 2. User Notes (keyed by "note:$txKey")
        val mergedUserNotes = mergeLwwStringMap(
            localMap = local.userNotes,
            remoteMap = remote.userNotes,
            localTimes = local.updatedAt,
            remoteTimes = remote.updatedAt,
            prefix = "note:",
        )

        // 3. Monthly Notes (keyed by "monthNote:$month")
        val mergedMonthlyNotes = mergeLwwStringMap(
            localMap = local.monthlyNotes,
            remoteMap = remote.monthlyNotes,
            localTimes = local.updatedAt,
            remoteTimes = remote.updatedAt,
            prefix = "monthNote:",
        )

        // 4. Monthly Summaries (keyed by "monthSummary:$month")
        val mergedMonthlySummaries = mergeLwwStringMap(
            localMap = local.monthlySummaries,
            remoteMap = remote.monthlySummaries,
            localTimes = local.updatedAt,
            remoteTimes = remote.updatedAt,
            prefix = "monthSummary:",
        )

        // 5. Knowledge Base
        val localKnowledgeTime = local.updatedAt["knowledge"] ?: if (local.knowledge.isNotBlank()) 1L else 0L
        val remoteKnowledgeTime = remote.updatedAt["knowledge"] ?: if (remote.knowledge.isNotBlank()) 1L else 0L
        val mergedKnowledge = if (remoteKnowledgeTime > localKnowledgeTime) remote.knowledge else local.knowledge

        // 6. AI Config
        val localAiTime = local.updatedAt["aiConfig"] ?: 0L
        val remoteAiTime = remote.updatedAt["aiConfig"] ?: 0L
        val mergedAiConfig = if (remoteAiTime > localAiTime) remote.aiConfig else local.aiConfig

        // 7. Custom Categories & Tombstones
        val mergedDeletedCategories = mutableMapOf<String, Long>()
        for (cat in (local.deletedCategories.keys + remote.deletedCategories.keys)) {
            mergedDeletedCategories[cat] = maxOf(local.deletedCategories[cat] ?: 0L, remote.deletedCategories[cat] ?: 0L)
        }
        val candidateCategories = (local.customCategories + remote.customCategories).distinct()
        val mergedCustomCategories = candidateCategories.filter { cat ->
            val addedTime = maxOf(
                local.updatedAt["customCat:$cat"] ?: if (cat in local.customCategories) 1L else 0L,
                remote.updatedAt["customCat:$cat"] ?: if (cat in remote.customCategories) 1L else 0L,
            )
            val deletedTime = mergedDeletedCategories[cat] ?: 0L
            addedTime >= deletedTime
        }.ifEmpty { INITIAL_DEFAULT_CATEGORIES }

        // 8. Category Icons (keyed by "icon:$cat")
        val mergedIcons = mergeLwwStringMap(
            localMap = local.categoryIcons,
            remoteMap = remote.categoryIcons,
            localTimes = local.updatedAt,
            remoteTimes = remote.updatedAt,
            prefix = "icon:",
        ).filterKeys { it in mergedCustomCategories }

        val mergedFingerprints = mergeLwwStringMap(
            localMap = local.analysisFingerprints,
            remoteMap = remote.analysisFingerprints,
            localTimes = local.updatedAt,
            remoteTimes = remote.updatedAt,
            prefix = "monthSummary:",
        )

        return AnalysisSnapshot(
            userNotes = mergedUserNotes,
            categories = mergedCategories,
            reasons = mergedReasons,
            knowledge = mergedKnowledge,
            aiConfig = mergedAiConfig,
            monthlySummaries = mergedMonthlySummaries,
            customCategories = mergedCustomCategories,
            categoryIcons = mergedIcons,
            monthlyNotes = mergedMonthlyNotes,
            updatedAt = mergedUpdatedAt,
            deletedCategories = mergedDeletedCategories,
            analysisFingerprints = mergedFingerprints,
            manualCategories = (local.manualCategories + remote.manualCategories).filter { it in mergedCategories }.toSet(),
        )
    }

    /**
     * Returns remote transactions not yet present locally for append-only ledger import.
     */
    fun findMissingTransactionsToAppend(
        localTransactions: List<UnifiedTransaction>,
        remoteTransactions: List<UnifiedTransaction>,
    ): List<UnifiedTransaction> {
        val existingKeys = localTransactions.mapTo(mutableSetOf()) { "${it.provider.name}:${it.providerTransactionId}" }
        return remoteTransactions.filter { "${it.provider.name}:${it.providerTransactionId}" !in existingKeys }
    }

    private fun mergeLwwStringMap(
        localMap: Map<String, String>,
        remoteMap: Map<String, String>,
        localTimes: Map<String, Long>,
        remoteTimes: Map<String, Long>,
        prefix: String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val allKeys = localMap.keys + remoteMap.keys +
            localTimes.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) } +
            remoteTimes.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

        for (k in allKeys.distinct()) {
            val lTime = localTimes["$prefix$k"] ?: if (k in localMap) 1L else 0L
            val rTime = remoteTimes["$prefix$k"] ?: if (k in remoteMap) 1L else 0L
            val chosen = if (rTime > lTime || (rTime == lTime && remoteMap[k].orEmpty() > localMap[k].orEmpty())) remoteMap[k] else localMap[k]
            if (!chosen.isNullOrBlank()) {
                result[k] = chosen
            }
        }
        return result
    }
}
