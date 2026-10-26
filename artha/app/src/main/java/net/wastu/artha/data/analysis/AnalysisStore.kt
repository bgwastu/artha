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
    val analysisEnabled: Boolean = false,
    val autoCategorize: Boolean = true,
    /** What the AI knows about each payment. Local cache, not backup content. */
    val aiRecords: Map<String, AiRecord> = emptyMap(),
) {
    val aiCategoryFingerprints: Map<String, String> by lazy { aiRecords.mapNotNull { (k, r) -> r.fingerprint?.let { k to it } }.toMap() }
    val aiConfidence: Map<String, CategoryConfidence> by lazy { aiRecords.mapNotNull { (k, r) -> r.confidence?.let { k to CategoryConfidence.of(it) } }.toMap() }
    val categoryFailures: Map<String, String> by lazy { aiRecords.mapNotNull { (k, r) -> r.failure?.let { k to it } }.toMap() }
    val aiMerchantConsistent: Set<String> by lazy { aiRecords.filterValues { it.merchantConsistent }.keys }
}

/** One payment's AI state: the evidence hash it was labeled from, how sure the model was, or why it gave up. */
data class AiRecord(
    val fingerprint: String? = null,
    /** 0 to 100. */
    val confidence: Int? = null,
    val failure: String? = null,
    /** The model said this merchant always means this category, so later payments can copy it. */
    val merchantConsistent: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        fingerprint?.let { put("f", it) }
        confidence?.let { put("c", it) }
        failure?.let { put("e", it) }
        if (merchantConsistent) put("m", true)
    }
    val isEmpty: Boolean get() = this == AiRecord()

    companion object {
        fun fromJson(json: JSONObject) = AiRecord(
            fingerprint = json.optString("f").ifBlank { null },
            confidence = if (json.has("c")) json.optInt("c").coerceIn(0, 100) else null,
            failure = json.optString("e").ifBlank { null },
            merchantConsistent = json.optBoolean("m", false),
        )
    }
}

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
        const val AI_FINGERPRINT_VERSION = 1
        private const val PREF_AI_FINGERPRINT_VERSION = "ai_fingerprint_version"
        private const val AI_RECORDS = "ai_records"
        private val LEGACY_AI_KEYS = listOf("ai_category_fingerprints", "ai_confidence", "ai_category_failures", "ai_merchant_consistent")
        /** Older builds saved only a level; map it to a percent inside that level's band. */
        internal fun legacyConfidencePercent(level: String): Int = when (level.uppercase()) {
            "HIGH" -> 95
            "LOW" -> 50
            else -> 77
        }
    }
    private fun getFingerprintVersion(): Int =
        prefs.getString(PREF_AI_FINGERPRINT_VERSION, null)?.toIntOrNull() ?: 0
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("artha_analysis_store", Context.MODE_PRIVATE)

    private val secret = StoredSecret(prefs, "artha_ai_config_key", "ai_api_key")
    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<AnalysisSnapshot> = _snapshot.asStateFlow()
    init {
        // Monthly AI analysis was removed; its run cache is not user data.
        if (prefs.contains("analysis_failures")) prefs.edit { remove("analysis_failures") }
    }

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
        val autoCategorize = prefs.getBoolean("auto_categorize", true)

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
            analysisEnabled = prefs.getBoolean("analysis_enabled", true),
            autoCategorize = autoCategorize,
            aiRecords = loadAiRecords(),
        )
    }

    /** Reads ai_records, folding the four maps older builds kept into it once. */
    private fun loadAiRecords(): Map<String, AiRecord> {
        prefs.getString(AI_RECORDS, null)?.let { json ->
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            return obj.keys().asSequence().mapNotNull { key -> obj.optJSONObject(key)?.let { key to AiRecord.fromJson(it) } }.toMap()
        }
        if (LEGACY_AI_KEYS.none(prefs::contains)) return emptyMap()
        val fingerprints = parseMap(prefs.getString("ai_category_fingerprints", "{}") ?: "{}")
        val confidence = parseMap(prefs.getString("ai_confidence", "{}") ?: "{}")
        val failures = parseMap(prefs.getString("ai_category_failures", "{}") ?: "{}")
        val consistent = parseList(prefs.getString("ai_merchant_consistent", "[]") ?: "[]").toSet()
        val records = (fingerprints.keys + confidence.keys + failures.keys + consistent).associateWith { key ->
            AiRecord(
                fingerprint = fingerprints[key],
                confidence = confidence[key]?.let(::legacyConfidencePercent),
                failure = failures[key],
                merchantConsistent = key in consistent,
            )
        }
        // A failed write leaves the old maps in place, so the fold simply runs again next launch.
        runCatching {
            checkedPreferenceEdit(prefs, LEGACY_AI_KEYS + AI_RECORDS, "Could not migrate AI records") {
                putString(AI_RECORDS, aiRecordsJson(records))
                for (old in LEGACY_AI_KEYS) remove(old)
            }
        }
        return records
    }

    private fun aiRecordsJson(records: Map<String, AiRecord>): String =
        JSONObject().apply { for ((key, record) in records) if (!record.isEmpty) put(key, record.toJson()) }.toString()

    /** Persists [records] with any other prefs [extra] writes, all or nothing. */
    private fun saveAiRecords(records: Map<String, AiRecord>, message: String, keys: List<String> = emptyList(), extra: SharedPreferences.Editor.() -> Unit = {}) {
        val clean = records.filterValues { !it.isEmpty }
        checkedPreferenceEdit(prefs, keys + AI_RECORDS, message) {
            extra()
            putString(AI_RECORDS, aiRecordsJson(clean))
        }
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
        if (!manual && key in _snapshot.value.manualCategories) return
        val records = _snapshot.value.aiRecords.toMutableMap()
        // A user pick replaces whatever the AI knew; an AI or merchant label keeps its record but clears the failure.
        if (manual) records.remove(key) else records[key]?.let { records[key] = it.copy(failure = null) }
        val manualKeys = if (manual) _snapshot.value.manualCategories + key else _snapshot.value.manualCategories
        currentCategories[key] = category
        currentReasons[key] = reason
        currentUpdated["cat:$key"] = now
        saveAiRecords(records, "Category could not be saved", listOf("manual_categories", "categories", "reasons", "updated_at_map")) {
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
            aiRecords = records,
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

    fun commitCategorySuggestions(candidates: List<CategoryCandidate>, suggestions: Map<String, CategorySuggestion>, input: AnalysisSnapshot): Int =
        commitCategorySuggestionKeys(candidates, suggestions, input).size

    /** Saves the suggestions whose input is unchanged since [input]; returns the keys actually saved. */
    @Synchronized
    fun commitCategorySuggestionKeys(candidates: List<CategoryCandidate>, suggestions: Map<String, CategorySuggestion>, input: AnalysisSnapshot): Set<String> {
        val current = _snapshot.value
        if (!sameRequestSettings(current, input)) return emptySet()
        val valid = candidates.filter { candidate ->
            val key = candidate.transaction.key
            BudgetAnalyzer.isSpending(candidate.transaction) &&
                AiCategorization.fingerprint(candidate.transaction, current) == candidate.fingerprint &&
                !candidate.transaction.classificationIsManual && untouchedSince(current, input, key) &&
                suggestions[key]?.category in AiCategorization.categories(current)
        }
        if (valid.isEmpty()) return emptySet()
        saveLabels(current, valid.associate { it.transaction.key to it.fingerprint }, suggestions, fromModel = true, "AI categories could not be saved")
        return valid.mapTo(mutableSetOf()) { it.transaction.key }
    }

    @Synchronized
    fun commitMerchantSuggestions(candidates: List<CategoryCandidate>, suggestions: Map<String, CategorySuggestion>, input: AnalysisSnapshot): Int {
        val current = _snapshot.value
        if (!sameRequestSettings(current, input)) return 0
        val valid = candidates.filter { candidate ->
            val key = candidate.transaction.key
            BudgetAnalyzer.isSpending(candidate.transaction) &&
                !candidate.transaction.classificationIsManual && untouchedSince(current, input, key) &&
                suggestions[key]?.category in AiCategorization.categories(current)
        }
        if (valid.isEmpty()) return 0
        saveLabels(current, valid.associate { it.transaction.key to it.fingerprint }, suggestions, fromModel = false, "Merchant categories could not be saved")
        return valid.size
    }

    @Synchronized
    fun commitMerchantSuggestions(
        suggestions: Map<String, CategorySuggestion>,
        input: AnalysisSnapshot,
        rows: List<net.wastu.artha.core.model.UnifiedTransaction> = emptyList(),
    ): Int {
        val current = _snapshot.value
        if (!sameRequestSettings(current, input)) return 0
        val validKeys = suggestions.keys.filter { key ->
            untouchedSince(current, input, key) && suggestions[key]?.category in AiCategorization.categories(current)
        }
        if (validKeys.isEmpty()) return 0
        val rowsByKey = rows.associateBy { it.key }
        val fingerprints = validKeys.associateWith { key -> rowsByKey[key]?.let { AiCategorization.fingerprint(it, current) } }
        saveLabels(current, fingerprints, suggestions, fromModel = false, "Merchant categories could not be saved")
        return validKeys.size
    }

    private fun sameRequestSettings(current: AnalysisSnapshot, input: AnalysisSnapshot): Boolean =
        current.aiConfig == input.aiConfig && current.knowledge == input.knowledge && current.customCategories == input.customCategories

    /** The user did not pick a category or edit the note while the request ran. */
    private fun untouchedSince(current: AnalysisSnapshot, input: AnalysisSnapshot, key: String): Boolean =
        key !in current.manualCategories && current.userNotes[key] == input.userNotes[key] &&
            current.updatedAt["cat:$key"] == input.updatedAt["cat:$key"]

    /** Writes AI or merchant labels for [fingerprints]' keys; a null fingerprint keeps the saved one. */
    private fun saveLabels(
        current: AnalysisSnapshot,
        fingerprints: Map<String, String?>,
        suggestions: Map<String, CategorySuggestion>,
        fromModel: Boolean,
        message: String,
    ) {
        val now = System.currentTimeMillis()
        val keys = fingerprints.keys
        val categories = current.categories + keys.associateWith { suggestions.getValue(it).category }
        val reasons = current.reasons + keys.associateWith { suggestions.getValue(it).reason }
        val times = current.updatedAt + keys.associate { "cat:$it" to now }
        val records = current.aiRecords + keys.associateWith { key ->
            val old = current.aiRecords[key] ?: AiRecord()
            val suggestion = suggestions.getValue(key)
            AiRecord(
                fingerprint = fingerprints[key] ?: old.fingerprint,
                confidence = suggestion.percent,
                merchantConsistent = if (fromModel) suggestion.merchantConsistent else old.merchantConsistent,
            )
        }
        saveAiRecords(records, message, listOf("categories", "reasons", "updated_at_map")) {
            putString("categories", JSONObject(categories as Map<*, *>).toString())
            putString("reasons", JSONObject(reasons as Map<*, *>).toString())
            putString("updated_at_map", JSONObject(times as Map<*, *>).toString())
        }
        _snapshot.value = current.copy(categories = categories, reasons = reasons, updatedAt = times, aiRecords = records)
        runCatching { net.wastu.artha.ui.widget.AnalysisWidgetProvider.triggerUpdate(appContext) }
    }

    @Synchronized
    fun recordCategoryFailures(keys: Collection<String>, message: String) {
        if (keys.isEmpty()) return
        val records = _snapshot.value.aiRecords.toMutableMap()
        for (k in keys) records[k] = (records[k] ?: AiRecord()).copy(failure = message)
        saveAiRecords(records, "Failures could not be saved")
        _snapshot.value = _snapshot.value.copy(aiRecords = records)
    }

    @Synchronized
    fun clearCategoryFailures(keys: Collection<String>) {
        val current = _snapshot.value.aiRecords
        val cleared = keys.filter { current[it]?.failure != null }
        if (cleared.isEmpty()) return
        val records = current.toMutableMap()
        for (k in cleared) records[k] = records.getValue(k).copy(failure = null)
        saveAiRecords(records, "Failures could not be cleared")
        _snapshot.value = _snapshot.value.copy(aiRecords = records)
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
        if (config.baseUrl.isNotBlank()) AiClient.endpoint(config.baseUrl)
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
            aiRecords = _snapshot.value.aiRecords,
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
    }

    /** One-shot repair against reconciled rows: earlier builds re-hashed raw rows, leaving labels looking stale. */
    @Synchronized
    fun migrateAiCategoryFingerprints(rows: List<net.wastu.artha.core.model.UnifiedTransaction>): Int {
        if (getFingerprintVersion() >= AI_FINGERPRINT_VERSION) return 0
        if (rows.isEmpty()) return 0
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
        val records = current.aiRecords + updates.mapValues { (key, fp) -> (current.aiRecords[key] ?: AiRecord()).copy(fingerprint = fp) }
        saveAiRecords(records, "Fingerprints could not be migrated", listOf(PREF_AI_FINGERPRINT_VERSION)) {
            putString(PREF_AI_FINGERPRINT_VERSION, AI_FINGERPRINT_VERSION.toString())
        }
        if (updates.isNotEmpty()) {
            _snapshot.value = current.copy(aiRecords = records)
        }
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
}
