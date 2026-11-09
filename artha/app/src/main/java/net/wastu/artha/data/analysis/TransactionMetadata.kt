package net.wastu.artha.data.analysis

object TransactionMetadata {
    /** Old rule output stays stored for recovery, but is no longer presented as a category. */
    fun category(snapshot: AnalysisSnapshot, key: String): String? = snapshot.categories[key]?.takeUnless {
        key !in snapshot.manualCategories && snapshot.reasons[key]?.startsWith("Local provider and merchant rule") == true
    }
}
