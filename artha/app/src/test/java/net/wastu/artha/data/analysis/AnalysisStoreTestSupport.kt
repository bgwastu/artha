package net.wastu.artha.data.analysis

/** Number of suggestions [AnalysisStore.commitCategorySuggestionKeys] actually saved. */
internal fun AnalysisStore.commitCategorySuggestions(
    candidates: List<CategoryCandidate>,
    suggestions: Map<String, CategorySuggestion>,
    input: AnalysisSnapshot,
): Int = commitCategorySuggestionKeys(candidates, suggestions, input).size

internal val AnalysisSnapshot.aiCategoryFingerprints: Map<String, String>
    get() = aiRecords.mapNotNull { (key, record) -> record.fingerprint?.let { key to it } }.toMap()

internal val AnalysisSnapshot.aiConfidence: Map<String, CategoryConfidence>
    get() = aiRecords.mapNotNull { (key, record) -> record.confidence?.let { key to CategoryConfidence.of(it) } }.toMap()
