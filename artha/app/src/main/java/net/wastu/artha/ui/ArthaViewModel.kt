package net.wastu.artha.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.*
import net.wastu.artha.data.analysis.*
import android.content.Context
import android.net.Uri
import net.wastu.artha.data.export.LedgerExportStorage
import net.wastu.artha.data.export.LedgerJsonlExport
import net.wastu.artha.data.export.LedgerJsonlImport
import net.wastu.artha.data.ledger.LedgerRepository
import net.wastu.artha.data.ledger.ProviderObservations
import net.wastu.artha.data.providers.ProviderPreferences
sealed interface LedgerState {
    data object Loading : LedgerState
    data class Ready(val transactions: List<UnifiedTransaction>) : LedgerState
    data object Failed : LedgerState
}

data class ImportFeedback(val sequence: Long, val provider: Provider?, val message: String, val failed: Boolean = false)

/** Saved payment date range for one provider, measured on the resolved ledger rows the UI shows. */
data class SourceCoverage(val earliest: java.time.Instant?, val latest: java.time.Instant?) {
    init { require(earliest != null || latest != null) }
}

/** Per-provider saved data coverage: pure so both the ViewModel and UI tests can use it. */
internal fun sourceCoverage(rows: List<UnifiedTransaction>): Map<Provider, SourceCoverage> = rows
    .groupBy { it.provider }
    .mapValues { (_, providerRows) -> SourceCoverage(providerRows.minOf { it.occurredAt }, providerRows.maxOf { it.occurredAt }) }

@OptIn(kotlinx.coroutines.FlowPreview::class)
class ArthaViewModel(
    private val rootAccess: RootAccess,
    private val ledger: () -> LedgerRepository,
    private val clock: Clock = AccountingTime.clock,
    private val ingest: (suspend () -> Map<Provider, String>)? = null,
    private val ingestStale: (suspend () -> Map<Provider, String>)? = null,
    private val syncSingle: (suspend (Provider) -> String)? = null,
    private val preferences: ProviderPreferences? = null,
    val syncingProvider: StateFlow<Provider?> = MutableStateFlow<Provider?>(null).asStateFlow(),
    val analysisStore: AnalysisStore? = null,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val spendingState: StateFlow<SpendingState>? = null,
    private val retrySpending: (() -> Unit)? = null,
    private val gojekFlashRefresh: (() -> Unit)? = null,
    /** Test seam; null sends batches to the configured model with the saved rows as userExamples. */
    private val categorize: (suspend (AiConfig, AnalysisSnapshot, List<CategoryCandidate>) -> Map<String, CategorySuggestion>)? = null,
    private val fromMemoryAction: (List<UnifiedTransaction>, AnalysisSnapshot) -> Map<String, CategorySuggestion> = AiCategorization::fromMemory,
    private val scopeKeysAction: (RecategorizeScope, List<UnifiedTransaction>, AnalysisSnapshot) -> Set<String> = AiCategorization::scopeKeys,
    private val needsReviewAction: (UnifiedTransaction, AnalysisSnapshot) -> Boolean = ::needsReview,
    private val merchantKeyProvider: (UnifiedTransaction) -> String? = ::merchantKey,
) : ViewModel() {
    private val _rootGate = MutableStateFlow(RootGateState.LOCKED)
    val rootGate = _rootGate.asStateFlow()
    private val _ledgerState = MutableStateFlow<LedgerState>(LedgerState.Loading)
    val ledgerState = _ledgerState.asStateFlow()
    private val _ledgerLoadFailed = MutableStateFlow(false)
    val ledgerLoadFailed = _ledgerLoadFailed.asStateFlow()
    @Volatile private var hasSavedProjection = false
    private val _providerCounts = MutableStateFlow<Map<Provider, Int>>(emptyMap())
    val providerCounts = _providerCounts.asStateFlow()
    val analysisSnapshot = analysisStore?.snapshot ?: MutableStateFlow(AnalysisSnapshot()).asStateFlow()
    private val _providerCoverage = MutableStateFlow<Map<Provider, SourceCoverage>>(emptyMap())
    val providerCoverage = _providerCoverage.asStateFlow()
    private val _selectedMonth = MutableStateFlow(YearMonth.now(clock.withZone(AccountingTime.zone)))
    val selectedMonth = _selectedMonth.asStateFlow()
    private val _analysisType = MutableStateFlow(TransactionType.EXPENSE)
    /** Which side of the books Analysis shows; never [TransactionType.TRANSFER]. */
    val analysisType = _analysisType.asStateFlow()
    private val _typeFilter = MutableStateFlow<TransactionType?>(null)
    /** Transactions tab filter; null shows everything. */
    val typeFilter = _typeFilter.asStateFlow()
    private val _dateRangeFilter = MutableStateFlow<Pair<Long?, Long?>?>(null)
    val dateRangeFilter = _dateRangeFilter.asStateFlow()
    private val _categoryFilter = MutableStateFlow<String?>(null)
    val categoryFilter = _categoryFilter.asStateFlow()
    private val _categoryFilterSet = MutableStateFlow<Set<String>?>(null)
    val categoryFilterSet = _categoryFilterSet.asStateFlow()
    private var categoryReturnDates: Pair<Long?, Long?>? = null
    private var categoryDrillActive = false
    private val _targetDestination = MutableStateFlow<String?>(null)
    val targetDestination = _targetDestination.asStateFlow()
    private val _reconciledCache = MutableStateFlow<List<UnifiedTransaction>>(emptyList())
    val allTransactions = _reconciledCache.asStateFlow()
    private val _monthAnalysis = MutableStateFlow(BudgetAnalyzer.analyzeMonth(_selectedMonth.value, emptyList()))
    val monthAnalysis = _monthAnalysis.asStateFlow()
    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _automaticImportActive = MutableStateFlow(false)
    val automaticImportActive = _automaticImportActive.asStateFlow()
    private val _requestedImportProvider = MutableStateFlow<Provider?>(null)
    val requestedImportProvider = _requestedImportProvider.asStateFlow()
    private val _importFeedback = MutableStateFlow<ImportFeedback?>(null)
    val importFeedback = _importFeedback.asStateFlow()
    private var importSequence = 0L
    private val _ingestionStatus = MutableStateFlow<Map<Provider, String>>(emptyMap())
    val ingestionStatus = _ingestionStatus.asStateFlow()
    val enabledProviders = preferences?.enabled ?: MutableStateFlow(Provider.entries.toSet()).asStateFlow()
    val importEnabled = preferences?.importEnabled ?: MutableStateFlow(Provider.entries.toSet()).asStateFlow()
    /** Fresh exposure of the last invisible GoPay refresh, surfaced on the GOJEK provider card. */
    val gojekFlash = preferences?.gojekFlash ?: MutableStateFlow<net.wastu.artha.data.providers.GojekFlashRecord?>(null).asStateFlow()
    val providerDiagnostics = preferences?.diagnostics ?: MutableStateFlow(emptyMap<Provider, net.wastu.artha.data.providers.ProviderDiagnostic>()).asStateFlow()
    private var observerJob: Job? = null
    private var ingestionJob: Job? = null
    private val categorizer = CategorizationEngine(
        viewModelScope, analysisStore, _reconciledCache, analysisSnapshot, clock, computationDispatcher, ioDispatcher,
        categorize, fromMemoryAction, scopeKeysAction, needsReviewAction,
    )
    val categoryProgress = categorizer.progress
    val categoryRunResults = categorizer.runResults
    val categoryAutomation = categorizer.automation

    fun setTargetDestination(dest: String?) { _targetDestination.value = dest }
    fun setProviderEnabled(provider: Provider, enabled: Boolean) { preferences?.setEnabled(provider, enabled) }
    fun setImportEnabled(provider: Provider, enabled: Boolean) { preferences?.setImportEnabled(provider, enabled) }

    fun suggestedExportFileName(): String = LedgerExportStorage.fileName(clock.instant())

    suspend fun buildExportJsonl(): String = withContext(Dispatchers.IO) {
        val raw = ledger().loadTransactions()
        LedgerJsonlExport.build(raw, analysisSnapshot.value, clock.instant())
    }

    suspend fun importExportFile(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val body = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Unable to read the import file.")
        val parsed = LedgerJsonlImport.parse(body)
        android.util.Log.i("ArthaImport", "parsed ${parsed.transactions.size} rows from ${body.length} bytes")
        analysisStore?.mergeImportedAnalysis(parsed.meta, parsed.labels)
        ledger().append(parsed.transactions).also { result ->
            android.util.Log.i("ArthaImport", "append: ${result.inserted} inserted, ${result.duplicates} duplicates")
        }
    }

    fun enterForeground() {
        observeSavedLedger()
        automaticRefresh()
    }

    private fun observeSavedLedger() {
        categorizer.start()
        if (observerJob?.isActive != true) {
            observerJob = viewModelScope.launch {
                val state = spendingState ?: flow {
                    emitAll(spendingFlow(ledger(), analysisSnapshot, computationDispatcher))
                }.flowOn(computationDispatcher).catch { emit(SpendingState.Failed) }
                    .stateIn(this, SharingStarted.Eagerly, SpendingState.Loading)
                launch {
                    combine(state, enabledProviders, _dateRangeFilter, _categoryFilter, _categoryFilterSet) { projection, enabled, dates, category, categorySet ->
                        when (projection) {
                            SpendingState.Loading -> if (hasSavedProjection) visibleLedger(_reconciledCache.value, enabled, dates, category, categorySet) else LedgerState.Loading
                            SpendingState.Failed -> {
                                _ledgerLoadFailed.value = true
                                if (hasSavedProjection) visibleLedger(_reconciledCache.value, enabled, dates, category, categorySet) else LedgerState.Failed
                            }
                            is SpendingState.Ready -> {
                                _ledgerLoadFailed.value = false
                                hasSavedProjection = true
                                _providerCounts.value = projection.providerCounts
                                _providerCoverage.value = sourceCoverage(projection.transactions)
                                _reconciledCache.value = projection.transactions
                                visibleLedger(projection.transactions, enabled, dates, category, categorySet)
                            }
                        }
                    }.flowOn(computationDispatcher).collect { _ledgerState.value = it }
                }
                launch(computationDispatcher) {
                    combine(_reconciledCache, _selectedMonth, _analysisType) { rows, month, type -> BudgetAnalyzer.analyzeMonth(month, rows, type) }
                        .collect { if (it.month == _selectedMonth.value && it.type == _analysisType.value) _monthAnalysis.value = it }
                }
            }
        }
    }

    /** Runs AI categorization for [scope] now, ahead of the automatic queue and regardless of failure back-off. */
    fun recategorize(scope: RecategorizeScope) = categorizer.recategorize(scope)

    /** Payments a [recategorize] run for [scope] would touch, for confirmation dialogs. */
    fun recategorizeCount(scope: RecategorizeScope): Int = categorizer.count(scope)

    /** Cancels in-flight and queued categorization; those payments return to their previous state. */
    fun stopCategorization() = categorizer.stop()

    /** Pauses or resumes the automatic queue; manual [recategorize] runs still work while paused. */
    fun setAutoCategorize(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            analysisStore?.setAutoCategorize(enabled)
        }
    }

    /** Accepts [category] for a reviewed payment; it becomes the user's pick and teaches merchant memory. */
    fun confirmCategory(transaction: UnifiedTransaction, category: String, onComplete: (Result<Unit>) -> Unit) {
        setTransactionCategory(transaction, category) { result ->
            if (result.isSuccess) {
                viewModelScope.launch(ioDispatcher) {
                    analysisStore?.clearCategoryFailures(listOf(transaction.key))
                }
                categorizer.userDecided(listOf(transaction.key))
            }
            onComplete(result)
        }
    }

    /** Other non-manual payments from the same merchant whose category differs from [category]. */
    fun merchantPeerCount(transaction: UnifiedTransaction, category: String): Int {
        val targetKey = merchantKeyProvider(transaction) ?: return 0
        val snapshot = analysisSnapshot.value
        return _reconciledCache.value.count { peer ->
            peer.key != transaction.key &&
                peer.isExpense &&
                !peer.classificationIsManual &&
                peer.key !in snapshot.manualCategories &&
                merchantKeyProvider(peer) == targetKey &&
                (snapshot.categories[peer.key] ?: peer.category) != category
        }
    }

    /** Applies [category] as the user's pick to those peers; reports how many changed. */
    fun applyToMerchant(transaction: UnifiedTransaction, category: String, onComplete: (Result<Int>) -> Unit) {
        val targetKey = merchantKeyProvider(transaction)
        if (targetKey == null) {
            onComplete(Result.success(0))
            return
        }
        val snapshot = analysisSnapshot.value
        val peers = _reconciledCache.value.filter { peer ->
            peer.key != transaction.key &&
                peer.isExpense &&
                !peer.classificationIsManual &&
                peer.key !in snapshot.manualCategories &&
                merchantKeyProvider(peer) == targetKey &&
                (snapshot.categories[peer.key] ?: peer.category) != category
        }
        if (peers.isEmpty()) {
            onComplete(Result.success(0))
            return
        }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                try {
                    val store = checkNotNull(analysisStore) { "Categories are unavailable" }
                    for (peer in peers) {
                        store.setClassification(peer.key, category, "", manual = true)
                    }
                    store.clearCategoryFailures(peers.map { it.key })
                    Result.success(peers.size)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (result.isSuccess) {
                categorizer.userDecided(peers.map { it.key })
            }
            onComplete(result)
        }
    }

    fun retryAutomaticCategories() = categorizer.retry()

    private fun visibleLedger(
        rows: List<UnifiedTransaction>,
        enabled: Set<Provider>,
        dates: Pair<Long?, Long?>?,
        category: String?,
        categorySet: Set<String>? = _categoryFilterSet.value,
    ): LedgerState.Ready {
        val (start, end) = dates ?: (null to null)
        val activeSet = categorySet ?: category?.let { setOf(it) }
        return LedgerState.Ready(rows.filter { tx ->
            val millis = tx.occurredAt.toEpochMilli()
            (activeSet != null || tx.provider in enabled) &&
                (activeSet == null || ((tx.category ?: net.wastu.artha.data.analysis.UNCATEGORIZED_LABEL) in activeSet && tx.type == _analysisType.value)) &&
                (start == null || millis >= start) && (end == null || millis < AccountingTime.dayAfter(end))
        })
    }

    fun retryLedger() {
        if (!hasSavedProjection) _ledgerState.value = LedgerState.Loading
        if (retrySpending != null) retrySpending.invoke() else {
            observerJob?.cancel(); observerJob = null
        }
        observeSavedLedger()
    }

    private fun automaticRefresh() {
        // Every app foreground triggers a sync; per-provider staleness and rate-limit windows
        // inside the importer decide whether each source actually refreshes.
        importHistory(null, automatic = true)
    }

    fun refresh() { importHistory(null) }
    fun syncProvider(provider: Provider) { importHistory(provider) }
    /** Gojek needs its visible history walked first; the host decides how (root UI automation). */
    /** Manual GOJEK refresh: invisible flash + import now; when the screen is on it defers to the next idle window. */
    fun requestGojekRefresh() { (gojekFlashRefresh ?: { syncProvider(Provider.GOJEK) }).invoke() }
    private fun importHistory(provider: Provider?, automatic: Boolean = false) {
        if (automatic && preferences != null && importEnabled.value.none { preferences.isStale(it, clock.millis()) }) return
        val previous = ingestionJob?.takeIf { it.isActive }
        if (previous != null && (automatic || !_automaticImportActive.value)) {
            if (!automatic) _importFeedback.value = ImportFeedback(++importSequence, provider, "An import is already running")
            return
        }
        previous?.cancel()
        if (!automatic) _importFeedback.value = null
        _requestedImportProvider.value = provider
        _refreshing.value = true
        _automaticImportActive.value = automatic
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                _rootGate.value = RootGateState.CHECKING
                val granted = net.wastu.artha.BuildConfig.DEMO || try { rootAccess.verify() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                _rootGate.value = if (granted) RootGateState.GRANTED else RootGateState.DENIED
                if (!granted) {
                    if (!automatic) _importFeedback.value = ImportFeedback(++importSequence, provider, "Root access unavailable. Allow root, then try again", true)
                    return@launch
                }
                val result = if (net.wastu.artha.BuildConfig.DEMO) simulateDemoSync(provider)
                    else if (provider != null && syncSingle != null) mapOf(provider to syncSingle.invoke(provider))
                    else if (automatic && ingestStale != null) ingestStale.invoke() else ingest?.invoke().orEmpty()
                _ingestionStatus.value = _ingestionStatus.value + result
                if (!automatic) {
                    val failed = result.values.count { it.startsWith("Unavailable:") }
                    val message = when {
                        result.isEmpty() -> "No sources enabled for import"
                        failed == result.size -> "Import failed. Open source details"
                        failed > 0 -> "Import finished. Some sources are unavailable"
                        preferences?.diagnostics?.value?.filterKeys { it in result }?.values?.any { it.accountStatus == net.wastu.artha.data.providers.AccountStatus.PARTIAL } == true -> "Imported available history"
                        else -> "Import complete"
                    }
                    _importFeedback.value = ImportFeedback(++importSequence, provider, message, result.isEmpty() || failed == result.size)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                _ingestionStatus.value = _ingestionStatus.value + ((provider ?: Provider.JAGO) to (e.message ?: "Import unavailable"))
                if (!automatic) _importFeedback.value = ImportFeedback(++importSequence, provider, "Import failed. Try again", true)
            }
            finally {
                if (ingestionJob === coroutineContext[Job]) {
                    _refreshing.value = false; _requestedImportProvider.value = null; _automaticImportActive.value = false
                }
            }
        }
        ingestionJob = job
        job.start()
    }

    /** Demo builds have no sources to read, so a sync pauses briefly and stamps each source as freshly synced. */
    private suspend fun simulateDemoSync(provider: Provider?): Map<Provider, String> {
        kotlinx.coroutines.delay(900)
        val targets = provider?.let { listOf(it) } ?: importEnabled.value.toList()
        return targets.associateWith { p ->
            val count = _providerCounts.value[p] ?: 0
            val previous = preferences?.diagnostics?.value?.get(p)
            preferences?.recordSyncSuccess(p, "$count payments, up to date", previous?.accountIdentifier, previous?.coverageHint, null, previous?.lastImportWindow)
            "$count payments, up to date"
        }
    }

    fun stopForegroundImports() {
        if (ingestionJob?.isActive == true) {
            ingestionJob?.cancel(); _rootGate.value = RootGateState.LOCKED
        }
    }
    fun lock() {
        stopForegroundImports(); observerJob?.cancel()
        categorizer.shutdown()
        _rootGate.value = RootGateState.LOCKED; _ledgerState.value = LedgerState.Loading
    }
    fun setAnalysisMonth(month: YearMonth) {
        if (month <= YearMonth.now(clock.withZone(AccountingTime.zone))) {
            _selectedMonth.value = month
            _monthAnalysis.value = BudgetAnalyzer.analyzeMonth(month, _reconciledCache.value, _analysisType.value)
        }
    }
    fun setAnalysisType(type: TransactionType) {
        require(type != TransactionType.TRANSFER)
        _analysisType.value = type
        _monthAnalysis.value = BudgetAnalyzer.analyzeMonth(_selectedMonth.value, _reconciledCache.value, type)
    }
    fun setTypeFilter(type: TransactionType?) { _typeFilter.value = type }
    fun clearClassification(key: String) { storeWrite("Categories are unavailable", {}) { clearClassification(key) } }
    fun setTransactionType(transaction: UnifiedTransaction, type: TransactionType?, onComplete: (Result<Unit>) -> Unit = {}) =
        storeWrite("Transactions are unavailable", onComplete) { setTypeOverride(transaction.key, type) }
    fun setDateRangeFilter(startMillis: Long?, endMillis: Long?) {
        _dateRangeFilter.value = if (startMillis == null && endMillis == null) null else startMillis to endMillis
    }
    fun clearDateRangeFilter() { _dateRangeFilter.value = null }
    fun clearCategoryFilter() {
        _categoryFilter.value = null
        _categoryFilterSet.value = null
        categoryDrillActive = false
        categoryReturnDates = null
    }
    fun openCategoryTransactions(category: String, categories: Set<String> = setOf(category)) {
        if (!categoryDrillActive) categoryReturnDates = _dateRangeFilter.value
        categoryDrillActive = true
        val month = selectedMonth.value
        setDateRangeFilter(month.atDay(1).atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli(),
            month.atEndOfMonth().atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli())
        _categoryFilter.value = category
        _categoryFilterSet.value = categories
        setTargetDestination("TRANSACTIONS")
    }
    fun closeCategoryTransactions() {
        if (!categoryDrillActive) return
        _dateRangeFilter.value = categoryReturnDates
        clearCategoryFilter()
        setTargetDestination("ANALYSIS")
    }
    /** Runs one analysis-store write off the main thread and reports its outcome; cancellation still propagates. */
    private fun storeWrite(unavailable: String, onComplete: (Result<Unit>) -> Unit, write: AnalysisStore.() -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { checkNotNull(analysisStore) { unavailable }.write(); Result.success(Unit) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure<Unit>(error) }
            }
            onComplete(result)
        }
    }
    fun setUserNote(transaction: UnifiedTransaction, note: String, onComplete: (Result<Unit>) -> Unit) =
        storeWrite("Notes are unavailable", onComplete) { setUserNote(transaction.key, note) }
    fun setTransactionCategory(transaction: UnifiedTransaction, category: String, onComplete: (Result<Unit>) -> Unit) =
        storeWrite("Categories are unavailable", onComplete) { setClassification(transaction.key, category, "", manual = true) }
    fun applyKnowledgeRewrite(rewritten: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setKnowledge(rewritten) } }
    fun updateAiConfig(config: AiConfig, onComplete: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    checkNotNull(analysisStore) { "AI settings are unavailable" }.setAiConfig(config)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    e.message ?: "AI settings could not be saved securely"
                }
            }
            _settingsError.value = error
            onComplete(error)
        }
    }
    fun addCustomCategory(category: String, type: TransactionType = TransactionType.EXPENSE, onComplete: (Result<Unit>) -> Unit = {}) =
        storeWrite("Categories unavailable", onComplete) { addCustomCategory(category, type) }
    fun removeCustomCategory(category: String, type: TransactionType = TransactionType.EXPENSE) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.removeCustomCategory(category, type) } }
    fun resetCategoriesToDefault() { viewModelScope.launch(Dispatchers.IO) { analysisStore?.resetCategoriesToDefault() } }
    fun setCategoryIcon(category: String, iconKey: String?) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setCategoryIcon(category, iconKey) } }
}
