package net.wastu.artha.data.analysis

/** Number of suggestions [AnalysisStore.commitCategorySuggestionKeys] actually saved. */
internal fun AnalysisStore.commitCategorySuggestions(
    candidates: List<CategoryCandidate>,
    suggestions: Map<String, CategorySuggestion>,
    input: AnalysisSnapshot,
): Int = commitCategorySuggestionKeys(candidates, suggestions, input).size
