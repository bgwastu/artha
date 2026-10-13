package net.wastu.artha.data.sync

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.INITIAL_DEFAULT_CATEGORIES
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class SyncBundle(
    val version: Int = 1,
    val exportedAtEpochMillis: Long = System.currentTimeMillis(),
    val analysis: AnalysisSnapshot,
    val transactions: List<UnifiedTransaction>,
)

class UnsupportedSyncVersionException(version: Int) : Exception("Unsupported backup version: $version. Update Artha before restoring this backup.")

object SyncBundleSerializer {

    fun toJsonBytes(bundle: SyncBundle): ByteArray {
        val root = JSONObject()
        root.put("version", bundle.version)
        root.put("exportedAtEpochMillis", bundle.exportedAtEpochMillis)

        // 1. Analysis Snapshot
        val a = bundle.analysis
        val analysisObj = JSONObject()
            .put("userNotes", JSONObject(a.userNotes as Map<*, *>))
            .put("categories", JSONObject(a.categories as Map<*, *>))
            .put("reasons", JSONObject(a.reasons as Map<*, *>))
            .put("knowledge", a.knowledge)
            .put(
                "aiConfig",
                JSONObject()
                    .put("baseUrl", a.aiConfig.baseUrl)
                    .put("apiKey", a.aiConfig.apiKey)
                    .put("model", a.aiConfig.model),
            )
            .put("monthlySummaries", JSONObject(a.monthlySummaries as Map<*, *>))
            .put("customCategories", JSONArray(a.customCategories))
            .put("categoryIcons", JSONObject(a.categoryIcons as Map<*, *>))
            .put("monthlyNotes", JSONObject(a.monthlyNotes as Map<*, *>))
            .put("updatedAt", JSONObject(a.updatedAt as Map<*, *>))
            .put("deletedCategories", JSONObject(a.deletedCategories as Map<*, *>))
            .put("analysisFingerprints", JSONObject(a.analysisFingerprints as Map<*, *>))
            .put("manualCategories", JSONArray(a.manualCategories.toList()))
        root.put("analysis", analysisObj)

        // 2. Transactions
        val txArray = JSONArray()
        for (tx in bundle.transactions) {
            val txObj = JSONObject()
                .put("provider", tx.provider.name)
                .put("providerTransactionId", tx.providerTransactionId)
                .put("direction", tx.direction.name)
                .put("amountMinor", tx.money.amountMinor)
                .put("currencyCode", tx.money.currencyCode)
                .put("occurredAtEpochMillis", tx.occurredAt.toEpochMilli())
                .put("description", tx.description)
                .put("rawPayload", tx.rawPayload)
            if (tx.pocket != null) {
                txObj.put("pocketId", tx.pocket.providerPocketId)
                txObj.put("pocketName", tx.pocket.name)
            }
            if (tx.cardLast4 != null) txObj.put("cardLast4", tx.cardLast4)
            txObj.put("isQris", tx.isQris)
            txArray.put(txObj)
        }
        root.put("transactions", txArray)

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun fromJsonBytes(bytes: ByteArray): SyncBundle {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw CorruptedSyncPayloadException("Invalid sync bundle JSON.", e)
        }

        val version = root.getInt("version")
        if (version != 1) throw UnsupportedSyncVersionException(version)
        val exportedAt = root.optLong("exportedAtEpochMillis", 0L)

        val analysisObj = root.getJSONObject("analysis")
        val aiObj = analysisObj.optJSONObject("aiConfig")
        val aiConfig = AiConfig(
            baseUrl = aiObj?.optString("baseUrl").orEmpty(),
            apiKey = aiObj?.optString("apiKey").orEmpty(),
            model = aiObj?.optString("model").orEmpty(),
        )

        val analysis = AnalysisSnapshot(
            userNotes = parseStringMap(analysisObj.optJSONObject("userNotes")),
            categories = parseStringMap(analysisObj.optJSONObject("categories")),
            reasons = parseStringMap(analysisObj.optJSONObject("reasons")),
            knowledge = analysisObj.optString("knowledge", ""),
            aiConfig = aiConfig,
            monthlySummaries = parseStringMap(analysisObj.optJSONObject("monthlySummaries")),
            customCategories = parseStringList(analysisObj.optJSONArray("customCategories")).ifEmpty { INITIAL_DEFAULT_CATEGORIES },
            categoryIcons = parseStringMap(analysisObj.optJSONObject("categoryIcons")),
            monthlyNotes = parseStringMap(analysisObj.optJSONObject("monthlyNotes")),
            updatedAt = parseLongMap(analysisObj.optJSONObject("updatedAt")),
            deletedCategories = parseLongMap(analysisObj.optJSONObject("deletedCategories")),
            analysisFingerprints = parseStringMap(analysisObj.optJSONObject("analysisFingerprints")),
            manualCategories = parseStringList(analysisObj.optJSONArray("manualCategories")).toSet(),
        )

        val txArray = root.getJSONArray("transactions")
        val transactions = mutableListOf<UnifiedTransaction>()
        for (i in 0 until txArray.length()) {
            val obj = txArray.getJSONObject(i)
            val providerName = obj.optString("provider")
            val provider = Provider.valueOf(providerName)
            val txId = obj.getString("providerTransactionId")
            val direction = Direction.valueOf(obj.getString("direction"))
            val amountMinor = obj.getLong("amountMinor")
            val currencyCode = obj.getString("currencyCode")
            val occurredAtMs = obj.getLong("occurredAtEpochMillis")
            val description = obj.getString("description")
            val rawPayload = obj.getString("rawPayload")
            JSONObject(rawPayload) // Validate every row before any restore writes.
            val pocketId = obj.optString("pocketId").takeIf { it.isNotBlank() && it != "null" }
            val pocketName = obj.optString("pocketName").takeIf { it.isNotBlank() && it != "null" }
            val pocket = if (pocketId != null && pocketName != null) Pocket(pocketId, pocketName) else null
            val cardLast4 = obj.optString("cardLast4").takeIf { it.length == 4 && it.all(Char::isDigit) }
            val isQris = obj.optBoolean("isQris", false)

            transactions.add(
                UnifiedTransaction(
                    provider = provider,
                    providerTransactionId = txId,
                    direction = direction,
                    money = Money(amountMinor, currencyCode),
                    occurredAt = Instant.ofEpochMilli(occurredAtMs),
                    description = description,
                    rawPayload = rawPayload,
                    pocket = pocket,
                    cardLast4 = cardLast4,
                    isQris = isQris,
                )
            )
        }

        return SyncBundle(
            version = version,
            exportedAtEpochMillis = exportedAt,
            analysis = analysis,
            transactions = transactions,
        )
    }

    private fun parseStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k)
        }
        return map
    }

    private fun parseLongMap(obj: JSONObject?): Map<String, Long> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, Long>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optLong(k, 0L)
            if (v > 0L) map[k] = v
        }
        return map
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

object SyncMerger {

    /**
     * Deterministic two-way Last-Write-Wins (LWW) + tombstone merge of local and remote analysis state.
     * Safe for fresh-install (empty local) syncing against populated remote backups.
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
     * Returns remote transactions that are not yet present in [localTransactions] so they can be
     * safely appended to the append-only Room database via `ledger.append()`.
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
