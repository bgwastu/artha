package net.wastu.artha.data.ingestion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.updateAndGet
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
import net.wastu.artha.data.providers.ProviderImportWindow

class IngestionCoordinator(
    private val rootAccess: RootAccess,
    private val ledger: () -> LedgerRepository,
    adapters: List<ProviderAdapter> = emptyList(),
    private val beforeBatch: () -> Unit = {},
    private val preferences: ProviderPreferences? = null,
) {
    private val mutex = Mutex()
    private val adaptersByProvider = adapters.associateBy { it.provider }
    private val active = MutableStateFlow<List<Provider>>(emptyList())
    private val _syncingProvider = MutableStateFlow<Provider?>(null)
    /** The provider the UI shows as importing; with several in flight, the one that started first. */
    val syncingProvider = _syncingProvider.asStateFlow()

    private fun markActive(provider: Provider, running: Boolean) {
        _syncingProvider.value = active.updateAndGet { if (running) it + provider else it - provider }.firstOrNull()
    }

    init { require(adaptersByProvider.size == adapters.size) { "Only one adapter per Provider is allowed." } }

    /** Imports the selected providers side by side; each one's outcome is recorded independently. */
    suspend fun ingestAll(force: Boolean = true): Map<Provider, String> = mutex.withLock {
        val selected = adaptersByProvider.keys.filter { provider ->
            preferences == null || (provider in preferences.importEnabled.value && (force || preferences.isStale(provider)))
        }
        if (selected.isEmpty()) return@withLock emptyMap()
        check(rootAccess.verify()) { "Root access unavailable" }
        beforeBatch()
        try {
            coroutineScope {
                selected.map { provider -> async { provider to importTracked(provider) } }.awaitAll().toMap()
            }
        } finally { beforeBatch() }
    }

    suspend fun syncProvider(provider: Provider): String = mutex.withLock {
        check(rootAccess.verify()) { "Root access unavailable" }
        beforeBatch()
        try { importTracked(provider, interactive = true) }
        finally { beforeBatch() }
    }

    private suspend fun importTracked(provider: Provider, interactive: Boolean = false): String {
        markActive(provider, true)
        try { return importAndRecord(provider, interactive) } finally { markActive(provider, false) }
    }

    suspend fun ingest(provider: Provider): AppendResult {
        check(rootAccess.verify()) { "The Root Gate must be granted before ingestion." }
        return readAndAppend(provider).result
    }

    private data class ImportResult(val result: AppendResult, val warning: String?, val window: ProviderImportWindow)

    private suspend fun readAndAppend(provider: Provider, interactive: Boolean = false): ImportResult {
        val adapter = requireNotNull(adaptersByProvider[provider]) { "Provider ingestion is not implemented yet." }
        var warning: String? = null
        val transactions = try {
            if (interactive) adapter.ingestInteractive() else adapter.ingest()
        } catch (partial: PartialImportException) {
            if (partial.transactions.isEmpty()) throw partial
            warning = partial.message?.takeIf { it.isNotBlank() } ?: "Some history could not be imported"; partial.transactions
        }
        require(transactions.all { it.provider == provider }) { "Adapter returned another Provider's transaction." }
        val payments = transactions.filter { it.isConfirmed }.distinctBy { it.key }
        val window = ProviderImportWindow(payments.size,
            payments.minOfOrNull { it.occurredAt.toEpochMilli() }, payments.maxOfOrNull { it.occurredAt.toEpochMilli() })
        return ImportResult(ledger().appendProviderImport(transactions), warning, window)
    }

    private suspend fun importAndRecord(provider: Provider, interactive: Boolean = false): String = try {
        val (result, warning, window) = readAndAppend(provider, interactive)
        val summary = "${result.inserted} added, ${result.duplicates} already saved" +
            if (result.evidenceUpdates > 0) ", ${result.evidenceUpdates} details updated" else ""
        preferences?.recordSyncSuccess(provider, summary,
            accountIdentifier = adaptersByProvider[provider]?.accountIdentifier,
            coverageHint = adaptersByProvider[provider]?.coverageHint, partialReason = warning, importWindow = window)
        if (warning == null) summary else "$summary. $warning"
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: "Unable to read ${provider.displayName} data"
        val status = statusFor(e)
        preferences?.recordSyncError(provider, message, status,
            coverageHint = adaptersByProvider[provider]?.coverageHint)
        "Unavailable: $message"
    }

    /** The account state a read failure points to. */
    private fun statusFor(error: Exception): net.wastu.artha.data.providers.AccountStatus {
        (error as? ProviderReadException)?.let { return it.status }
        val lower = error.message.orEmpty().lowercase()
        return when {
            "login" in lower || "cookie" in lower || "session" in lower -> net.wastu.artha.data.providers.AccountStatus.NEEDS_LOGIN
            "not installed" in lower -> net.wastu.artha.data.providers.AccountStatus.APP_NOT_INSTALLED
            else -> net.wastu.artha.data.providers.AccountStatus.ERROR
        }
    }
}
