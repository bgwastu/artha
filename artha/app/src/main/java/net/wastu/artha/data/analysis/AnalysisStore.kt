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
data class AnalysisFailure(
    val message: String,
    val at: Long,
    val fingerprint: String,
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
    val aiCategoryFingerprints: Map<String, String> = emptyMap(),
    val analysisEnabled: Boolean = false,
    /** AI confidence per payment key; LOW (and MEDIUM without item details) means "Needs review". */
    val aiConfidence: Map<String, CategoryConfidence> = emptyMap(),
    /** Payments the AI gave up on after retries, with the user-facing reason. Local cache, not backup content. */
    val categoryFailures: Map<String, String> = emptyMap(),
    val autoCategorize: Boolean = true,
    val aiMerchantConsistent: Set<String> = emptySet(),
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
    companion object {
        /** defaults shared with the pi.dev CLIProxyAPI gateway (see ~/.pi/agent/cliproxyapi.json). */
        const val DEFAULT_AI_BASE_URL = "https://ai.wastu.net"
        const val DEFAULT_AI_MODEL = "gemini-3.8-flash-high"
    }
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("artha_analysis_store", Context.MODE_PRIVATE)

    private val secret = StoredSecret(prefs, "artha_ai_config_key", "ai_api_key")
    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<AnalysisSnapshot> = _snapshot.asStateFlow()
    private val _analysisFailures = MutableStateFlow(loadAnalysisFailures())
    val analysisFailures: StateFlow<Map<String, AnalysisFailure>> = _analysisFailures.asStateFlow()

    private fun loadSnapshot(): AnalysisSnapshot {
        val notesJson = prefs.getString("user_notes", "{}") ?: "{}"
        val categoriesJson = prefs.getString("categories", "{}") ?: "{}"
        val reasonsJson = prefs.getString("reasons", "{}") ?: "{}"
        val summariesJson = prefs.getString("monthly_summaries", "{}") ?: "{}"
        val knowledge = prefs.getString("knowledge", "") ?: ""
        val baseUrl = prefs.getString("ai_base_url", "")?.takeUnless { it.isBlank() } ?: DEFAULT_AI_BASE_URL
        val apiKey = secret.read()
        val model = prefs.getString("ai_model", "")?.takeUnless { it.isBlank() } ?: DEFAULT_AI_MODEL
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
        val aiConfidenceJson = prefs.getString("ai_confidence", "{}") ?: "{}"
        val categoryFailuresJson = prefs.getString("ai_category_failures", "{}") ?: "{}"
        val autoCategorize = prefs.getBoolean("auto_categorize", true)
        val aiMerchantConsistentJson = prefs.getString("ai_merchant_consistent", "[]") ?: "[]"

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
            aiCategoryFingerprints = parseMap(prefs.getString("ai_category_fingerprints", "{}") ?: "{}"),
            analysisEnabled = prefs.getBoolean("analysis_enabled", true),
            aiConfidence = parseConfidenceMap(aiConfidenceJson),
            categoryFailures = parseMap(categoryFailuresJson),
            autoCategorize = autoCategorize,
            aiMerchantConsistent = parseList(aiMerchantConsistentJson).toSet(),
        )
    }

    @Synchronized
    fun setAnalysisEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("analysis_enabled", enabled) }
        _snapshot.value = _snapshot.value.copy(analysisEnabled = enabled)
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
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
        checkedPreferenceEdit(prefs, listOf("user_notes", "updated_at_map"), "Note could not be saved") {
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
        val currentFailures = _snapshot.value.categoryFailures.toMutableMap()
        currentFailures.remove(key)
        if (!manual && key in _snapshot.value.manualCategories) return
        val manualKeys = if (manual) _snapshot.value.manualCategories + key else _snapshot.value.manualCategories
        currentCategories[key] = category
        currentReasons[key] = reason
        currentUpdated["cat:$key"] = now
        checkedPreferenceEdit(prefs, listOf("manual_categories", "categories", "reasons", "updated_at_map", "ai_category_failures"), "Category could not be saved") {
            putString("manual_categories", JSONArray(manualKeys.toList()).toString())
            putString("categories", JSONObject(currentCategories as Map<*, *>).toString())
            putString("reasons", JSONObject(currentReasons as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
            putString("ai_category_failures", JSONObject(currentFailures as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(
            manualCategories = manualKeys,
            categories = currentCategories,
            reasons = currentReasons,
            updatedAt = currentUpdated,
            categoryFailures = currentFailures,
        )
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
    }

    /** Lets the AI replace a user's pick after they confirmed "Replace your pick?". */
    @Synchronized
    fun removeManualCategory(key: String) {
        val manual = _snapshot.value.manualCategories - key
        if (manual == _snapshot.value.manualCategories) return
        checkedPreferenceEdit(prefs, listOf("manual_categories"), "Category could not be saved") {
            putString("manual_categories", JSONArray(manual.toList()).toString())
        }
        _snapshot.value = _snapshot.value.copy(manualCategories = manual)
    }

    @Synchronized
    fun commitCategorySuggestions(candidates: List<CategoryCandidate>, suggestions: Map<String, CategorySuggestion>, input: AnalysisSnapshot): Int {
        val current = _snapshot.value
        if (current.aiConfig != input.aiConfig || current.knowledge != input.knowledge || current.customCategories != input.customCategories) return 0
        val valid = candidates.filter { candidate ->
            val key = candidate.transaction.key
            BudgetAnalyzer.isSpending(candidate.transaction) &&
                AiCategorization.fingerprint(candidate.transaction, current) == candidate.fingerprint &&
                !candidate.transaction.classificationIsManual && key !in current.manualCategories &&
                current.userNotes[key] == input.userNotes[key] &&
                current.updatedAt["cat:$key"] == input.updatedAt["cat:$key"] &&
                suggestions[key]?.category in AiCategorization.categories(current)
        }
        if (valid.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val categories = current.categories + valid.associate { it.transaction.key to suggestions.getValue(it.transaction.key).category }
        val reasons = current.reasons + valid.associate { it.transaction.key to suggestions.getValue(it.transaction.key).reason }
        val times = current.updatedAt + valid.associate { "cat:${it.transaction.key}" to now }
        val fingerprints = current.aiCategoryFingerprints + valid.associate { it.transaction.key to it.fingerprint }
        val confidences = current.aiConfidence + valid.associate { it.transaction.key to suggestions.getValue(it.transaction.key).confidence }
        val validKeys = valid.map { it.transaction.key }.toSet()
        val failures = current.categoryFailures - validKeys
        val merchantConsistent = current.aiMerchantConsistent.toMutableSet().apply {
            for (c in valid) {
                val sug = suggestions.getValue(c.transaction.key)
                if (sug.merchantConsistent) add(c.transaction.key) else remove(c.transaction.key)
            }
        }.toSet()
        checkedPreferenceEdit(prefs, listOf("categories", "reasons", "updated_at_map", "ai_category_fingerprints", "ai_confidence", "ai_category_failures", "ai_merchant_consistent"), "AI categories could not be saved") {
            putString("categories", JSONObject(categories as Map<*, *>).toString())
            putString("reasons", JSONObject(reasons as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(times as Map<*, *>).toString())
            putString("ai_category_fingerprints", JSONObject(fingerprints as Map<*, *>).toString())
            putString("ai_confidence", JSONObject(confidences.mapValues { it.value.name } as Map<*, *>).toString())
            putString("ai_category_failures", JSONObject(failures as Map<*, *>).toString())
            putString("ai_merchant_consistent", JSONArray(merchantConsistent.toList()).toString())
        }
        _snapshot.value = current.copy(
            categories = categories,
            reasons = reasons,
            updatedAt = times,
            aiCategoryFingerprints = fingerprints,
            aiConfidence = confidences,
            categoryFailures = failures,
            aiMerchantConsistent = merchantConsistent,
        )
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
        return valid.size
    }

    @Synchronized
    fun commitMerchantSuggestions(candidates: List<CategoryCandidate>, suggestions: Map<String, CategorySuggestion>, input: AnalysisSnapshot): Int {
        val current = _snapshot.value
        if (current.aiConfig != input.aiConfig || current.knowledge != input.knowledge || current.customCategories != input.customCategories) return 0
        val valid = candidates.filter { candidate ->
            val key = candidate.transaction.key
            BudgetAnalyzer.isSpending(candidate.transaction) &&
                !candidate.transaction.classificationIsManual && key !in current.manualCategories &&
                current.userNotes[key] == input.userNotes[key] &&
                current.updatedAt["cat:$key"] == input.updatedAt["cat:$key"] &&
                suggestions[key]?.category in AiCategorization.categories(current)
        }
        if (valid.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val categories = current.categories + valid.associate { it.transaction.key to suggestions.getValue(it.transaction.key).category }
        val reasons = current.reasons + valid.associate { it.transaction.key to suggestions.getValue(it.transaction.key).reason }
        val times = current.updatedAt + valid.associate { "cat:${it.transaction.key}" to now }
        val fingerprints = current.aiCategoryFingerprints + valid.associate { it.transaction.key to it.fingerprint }
        val confidences = current.aiConfidence + valid.associate { it.transaction.key to suggestions.getValue(it.transaction.key).confidence }
        val validKeys = valid.map { it.transaction.key }.toSet()
        val failures = current.categoryFailures - validKeys
        checkedPreferenceEdit(prefs, listOf("categories", "reasons", "updated_at_map", "ai_category_fingerprints", "ai_confidence", "ai_category_failures"), "Merchant categories could not be saved") {
            putString("categories", JSONObject(categories as Map<*, *>).toString())
            putString("reasons", JSONObject(reasons as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(times as Map<*, *>).toString())
            putString("ai_category_fingerprints", JSONObject(fingerprints as Map<*, *>).toString())
            putString("ai_confidence", JSONObject(confidences.mapValues { it.value.name } as Map<*, *>).toString())
            putString("ai_category_failures", JSONObject(failures as Map<*, *>).toString())
        }
        _snapshot.value = current.copy(
            categories = categories,
            reasons = reasons,
            updatedAt = times,
            aiCategoryFingerprints = fingerprints,
            aiConfidence = confidences,
            categoryFailures = failures,
        )
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
        return valid.size
    }

    @Synchronized
    fun commitMerchantSuggestions(suggestions: Map<String, CategorySuggestion>, input: AnalysisSnapshot): Int {
        val current = _snapshot.value
        if (current.aiConfig != input.aiConfig || current.knowledge != input.knowledge || current.customCategories != input.customCategories) return 0
        val validKeys = suggestions.keys.filter { key ->
            key !in current.manualCategories &&
                current.userNotes[key] == input.userNotes[key] &&
                current.updatedAt["cat:$key"] == input.updatedAt["cat:$key"] &&
                suggestions[key]?.category in AiCategorization.categories(current)
        }
        if (validKeys.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val categories = current.categories + validKeys.associateWith { suggestions.getValue(it).category }
        val reasons = current.reasons + validKeys.associateWith { suggestions.getValue(it).reason }
        val times = current.updatedAt + validKeys.associate { "cat:$it" to now }
        val confidences = current.aiConfidence + validKeys.associateWith { suggestions.getValue(it).confidence }
        val failures = current.categoryFailures - validKeys.toSet()
        checkedPreferenceEdit(prefs, listOf("categories", "reasons", "updated_at_map", "ai_confidence", "ai_category_failures"), "Merchant categories could not be saved") {
            putString("categories", JSONObject(categories as Map<*, *>).toString())
            putString("reasons", JSONObject(reasons as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(times as Map<*, *>).toString())
            putString("ai_confidence", JSONObject(confidences.mapValues { it.value.name } as Map<*, *>).toString())
            putString("ai_category_failures", JSONObject(failures as Map<*, *>).toString())
        }
        _snapshot.value = current.copy(
            categories = categories,
            reasons = reasons,
            updatedAt = times,
            aiConfidence = confidences,
            categoryFailures = failures,
        )
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
        return validKeys.size
    }

    @Synchronized
    fun recordCategoryFailures(keys: Collection<String>, message: String) {
        if (keys.isEmpty()) return
        val current = _snapshot.value.categoryFailures.toMutableMap()
        for (k in keys) current[k] = message
        checkedPreferenceEdit(prefs, listOf("ai_category_failures"), "Failures could not be saved") {
            putString("ai_category_failures", JSONObject(current as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(categoryFailures = current)
    }

    @Synchronized
    fun clearCategoryFailures(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = _snapshot.value.categoryFailures.toMutableMap()
        var changed = false
        for (k in keys) {
            if (current.remove(k) != null) changed = true
        }
        if (changed) {
            checkedPreferenceEdit(prefs, listOf("ai_category_failures"), "Failures could not be cleared") {
                putString("ai_category_failures", JSONObject(current as Map<*, *>).toString())
            }
            _snapshot.value = _snapshot.value.copy(categoryFailures = current)
        }
    }

    @Synchronized
    fun setAutoCategorize(enabled: Boolean) {
        prefs.edit { putBoolean("auto_categorize", enabled) }
        _snapshot.value = _snapshot.value.copy(autoCategorize = enabled)
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
        if (config.baseUrl.isNotBlank()) AiAnalysisService.endpoint(config.baseUrl)
        val normalized = config.copy(baseUrl = config.baseUrl.trim(), model = config.model.trim(), apiKey = config.apiKey.trim())
        val now = System.currentTimeMillis()
        val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
        currentUpdated["aiConfig"] = now
        checkedPreferenceEdit(prefs, secret.storageKeys + listOf("ai_base_url", "ai_model", "updated_at_map"), "AI settings could not be saved") {
            secret.stage(normalized.apiKey, this)
            putString("ai_base_url", normalized.baseUrl)
            putString("ai_model", normalized.model)
            putString("updated_at_map", JSONObject(currentUpdated as Map<*, *>).toString())
        }
        _snapshot.value = _snapshot.value.copy(aiConfig = normalized, updatedAt = currentUpdated)
    }

    @Synchronized
    fun commitMonthSummary(month: String, summary: String, fingerprint: String, input: AnalysisSnapshot): Boolean {
        val current = _snapshot.value
        if (current.userNotes != input.userNotes || current.categories != input.categories || current.knowledge != input.knowledge ||
            current.aiConfig != input.aiConfig || current.customCategories != input.customCategories ||
            current.manualCategories != input.manualCategories || current.monthlyNotes[month.substringBefore(':')] != input.monthlyNotes[month.substringBefore(':')]) return false
        val summaries = current.monthlySummaries + (month to summary)
        val fingerprints = current.analysisFingerprints + (month to fingerprint)
        val times = current.updatedAt + ("monthSummary:$month" to System.currentTimeMillis())
        checkedPreferenceEdit(prefs, listOf("monthly_summaries", "analysis_fingerprints", "updated_at_map"), "Analysis could not be saved") {
            putString("monthly_summaries", JSONObject(summaries as Map<*, *>).toString())
            putString("analysis_fingerprints", JSONObject(fingerprints as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(times as Map<*, *>).toString())
        }
        _snapshot.value = current.copy(monthlySummaries = summaries, analysisFingerprints = fingerprints, updatedAt = times)
        clearAnalysisFailure(month)
        return true
    }

    @Synchronized
    fun recordAnalysisFailure(key: String, message: String, fingerprint: String) {
        val now = System.currentTimeMillis()
        val current = _analysisFailures.value.toMutableMap()
        current[key] = AnalysisFailure(message, now, fingerprint)
        persistAnalysisFailures(current)
        _analysisFailures.value = current
    }

    @Synchronized
    fun clearAnalysisFailure(key: String) {
        val current = _analysisFailures.value.toMutableMap()
        if (current.remove(key) != null) {
            persistAnalysisFailures(current)
            _analysisFailures.value = current
        }
    }

    private fun loadAnalysisFailures(): Map<String, AnalysisFailure> {
        val json = prefs.getString("analysis_failures", "{}") ?: "{}"
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val map = mutableMapOf<String, AnalysisFailure>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val entry = obj.optJSONObject(k) ?: continue
            val message = entry.optString("message")
            val at = entry.optLong("at", 0L)
            val fingerprint = entry.optString("fingerprint")
            if (message.isNotBlank() && fingerprint.isNotBlank()) {
                map[k] = AnalysisFailure(message, at, fingerprint)
            }
        }
        return map
    }

    private fun persistAnalysisFailures(failures: Map<String, AnalysisFailure>) {
        val obj = JSONObject()
        for ((k, v) in failures) {
            val entry = JSONObject()
            entry.put("message", v.message)
            entry.put("at", v.at)
            entry.put("fingerprint", v.fingerprint)
            obj.put(k, entry)
        }
        prefs.edit { putString("analysis_failures", obj.toString()) }
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
    fun addCustomCategory(category: String) {
        val trimmed = category.trim()
        require(trimmed.isNotBlank()) { "Enter a category name" }
        require(_snapshot.value.customCategories.none { it.equals(trimmed, ignoreCase = true) }) { "Category already exists" }
        run {
            val now = System.currentTimeMillis()
            val currentUpdated = _snapshot.value.updatedAt.toMutableMap()
            val currentDeleted = _snapshot.value.deletedCategories.toMutableMap()
            currentUpdated["customCat:$trimmed"] = now
            currentDeleted.remove(trimmed)
            val newCats = _snapshot.value.customCategories + trimmed
            checkedPreferenceEdit(prefs, listOf("custom_categories", "updated_at_map", "deleted_categories_map"), "Category could not be added") {
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
    fun applyMergedSnapshot(merged: AnalysisSnapshot) {
        // Classification request fingerprints are a local cache, not backup content.
        val merged = merged.copy(
            aiConfig = _snapshot.value.aiConfig,
            aiCategoryFingerprints = _snapshot.value.aiCategoryFingerprints,
            aiConfidence = _snapshot.value.aiConfidence,
            aiMerchantConsistent = _snapshot.value.aiMerchantConsistent,
            categoryFailures = _snapshot.value.categoryFailures,
            autoCategorize = _snapshot.value.autoCategorize,
        )
        prefs.edit {
            putString("user_notes", JSONObject(merged.userNotes as Map<*, *>).toString())
            putString("categories", JSONObject(merged.categories as Map<*, *>).toString())
            putString("reasons", JSONObject(merged.reasons as Map<*, *>).toString())
            putString("knowledge", merged.knowledge)
            putString("ai_base_url", merged.aiConfig.baseUrl)
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
    fun mergeImportedAnalysis(meta: org.json.JSONObject?, labels: List<net.wastu.artha.data.export.ImportedLabels>) {
        var next = _snapshot.value
        meta?.optJSONObject("analysis")?.let { analysis ->
            val importedNotes = jsonObjectToMap(analysis.optJSONObject("userNotes"))
            val importedCategories = jsonObjectToMap(analysis.optJSONObject("categories"))
            val importedReasons = jsonObjectToMap(analysis.optJSONObject("reasons"))
            val importedMonthlyNotes = jsonObjectToMap(analysis.optJSONObject("monthlyNotes"))
            val importedSummaries = jsonObjectToMap(analysis.optJSONObject("monthlySummaries"))
            val importedIcons = jsonObjectToMap(analysis.optJSONObject("categoryIcons"))
            val importedManual = jsonArrayToList(analysis.optJSONArray("manualCategories")).toSet()
            val importedCustom = jsonArrayToList(analysis.optJSONArray("customCategories"))
            val knowledge = analysis.optString("knowledge")
            next = next.copy(
                knowledge = knowledge.ifBlank { next.knowledge },
                customCategories = (importedCustom + next.customCategories).distinctBy { it.lowercase() },
                categoryIcons = next.categoryIcons + importedIcons,
                monthlyNotes = next.monthlyNotes + importedMonthlyNotes,
                monthlySummaries = next.monthlySummaries + importedSummaries,
                userNotes = next.userNotes + importedNotes,
                categories = next.categories + importedCategories,
                reasons = next.reasons + importedReasons,
                manualCategories = next.manualCategories + importedManual,
            )
        }
        val userNotes = next.userNotes.toMutableMap()
        val categories = next.categories.toMutableMap()
        val reasons = next.reasons.toMutableMap()
        val manual = next.manualCategories.toMutableSet()
        val updatedAt = next.updatedAt.toMutableMap()
        val now = System.currentTimeMillis()
        for (label in labels) {
            label.userNote?.let {
                userNotes[label.key] = it
                updatedAt["note:${label.key}"] = now
            }
            label.category?.let { category ->
                categories[label.key] = category
                reasons[label.key] = label.reason.orEmpty()
                updatedAt["cat:${label.key}"] = now
                if (label.classificationIsManual) manual += label.key
            }
        }
        next = next.copy(userNotes = userNotes, categories = categories, reasons = reasons, manualCategories = manual, updatedAt = updatedAt)
        prefs.edit {
            putString("knowledge", next.knowledge)
            putString("custom_categories", JSONArray(next.customCategories).toString())
            putString("custom_category_icons", JSONObject(next.categoryIcons as Map<*, *>).toString())
            putString("monthly_notes", JSONObject(next.monthlyNotes as Map<*, *>).toString())
            putString("monthly_summaries", JSONObject(next.monthlySummaries as Map<*, *>).toString())
            putString("user_notes", JSONObject(next.userNotes as Map<*, *>).toString())
            putString("categories", JSONObject(next.categories as Map<*, *>).toString())
            putString("reasons", JSONObject(next.reasons as Map<*, *>).toString())
            putString("manual_categories", JSONArray(next.manualCategories.toList()).toString())
            putString("updated_at_map", JSONObject(next.updatedAt.mapValues { it.value.toString() } as Map<*, *>).toString())
        }
        _snapshot.value = next
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
    }

    @Synchronized
    fun migrateMetadata(transactions: List<net.wastu.artha.core.model.UnifiedTransaction>) {
        val migrated = TransactionMetadata.migrate(_snapshot.value, transactions)
        if (migrated != _snapshot.value) applyMergedSnapshot(migrated)
        migrateAiCategoryFingerprints(transactions)
    }

    @Synchronized
    fun migrateAiCategoryFingerprints(rows: List<net.wastu.artha.core.model.UnifiedTransaction>): Int {
        val current = _snapshot.value
        val updates = mutableMapOf<String, String>()
        for (tx in rows) {
            val key = tx.key
            val hasLabel = current.reasons[key]?.startsWith(AI_REASON_PREFIX) == true ||
                current.reasons[key]?.startsWith(MERCHANT_REASON_PREFIX) == true
            if (hasLabel) {
                val newFp = AiCategorization.fingerprint(tx, current)
                if (current.aiCategoryFingerprints[key] != newFp) {
                    updates[key] = newFp
                }
            }
        }
        if (updates.isEmpty()) return 0
        val newFingerprints = current.aiCategoryFingerprints + updates
        checkedPreferenceEdit(prefs, listOf("ai_category_fingerprints"), "Fingerprints could not be migrated") {
            putString("ai_category_fingerprints", JSONObject(newFingerprints as Map<*, *>).toString())
        }
        _snapshot.value = current.copy(aiCategoryFingerprints = newFingerprints)
        return updates.size
    }

    private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.optString(key)
        }
        return map
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
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

    private fun parseConfidenceMap(json: String): Map<String, CategoryConfidence> {
        val map = mutableMapOf<String, CategoryConfidence>()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val confStr = obj.optString(k)
            map[k] = when (confStr.uppercase()) {
                "HIGH" -> CategoryConfidence.HIGH
                "LOW" -> CategoryConfidence.LOW
                else -> CategoryConfidence.MEDIUM
            }
        }
        return map
    }
}
