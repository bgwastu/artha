package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction

interface ProviderAdapter {
    val provider: Provider
    val coverageHint: String? get() = null
    val accountIdentifier: String? get() = null
    suspend fun ingest(): List<UnifiedTransaction>
    suspend fun ingestInteractive(): List<UnifiedTransaction> = ingest()
}
