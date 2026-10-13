package net.wastu.artha.data.ingestion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import net.wastu.artha.data.providers.AccountStatus
import net.wastu.artha.data.providers.ProviderPreferences

interface ProviderAdapter {
    val provider: Provider
    val coverageHint: String? get() = null
    val accountIdentifier: String? get() = null
    suspend fun ingest(): List<UnifiedTransaction>
}

class IngestionCoordinator(
    private val rootAccess: RootAccess,
    private val ledger: () -> LedgerRepository,
    adapters: List<ProviderAdapter> = emptyList(),
    private val beforeBatch: () -> Unit = {},
    private val preferences: ProviderPreferences? = null,
) {
    private val mutex = Mutex()
    private val adaptersByProvider = adapters.associateBy { it.provider }
    private val _syncingProvider = MutableStateFlow<Provider?>(null)
    val syncingProvider = _syncingProvider.asStateFlow()

    init { require(adaptersByProvider.size == adapters.size) { "Only one adapter per Provider is allowed." } }

    suspend fun ingestAll(): Map<Provider, String> = mutex.withLock {
        check(rootAccess.verify()) { "Root access unavailable" }
        beforeBatch()
        try { buildMap {
            for (provider in adaptersByProvider.keys) {
                if (preferences != null && provider !in preferences.importEnabled.value) continue
                _syncingProvider.value = provider
                put(provider, importAndRecord(provider))
            }
        } } finally { _syncingProvider.value = null; beforeBatch() }
    }

    suspend fun syncProvider(provider: Provider): String = mutex.withLock {
        check(rootAccess.verify()) { "Root access unavailable" }
        beforeBatch()
        try { _syncingProvider.value = provider; importAndRecord(provider) }
        finally { _syncingProvider.value = null; beforeBatch() }
    }

    suspend fun ingest(provider: Provider): AppendResult {
        check(rootAccess.verify()) { "The Root Gate must be granted before ingestion." }
        return readAndAppend(provider).first
    }

    private suspend fun readAndAppend(provider: Provider): Pair<AppendResult, String?> {
        val adapter = requireNotNull(adaptersByProvider[provider]) { "Provider ingestion is not implemented yet." }
        var warning = adapter.coverageHint
        val transactions = try { adapter.ingest() } catch (partial: PartialImportException) {
            if (partial.transactions.isEmpty()) throw partial
            warning = partial.message; partial.transactions
        }
        require(transactions.all { it.provider == provider }) { "Adapter returned another Provider's transaction." }
        return ledger().append(transactions) to warning
    }

    private suspend fun importAndRecord(provider: Provider): String = try {
        val (result, warning) = readAndAppend(provider)
        val summary = "${result.inserted} added, ${result.duplicates} already saved"
        preferences?.recordSyncSuccess(provider, summary, accountIdentifier = adaptersByProvider[provider]?.accountIdentifier, coverageHint = warning)
        if (warning == null) summary else "$summary · $warning"
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: "Unable to read ${provider.displayName} data"
        val lower = message.lowercase()
        val status = (e as? ProviderReadException)?.status ?: when {
            "login" in lower || "cookie" in lower || "session" in lower -> AccountStatus.NEEDS_LOGIN
            "not installed" in lower -> AccountStatus.APP_NOT_INSTALLED
            else -> AccountStatus.ERROR
        }
        preferences?.recordSyncError(provider, message, status)
        "Unavailable: $message"
    }
}
