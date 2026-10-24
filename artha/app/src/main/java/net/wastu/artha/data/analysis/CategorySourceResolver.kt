package net.wastu.artha.data.analysis

import net.wastu.artha.core.model.UnifiedTransaction

private const val REASON_PREFIX = "AI suggestion: "

fun aiCategoryReason(snapshot: AnalysisSnapshot, key: String): String? {
    if (key in snapshot.manualCategories) return null
    val reason = snapshot.reasons[key] ?: return null
    return if (reason.startsWith(REASON_PREFIX)) reason.removePrefix(REASON_PREFIX) else null
}

fun categorySource(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): CategorySource {
    if (tx.classificationIsManual || tx.key in snapshot.manualCategories) return CategorySource.MANUAL
    val reason = snapshot.reasons[tx.key] ?: tx.aiReasoning
    if (reason?.startsWith(REASON_PREFIX) == true) return CategorySource.AI
    val aiConfigured = snapshot.aiConfig.baseUrl.isNotBlank() &&
        snapshot.aiConfig.model.isNotBlank() &&
        snapshot.aiConfig.apiKey.isNotBlank()
    if (BudgetAnalyzer.isSpending(tx) && snapshot.analysisEnabled && aiConfigured) return CategorySource.PENDING
    return CategorySource.NONE
}
