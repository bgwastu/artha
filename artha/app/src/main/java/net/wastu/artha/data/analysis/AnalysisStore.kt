package net.wastu.artha.data.analysis

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
)

data class AnalysisSnapshot(
    val userNotes: Map<String, String> = emptyMap(),
    val categories: Map<String, String> = emptyMap(),
    val reasons: Map<String, String> = emptyMap(),
    val knowledge: String = "",
    val aiConfig: AiConfig = AiConfig(),
    val monthlySummaries: Map<String, String> = emptyMap(),
    val customCategories: List<String> = INITIAL_DEFAULT_CATEGORIES,
    val categoryIcons: Map<String, String> = emptyMap(),
    val monthlyNotes: Map<String, String> = emptyMap(),
    val updatedAt: Map<String, Long> = emptyMap(),
    val deletedCategories: Map<String, Long> = emptyMap(),
    val analysisFingerprints: Map<String, String> = emptyMap(),
    val manualCategories: Set<String> = emptySet(),
)

val INITIAL_DEFAULT_CATEGORIES = listOf(
    "Dining Out",
    "Groceries",
    "Transport",
    "Tech & Subscriptions",
    "Shopping & Hardware",
    "Clothing & Apparel",
    "Travel & Lodging",
    "Utilities & Bills",
    "Health & Fitness",
    "Personal Care",
    "Family & Social",
    "Cash",
    "Taxes & Fees",
    "Business",
    "Investments & Transfers",
    "Uncategorized",
)
class AnalysisStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("artha_analysis_store", Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<AnalysisSnapshot> = _snapshot.asStateFlow()

    private fun loadSnapshot(): AnalysisSnapshot {
        val notesJson = prefs.getString("user_notes", "{}") ?: "{}"
        val categoriesJson = prefs.getString("categories", "{}") ?: "{}"
        val reasonsJson = prefs.getString("reasons", "{}") ?: "{}"
        val summariesJson = prefs.getString("monthly_summaries", "{}") ?: "{}"
        val knowledge = prefs.getString("knowledge", "") ?: ""
        val baseUrl = prefs.getString("ai_base_url", "") ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "") ?: ""
        val customCategoriesJson = prefs.getString("custom_categories", null)
        val customCategories = if (customCategoriesJson == null) {
            val seeded = JSONArray(INITIAL_DEFAULT_CATEGORIES).toString()
            prefs.edit { putString("custom_categories", seeded) }
            INITIAL_DEFAULT_CATEGORIES
        } else {
            parseList(customCategoriesJson).ifEmpty { INITIAL_DEFAULT_CATEGORIES }
        }
        val categoryIconsJson = prefs.getString("custom_category_icons", "{}") ?: "{}"
        val monthlyNotesJson = prefs.getString("monthly_notes", "{}") ?: "{}"
        val updatedAtJson = prefs.getString("updated_at_map", "{}") ?: "{}"
        val deletedCatsJson = prefs.getString("deleted_categories_map", "{}") ?: "{}"
        val fingerprintsJson = prefs.getString("analysis_fingerprints", "{}") ?: "{}"

        return AnalysisSnapshot(
            userNotes = parseMap(notesJson),
            categories = parseMap(categoriesJson),
            reasons = parseMap(reasonsJson),
            knowledge = knowledge,
            aiConfig = AiConfig(baseUrl, apiKey, model),
            monthlySummaries = parseMap(summariesJson),
            customCategories = customCategories,
            categoryIcons = parseMap(categoryIconsJson),
            monthlyNotes = parseMap(monthlyNotesJson),
            updatedAt = parseLongMap(updatedAtJson),
            deletedCategories = parseLongMap(deletedCatsJson),
            analysisFingerprints = parseMap(fingerprintsJson),
            manualCategories = parseList(prefs.getString("manual_categories", "[]") ?: "[]").toSet(),
        )
    }

    @Synchronized
    fun setUserNote(transactionId: String, note: String) {
        val key = transactionId
        val now = System.currentTimeMillis()
        val currentNotes = _snapshot.value.userNotes.toMutableMap()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        if (note.isBlank()) {
            currentNotes.remove(key)
        } else {
            currentNotes[key] = note.trim()
        }
        currentUpdated["note:$key"] = now
        prefs.edit {
            putString("user_notes", JSONObject(currentNotes as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(userNotes = currentNotes, updatedAt = currentUpdated)
    }

    @Synchronized
    fun setClassification(transactionId: String, category: String, reason: String, manual: Boolean = true) {
        val key = transactionId
        val now = System.currentTimeMillis()
        val currentCategories = _snapshot.value.categories.toMutableMap()
        val currentReasons = _snapshot.value.reasons.toMutableMap()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        if (!manual && key in _snapshot.value.manualCategories) return
        val manualKeys = if (manual) _snapshot.value.manualCategories + key else _snapshot.value.manualCategories
        currentCategories[key] = category
        currentReasons[key] = reason
        currentUpdated["cat:$key"] = now
        prefs.edit {
            putString("manual_categories", JSONArray(manualKeys.toList()).toString())
            putString("categories", JSONObject(currentCategories as Map<*, *>).toString())
            putString("reasons", JSONObject(currentReasons as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(
            manualCategories = manualKeys,
            categories = currentCategories,
            reasons = currentReasons,
            updatedAt = currentUpdated,
        )
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
    }

    @Synchronized
    fun setBatchClassifications(updates: Map<String, Pair<String, String>>) {
        applyMergedSnapshot(TransactionMetadata.applyAutomatic(_snapshot.value, updates, System.currentTimeMillis()))
    }

    @Synchronized
    fun setKnowledge(knowledgeText: String) {
        val clean = knowledgeText.trim()
        val now = System.currentTimeMillis()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        currentUpdated["knowledge"] = now
        prefs.edit {
            putString("knowledge", clean)
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(knowledge = clean, updatedAt = currentUpdated)
    }

    @Synchronized
    fun setAiConfig(config: AiConfig) {
        val now = System.currentTimeMillis()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        currentUpdated["aiConfig"] = now
        prefs.edit {
            putString("ai_base_url", config.baseUrl.trim())
            putString("ai_api_key", config.apiKey.trim())
            putString("ai_model", config.model.trim())
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(aiConfig = config, updatedAt = currentUpdated)
    }

    @Synchronized
    fun setMonthlySummary(month: String, summary: String) {
        val now = System.currentTimeMillis()
        val currentSummaries = _snapshot.value.monthlySummaries.toMutableMap()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        currentSummaries[month] = summary.trim()
        currentUpdated["monthSummary:$month"] = now
        prefs.edit {
            putString("monthly_summaries", JSONObject(currentSummaries as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(monthlySummaries = currentSummaries, updatedAt = currentUpdated)
    }

    @Synchronized
    fun commitMonthSummary(month: String, summary: String, fingerprint: String, input: AnalysisSnapshot): Boolean {
        val current = _snapshot.value
        if (current.userNotes != input.userNotes || current.categories != input.categories || current.knowledge != input.knowledge ||
            current.aiConfig != input.aiConfig || current.customCategories != input.customCategories ||
            current.manualCategories != input.manualCategories || current.monthlyNotes[month] != input.monthlyNotes[month]) return false
        applyMergedSnapshot(current.copy(monthlySummaries = current.monthlySummaries + (month to summary),
            analysisFingerprints = current.analysisFingerprints + (month to fingerprint),
            updatedAt = current.updatedAt + ("monthSummary:$month" to System.currentTimeMillis())))
        return true
    }

    @Synchronized
    fun clearAllClassifications() {
        val current = _snapshot.value
        val now = System.currentTimeMillis()
        val times = current.updatedAt.toMutableMap()
        current.categories.keys.filter { it !in current.manualCategories }.forEach { times["cat:$it"] = now }
        current.monthlySummaries.keys.forEach { times["monthSummary:$it"] = now }
        applyMergedSnapshot(current.copy(
            categories = current.categories.filterKeys { it in current.manualCategories },
            reasons = current.reasons.filterKeys { it in current.manualCategories },
            monthlySummaries = emptyMap(), analysisFingerprints = emptyMap(), updatedAt = times,
        ))
    }

    @Synchronized
    fun setMonthFingerprint(month: String, fingerprint: String) {
        val current = _snapshot.value.analysisFingerprints.toMutableMap()
        current[month] = fingerprint
        prefs.edit { putString("analysis_fingerprints", JSONObject(current as Map<*, *>).toString()) }
        _snapshot.value = _snapshot.value.copy(analysisFingerprints = current)
    }

    @Synchronized
    fun setMonthlyNote(month: String, note: String) {
        val now = System.currentTimeMillis()
        val current = _snapshot.value.monthlyNotes.toMutableMap()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        if (note.isBlank()) {
            current.remove(month)
        } else {
            current[month] = note.trim()
        }
        currentUpdated["monthNote:$month"] = now
        prefs.edit {
            putString("monthly_notes", JSONObject(current as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(monthlyNotes = current, updatedAt = currentUpdated)
    }

    @Synchronized
    fun setCustomCategories(categories: List<String>) {
        val clean = categories.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val json = JSONArray(clean).toString()
        prefs.edit { putString("custom_categories", json) }
        _snapshot.value = _snapshot.value.copy(customCategories = clean)
    }

    @Synchronized
    fun addCustomCategory(category: String) {
        val trimmed = category.trim()
        if (trimmed.isNotBlank() && trimmed !in _snapshot.value.customCategories) {
            val now = System.currentTimeMillis()
            val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
            val currentDeleted = _snapshot.value.deletedCategories.toMutableMap()
            currentUpdated["customCat:$trimmed"] = now
            currentDeleted.remove(trimmed)
            val newCats = _snapshot.value.customCategories + trimmed
            prefs.edit {
                putString("custom_categories", JSONArray(newCats).toString())
                putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
                putString("deleted_categories_map", JSONObject(currentDeleted as Map<*, *>).toString())
            }
            _snapshot.value = _snapshot.value.copy(
                customCategories = newCats,
                updatedAt = currentUpdated,
                deletedCategories = currentDeleted,
            )
        }
    }

    @Synchronized
    fun removeCustomCategory(category: String) {
        val now = System.currentTimeMillis()
        val currentIcons = _snapshot.value.categoryIcons.toMutableMap()
        val currentDeleted = _snapshot.value.deletedCategories.toMutableMap()
        currentIcons.remove(category)
        currentDeleted[category] = now
        val newCats = _snapshot.value.customCategories.filter { it != category }
        prefs.edit {
            putString("custom_category_icons", JSONObject(currentIcons as Map<*, *>).toString())
            putString("custom_categories", JSONArray(newCats).toString())
            putString("deleted_categories_map", JSONObject(currentDeleted as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(
            customCategories = newCats,
            categoryIcons = currentIcons,
            deletedCategories = currentDeleted,
        )
    }

    @Synchronized
    fun setCategoryIcon(category: String, iconKey: String?) {
        val now = System.currentTimeMillis()
        val current = _snapshot.value.categoryIcons.toMutableMap()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        if (iconKey == null || iconKey == "default") {
            current.remove(category)
        } else {
            current[category] = iconKey
        }
        currentUpdated["icon:$category"] = now
        prefs.edit {
            putString("custom_category_icons", JSONObject(current as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(categoryIcons = current, updatedAt = currentUpdated)
    }

    @Synchronized
    fun resetCategoriesToDefault() {
        prefs.edit {
            putString("custom_category_icons", "{}")
            putString("deleted_categories_map", "{}")
            putString("custom_categories", JSONArray(INITIAL_DEFAULT_CATEGORIES).toString())
        }
        _snapshot.value = _snapshot.value.copy(
            categoryIcons = emptyMap(),
            deletedCategories = emptyMap(),
            customCategories = INITIAL_DEFAULT_CATEGORIES,
        )
    }



    @Synchronized
    fun restoreSnapshot(remote: AnalysisSnapshot) {
        val local = _snapshot.value
        val merged = net.wastu.artha.data.sync.SyncMerger.mergeAnalysis(local, remote)
        applyMergedSnapshot(merged.copy(aiConfig = local.aiConfig,
            categories = merged.categories + local.categories.filterKeys { it in local.manualCategories },
            reasons = merged.reasons + local.reasons.filterKeys { it in local.manualCategories },
            manualCategories = merged.manualCategories + local.manualCategories))
    }

    @Synchronized
    fun applyMergedSnapshot(merged: AnalysisSnapshot) {
        prefs.edit {
            putString("user_notes", JSONObject(merged.userNotes as Map<*, *>).toString())
            putString("categories", JSONObject(merged.categories as Map<*, *>).toString())
            putString("reasons", JSONObject(merged.reasons as Map<*, *>).toString())
            putString("knowledge", merged.knowledge)
            putString("ai_base_url", merged.aiConfig.baseUrl)
            putString("ai_api_key", merged.aiConfig.apiKey)
            putString("ai_model", merged.aiConfig.model)
            putString("monthly_summaries", JSONObject(merged.monthlySummaries as Map<*, *>).toString())
            putString("custom_categories", JSONArray(merged.customCategories).toString())
            putString("custom_category_icons", JSONObject(merged.categoryIcons as Map<*, *>).toString())
            putString("monthly_notes", JSONObject(merged.monthlyNotes as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(merged.updatedAt as Map<*, *>).toString())
            putString("deleted_categories_map", JSONObject(merged.deletedCategories as Map<*, *>).toString())
            putString("analysis_fingerprints", JSONObject(merged.analysisFingerprints as Map<*, *>).toString())
            putString("manual_categories", JSONArray(merged.manualCategories.toList()).toString())
        }
        _snapshot.value = merged
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
    }

    @Synchronized
    fun migrateMetadata(transactions: List<net.wastu.artha.core.model.UnifiedTransaction>) {
        val migrated = TransactionMetadata.migrate(_snapshot.value, transactions)
        if (migrated != _snapshot.value) applyMergedSnapshot(migrated)
    }

    private fun parseMap(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k)
        }
        return map
    }

    private fun parseLongMap(json: String): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optLong(k, 0L)
            if (v > 0L) map[k] = v
        }
        return map
    }

    private fun parseList(json: String): List<String> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
