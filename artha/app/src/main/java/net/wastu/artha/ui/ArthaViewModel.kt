package net.wastu.artha.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.*
import net.wastu.artha.data.analysis.*
import net.wastu.artha.data.ledger.LedgerRepository
import net.wastu.artha.data.providers.ProviderPreferences
import net.wastu.artha.data.sync.DriveSyncManager

sealed interface LedgerState {
    data object Loading : LedgerState
    data class Ready(val transactions: List<UnifiedTransaction>) : LedgerState
    data object Failed : LedgerState
}

data class ImportFeedback(val sequence: Long, val provider: Provider?, val message: String, val failed: Boolean = false)

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
    val driveSyncManager: DriveSyncManager? = null,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val spendingState: StateFlow<SpendingState>? = null,
    private val retrySpending: (() -> Unit)? = null,
    private val categorize: suspend (AiConfig, AnalysisSnapshot, List<CategoryCandidate>) -> Map<String, CategorySuggestion> = AiCategorization::classify,
    initialSnapshot: AnalysisSnapshot = AnalysisSnapshot(),
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
    val analysisSnapshot = analysisStore?.snapshot ?: MutableStateFlow(initialSnapshot).asStateFlow()
    private val _selectedMonth = MutableStateFlow(YearMonth.now(clock.withZone(AccountingTime.zone)))
    val selectedMonth = _selectedMonth.asStateFlow()
    private val _dateRangeFilter = MutableStateFlow<Pair<Long?, Long?>?>(null)
    val dateRangeFilter = _dateRangeFilter.asStateFlow()
    private val _categoryFilter = MutableStateFlow<String?>(null)
    val categoryFilter = _categoryFilter.asStateFlow()
    private var categoryReturnDates: Pair<Long?, Long?>? = null
    private var categoryDrillActive = false
    private val _targetDestination = MutableStateFlow<String?>(null)
    val targetDestination = _targetDestination.asStateFlow()
    private val _reconciledCache = MutableStateFlow<List<UnifiedTransaction>>(emptyList())
    val allTransactions = _reconciledCache.asStateFlow()
    private val _monthAnalysis = MutableStateFlow(BudgetAnalyzer.analyzeMonth(_selectedMonth.value, emptyList()))
    val monthAnalysis = _monthAnalysis.asStateFlow()
    private val _analyzingMonths = MutableStateFlow<Set<YearMonth>>(emptySet())
    val analyzingMonths = _analyzingMonths.asStateFlow()
    private val _analysisErrors = MutableStateFlow<Map<YearMonth, String>>(emptyMap())
    val analysisErrors = _analysisErrors.asStateFlow()
    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()
    private val _analysisStages = MutableStateFlow<Map<YearMonth, AnalysisStage>>(emptyMap())
    val analysisStages = _analysisStages.asStateFlow()
    private val monthJobs = mutableMapOf<YearMonth, Job>()
    private val failedFingerprints = mutableMapOf<YearMonth, String>()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _automaticImportActive = MutableStateFlow(false)
    val automaticImportActive = _automaticImportActive.asStateFlow()
    private val _requestedImportProvider = MutableStateFlow<Provider?>(null)
    val requestedImportProvider = _requestedImportProvider.asStateFlow()
    private val _importFeedback = MutableStateFlow<ImportFeedback?>(null)
    val importFeedback = _importFeedback.asStateFlow()
    private var importSequence = 0L
    private val _categoryAutomation = MutableStateFlow(CategoryAutomationState.NOT_CONFIGURED)
    val categoryAutomation = _categoryAutomation.asStateFlow()
    private val categoryRetry = MutableStateFlow(0)
    private val _ingestionStatus = MutableStateFlow<Map<Provider, String>>(emptyMap())
    val ingestionStatus = _ingestionStatus.asStateFlow()
    val enabledProviders = preferences?.enabled ?: MutableStateFlow(Provider.entries.toSet()).asStateFlow()
    val importEnabled = preferences?.importEnabled ?: MutableStateFlow(Provider.entries.toSet()).asStateFlow()
    val providerDiagnostics = preferences?.diagnostics ?: MutableStateFlow(emptyMap<Provider, net.wastu.artha.data.providers.ProviderDiagnostic>()).asStateFlow()
    val driveSyncState = driveSyncManager?.syncState ?: MutableStateFlow(net.wastu.artha.data.sync.DriveSyncState()).asStateFlow()
    private var observerJob: Job? = null
    private var ingestionJob: Job? = null
    private var categorizationJob: Job? = null
    private var lastAutomaticRefresh = Long.MIN_VALUE

    fun setTargetDestination(dest: String?) { _targetDestination.value = dest }
    fun setProviderEnabled(provider: Provider, enabled: Boolean) { preferences?.setEnabled(provider, enabled) }
    fun setImportEnabled(provider: Provider, enabled: Boolean) { preferences?.setImportEnabled(provider, enabled) }
    fun setDriveSyncEnabled(enabled: Boolean) { driveSyncManager?.setEnabled(enabled) }
    fun setDriveAccountEmail(email: String?) { driveSyncManager?.setAccountEmail(email) }
    fun setDriveEncryptionPassword(password: String) { viewModelScope.launch(Dispatchers.IO) { driveSyncManager?.setEncryptionPassword(password) } }
    fun syncDriveNow() { viewModelScope.launch { driveSyncManager?.syncNow() } }
    fun restoreDriveBackup() { viewModelScope.launch { driveSyncManager?.restoreNow() } }

    fun enterForeground() {
        observeSavedLedger()
        automaticRefresh()
        analyzeMonth(_selectedMonth.value)
    }

    private fun observeSavedLedger() {
        observeAutomaticCategories()
        if (observerJob?.isActive != true) {
            observerJob = viewModelScope.launch {
                val state = spendingState ?: flow {
                    emitAll(spendingFlow(ledger(), analysisSnapshot, computationDispatcher,
                        migrate = { analysisStore?.migrateMetadata(it) }))
                }.flowOn(computationDispatcher).catch { emit(SpendingState.Failed) }
                    .stateIn(this, SharingStarted.Eagerly, SpendingState.Loading)
                launch {
                    combine(state, enabledProviders, _dateRangeFilter, _categoryFilter) { projection, enabled, dates, category ->
                        when (projection) {
                            SpendingState.Loading -> if (hasSavedProjection) visibleLedger(_reconciledCache.value, enabled, dates, category) else LedgerState.Loading
                            SpendingState.Failed -> {
                                _ledgerLoadFailed.value = true
                                if (hasSavedProjection) visibleLedger(_reconciledCache.value, enabled, dates, category) else LedgerState.Failed
                            }
                            is SpendingState.Ready -> {
                                _ledgerLoadFailed.value = false
                                hasSavedProjection = true
                                _providerCounts.value = projection.providerCounts
                                _reconciledCache.value = projection.transactions
                                visibleLedger(projection.transactions, enabled, dates, category)
                            }
                        }
                    }.flowOn(computationDispatcher).collect { _ledgerState.value = it }
                }
                launch(computationDispatcher) {
                    combine(_reconciledCache, _selectedMonth, analysisSnapshot.map { it.monthlySummaries }.distinctUntilChanged()) { rows, month, summaries ->
                        BudgetAnalyzer.analyzeMonth(month, rows, summaries[analysisKey(month)])
                    }.collect { if (it.month == _selectedMonth.value) _monthAnalysis.value = it }
                }
                launch {
                    combine(_reconciledCache, _selectedMonth, analysisSnapshot, _refreshing, _categoryAutomation) { rows, month, snapshot, importing, categoryState ->
                        if (importing || categoryState == CategoryAutomationState.WORKING) null else month to fingerprint(month, rows, snapshot)
                    }.flowOn(computationDispatcher).distinctUntilChanged().debounce(2000).collect { input ->
                        input?.let { (month, _) -> analyzeMonth(month, debounceMs = 0) }
                    }
                }
            }
        }
    }

    private fun observeAutomaticCategories() {
        val store = analysisStore ?: return
        if (categorizationJob?.isActive == true) return
        categorizationJob = viewModelScope.launch {
            var failedBatch: String? = null
            var previousConfig: AiConfig? = null
            var previousRetry = 0
            combine(_reconciledCache, analysisSnapshot.map(AiCategorization::requestSnapshot).distinctUntilChanged(), categoryRetry) { rows, snapshot, retry ->
                CategoryInput(rows, snapshot, retry)
            }.debounce(1000).collectLatest { input ->
                val config = input.snapshot.aiConfig
                if (config != previousConfig || input.retry != previousRetry) failedBatch = null
                previousConfig = config; previousRetry = input.retry
                if (config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
                    _categoryAutomation.value = CategoryAutomationState.NOT_CONFIGURED
                    return@collectLatest
                }
                val batch = withContext(computationDispatcher) { AiCategorization.nextBatch(input.rows, input.snapshot) }
                if (batch.isEmpty()) {
                    _categoryAutomation.value = CategoryAutomationState.READY
                    return@collectLatest
                }
                val batchId = batch.joinToString(":") { it.fingerprint }
                if (failedBatch == batchId) return@collectLatest
                _categoryAutomation.value = CategoryAutomationState.WORKING
                try {
                    val suggestions = AiCategorization.requestWithRetry { categorize(config, input.snapshot, batch) }
                    ensureActive()
                    val currentRows = _reconciledCache.value.associateBy { it.key }
                    val unchanged = batch.filter { candidate ->
                        currentRows[candidate.transaction.key]?.let { current ->
                            !current.classificationIsManual && AiCategorization.fingerprint(current, analysisSnapshot.value) == candidate.fingerprint
                        } == true
                    }
                    withContext(Dispatchers.IO) { store.commitCategorySuggestions(unchanged, suggestions, input.snapshot) }
                    _categoryAutomation.value = CategoryAutomationState.READY
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failedBatch = batchId; _categoryAutomation.value = CategoryAutomationState.ERROR }
            }
        }
    }

    private data class CategoryInput(val rows: List<UnifiedTransaction>, val snapshot: AnalysisSnapshot, val retry: Int)
    fun retryAutomaticCategories() { categoryRetry.update { it + 1 }; observeAutomaticCategories() }

    private fun visibleLedger(rows: List<UnifiedTransaction>, enabled: Set<Provider>, dates: Pair<Long?, Long?>?, category: String?): LedgerState.Ready {
        val (start, end) = dates ?: (null to null)
        return LedgerState.Ready(rows.filter { tx ->
            val millis = tx.occurredAt.toEpochMilli()
            (category != null || tx.provider in enabled) && (category == null || tx.category == category && BudgetAnalyzer.isSpending(tx)) &&
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
        val now = clock.millis()
        if (lastAutomaticRefresh != Long.MIN_VALUE && now - lastAutomaticRefresh < 15 * 60_000) return
        lastAutomaticRefresh = now
        importHistory(null, automatic = true)
    }

    fun refresh() { importHistory(null) }
    fun syncProvider(provider: Provider) { importHistory(provider) }
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
                val granted = try { rootAccess.verify() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                _rootGate.value = if (granted) RootGateState.GRANTED else RootGateState.DENIED
                if (!granted) {
                    if (!automatic) _importFeedback.value = ImportFeedback(++importSequence, provider, "Root access unavailable. Allow root, then try again", true)
                    return@launch
                }
                val result = if (provider != null && syncSingle != null) mapOf(provider to syncSingle.invoke(provider))
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
                if (result.isNotEmpty() && driveSyncState.value.isEnabled) driveSyncManager?.syncNow()
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

    fun stopForegroundImports() {
        if (ingestionJob?.isActive == true) {
            ingestionJob?.cancel(); lastAutomaticRefresh = Long.MIN_VALUE; _rootGate.value = RootGateState.LOCKED
        }
    }
    fun lock() {
        stopForegroundImports(); observerJob?.cancel(); categorizationJob?.cancel(); monthJobs.values.forEach(Job::cancel); monthJobs.clear()
        lastAutomaticRefresh = Long.MIN_VALUE
        _rootGate.value = RootGateState.LOCKED; _ledgerState.value = LedgerState.Loading
    }
    fun setAnalysisMonth(month: YearMonth) {
        if (month <= YearMonth.now(clock.withZone(AccountingTime.zone))) _selectedMonth.value = month
    }
    fun setDateRangeFilter(startMillis: Long?, endMillis: Long?) {
        _dateRangeFilter.value = if (startMillis == null && endMillis == null) null else startMillis to endMillis
    }
    fun clearDateRangeFilter() { _dateRangeFilter.value = null }
    fun clearCategoryFilter() { _categoryFilter.value = null; categoryDrillActive = false; categoryReturnDates = null }
    fun openCategoryTransactions(category: String) {
        if (!categoryDrillActive) categoryReturnDates = _dateRangeFilter.value
        categoryDrillActive = true
        val month = selectedMonth.value
        setDateRangeFilter(month.atDay(1).atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli(),
            month.atEndOfMonth().atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli())
        _categoryFilter.value = category; setTargetDestination("TRANSACTIONS")
    }
    fun closeCategoryTransactions() {
        if (!categoryDrillActive) return
        _dateRangeFilter.value = categoryReturnDates
        clearCategoryFilter()
        setTargetDestination("ANALYSIS")
    }
    fun setUserNote(transaction: UnifiedTransaction, note: String, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    checkNotNull(analysisStore) { "Notes are unavailable" }.setUserNote(transaction.key, note)
                    Result.success(Unit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure<Unit>(error)
                }
            }
            onComplete(result)
        }
    }
    fun setTransactionCategory(transaction: UnifiedTransaction, category: String, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    checkNotNull(analysisStore) { "Categories are unavailable" }.setClassification(transaction.key, category, "", manual = true)
                    Result.success(Unit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure<Unit>(error)
                }
            }
            onComplete(result)
        }
    }
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
    fun addCustomCategory(category: String, onComplete: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { checkNotNull(analysisStore) { "Categories unavailable" }.addCustomCategory(category); Result.success(Unit) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure<Unit>(error) }
            }
            onComplete(result)
        }
    }
    fun removeCustomCategory(category: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.removeCustomCategory(category) } }
    fun resetCategoriesToDefault() { viewModelScope.launch(Dispatchers.IO) { analysisStore?.resetCategoriesToDefault() } }
    fun setCategoryIcon(category: String, iconKey: String?) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setCategoryIcon(category, iconKey) } }
    fun setMonthlyNote(month: String, note: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setMonthlyNote(month, note) } }

    private fun fingerprint(month: YearMonth, rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): String =
        BudgetAnalyzer.computeMonthFingerprint(rows.filter {
            YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) <= month &&
                BudgetAnalyzer.isSpending(it)
        }, snapshot.monthlyNotes[month.toString()].orEmpty(),
            snapshot.knowledge + "\nresearch-v6\n${snapshot.aiConfig.baseUrl}\n${snapshot.aiConfig.model}\n${snapshot.aiConfig.apiKey}\n${if (month == YearMonth.now(clock.withZone(AccountingTime.zone))) java.time.LocalDate.now(clock.withZone(AccountingTime.zone)) else month}", snapshot.customCategories)

    fun analyzeMonth(month: YearMonth, forceReanalyze: Boolean = false, debounceMs: Long = 600) {
        if ((_refreshing.value || _categoryAutomation.value == CategoryAutomationState.WORKING) && !forceReanalyze) return
        val config = analysisSnapshot.value.aiConfig
        if (analysisStore == null || config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) return
        monthJobs[month]?.cancel()
        val key = analysisKey(month)
        val job = viewModelScope.launch {
            try {
                if (debounceMs > 0) delay(debounceMs)
                val snapshot = analysisSnapshot.value
                val rows = _reconciledCache.value
                if (withContext(computationDispatcher) { rows.none { YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) <= month && BudgetAnalyzer.isSpending(it) } }) return@launch
                val fp = withContext(computationDispatcher) { fingerprint(month, rows, snapshot) }
                if (!forceReanalyze && (failedFingerprints[month] == fp || snapshot.analysisFingerprints[key] == fp)) return@launch
                _analyzingMonths.update { it + month }; _analysisErrors.update { it - month }
                try {
                    val result = AiAnalysisService.analyzeMonth(snapshot.aiConfig, snapshot.knowledge, month.toString(), rows,
                        snapshot.customCategories, snapshot.monthlyNotes[month.toString()].orEmpty(),
                        onProgress = { stage -> _analysisStages.update { it + (month to stage) } })
                    ensureActive()
                    if (withContext(computationDispatcher) { fingerprint(month, _reconciledCache.value, analysisSnapshot.value) } != fp) return@launch
                    _analysisStages.update { it + (month to AnalysisStage.SAVING) }
                    val committed = withContext(Dispatchers.IO) { analysisStore.commitMonthSummary(key, result.monthlySummary, fp, snapshot) }
                    if (committed) failedFingerprints.remove(month)
                } catch (timeout: TimeoutCancellationException) {
                    failedFingerprints[month] = fp
                    _analysisErrors.update { it + (month to "Analysis timed out. Try again") }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    failedFingerprints[month] = fp
                    _analysisErrors.update { it + (month to "Analysis unavailable. Try again") }
                }
            } finally {
                if (monthJobs[month] === coroutineContext[Job]) {
                    monthJobs.remove(month); _analyzingMonths.update { it - month }; _analysisStages.update { it - month }
                }
            }
        }
        monthJobs[month] = job
    }
    fun analyzeCurrentMonth() { analyzeMonth(_selectedMonth.value, forceReanalyze = true) }
}
