package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderPreferences
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

/** Implement extraction, decryption and normalization within each Provider's adapter. */
interface ProviderAdapter {
    val provider: Provider
    suspend fun ingest(): List<UnifiedTransaction>
}

/** No adapter is registered until it actually supports its Provider. */
class IngestionCoordinator(
    private val rootAccess: RootAccess,
    private val ledger: () -> LedgerRepository,
    adapters: List<ProviderAdapter> = emptyList(),
    private val beforeBatch: () -> Unit = {},
    private val preferences: ProviderPreferences? = null,
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val adaptersByProvider = adapters.associateBy { it.provider }
    private val _syncingProvider = kotlinx.coroutines.flow.MutableStateFlow<Provider?>(null)
    val syncingProvider = _syncingProvider.asStateFlow()

    init {
        require(adaptersByProvider.size == adapters.size) { "Only one adapter per Provider is allowed." }
    }

    suspend fun ingestAll(): Map<Provider, String> = mutex.withLock {
        check(rootAccess.verify()) { "Root access unavailable" }
        beforeBatch()
        try {
            buildMap {
                for (provider in adaptersByProvider.keys) {
                    try {
                        _syncingProvider.value = provider
                        val result = ingest(provider)
                        val summary = "${result.inserted} added, ${result.duplicates} synced"
                        preferences?.recordSyncSuccess(provider, summary)
                        put(provider, summary)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        val errMsg = e.message?.takeIf { it.isNotBlank() } ?: "Unable to read ${provider.displayName} data"
                        val status = classifyAccountStatus(errMsg)
                        preferences?.recordSyncError(provider, errMsg, status)
                        put(provider, "Unavailable")
                    }
                }
            }
        } finally {
            _syncingProvider.value = null
            beforeBatch()
        }
    }

    suspend fun syncProvider(provider: Provider): String = mutex.withLock {
        check(rootAccess.verify()) { "Root access unavailable" }
        beforeBatch()
        try {
            _syncingProvider.value = provider
            val result = ingest(provider)
            val summary = "${result.inserted} added, ${result.duplicates} synced"
            preferences?.recordSyncSuccess(provider, summary)
            summary
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val errMsg = e.message?.takeIf { it.isNotBlank() } ?: "Unable to read ${provider.displayName} data"
            val status = classifyAccountStatus(errMsg)
            preferences?.recordSyncError(provider, errMsg, status)
            "Unavailable: $errMsg"
        } finally {
            _syncingProvider.value = null
            beforeBatch()
        }
    }

    suspend fun ingest(provider: Provider): AppendResult {
        check(rootAccess.verify()) { "The Root Gate must be granted before ingestion." }
        val adapter = requireNotNull(adaptersByProvider[provider]) { "Provider ingestion is not implemented yet." }
        val transactions = adapter.ingest()
        require(transactions.all { it.provider == provider }) { "Adapter returned another Provider's transaction." }
        return ledger().append(transactions)
    }

    private fun classifyAccountStatus(errorMessage: String): AccountStatus {
        val lower = errorMessage.lowercase()
        return when {
            "cookie" in lower || "login" in lower || "session" in lower || "expired" in lower || "401" in lower || "403" in lower -> AccountStatus.NEEDS_LOGIN
            "no such file" in lower || "not installed" in lower || "missing" in lower -> AccountStatus.APP_NOT_INSTALLED
            else -> AccountStatus.ERROR
        }
    }
}
