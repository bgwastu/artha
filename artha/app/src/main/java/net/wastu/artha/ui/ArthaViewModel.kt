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
    private val gojekHistoryWalk: (() -> Unit)? = null,
    private val gojekFlashRefresh: (() -> Unit)? = null,
    private val categorize: suspend (AiConfig, AnalysisSnapshot, List<CategoryCandidate>) -> Map<String, CategorySuggestion> = AiCategorization::classify,
    private val fromMemoryAction: (List<UnifiedTransaction>, AnalysisSnapshot) -> Map<String, CategorySuggestion> = AiCategorization::fromMemory,
    private val scopeKeysAction: (RecategorizeScope, List<UnifiedTransaction>, AnalysisSnapshot) -> Set<String> = AiCategorization::scopeKeys,
    private val needsReviewAction: (UnifiedTransaction, AnalysisSnapshot) -> Boolean = ::needsReview,
    private val merchantKeyProvider: (UnifiedTransaction) -> String? = ::merchantKey,
    private val analyzeMonthAction: suspend (
        config: AiConfig,
        knowledge: String,
        monthLabel: String,
        transactions: List<UnifiedTransaction>,
        availableCategories: List<String>,
        monthlyUserNote: String,
        onProgress: (AnalysisStage) -> Unit,
    ) -> MonthAnalysisResult = { config, knowledge, monthLabel, transactions, availableCategories, monthlyUserNote, onProgress ->
        AiAnalysisService.analyzeMonth(config, knowledge, monthLabel, transactions, availableCategories, monthlyUserNote, onProgress)
    },
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
    private val _providerCoverage = MutableStateFlow<Map<Provider, SourceCoverage>>(emptyMap())
    val providerCoverage = _providerCoverage.asStateFlow()
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
    /** Status of the selected month's AI commentary; the single source for every analysis status line. */
    private val _analysisStatus = MutableStateFlow<MonthAnalysisStatus>(MonthAnalysisStatus.NeverRun)
    val analysisStatus = _analysisStatus.asStateFlow()
    private val _categoryProgress = MutableStateFlow(CategoryProgress())
    val categoryProgress = _categoryProgress.asStateFlow()
    private val monthJobs = mutableMapOf<YearMonth, Job>()
    private val fallbackFailures = MutableStateFlow<Map<String, AnalysisFailure>>(emptyMap())
    private val analysisFailures: StateFlow<Map<String, AnalysisFailure>> =
        analysisStore?.analysisFailures ?: fallbackFailures.asStateFlow()

    private fun recordAnalysisFailure(key: String, message: String, fingerprint: String) {
        val store = analysisStore
        if (store != null) {
            store.recordAnalysisFailure(key, message, fingerprint)
        } else {
            val now = System.currentTimeMillis()
            fallbackFailures.update { it + (key to AnalysisFailure(message, now, fingerprint)) }
        }
    }

    private fun clearAnalysisFailure(key: String) {
        val store = analysisStore
        if (store != null) {
            store.clearAnalysisFailure(key)
        } else {
            fallbackFailures.update { it - key }
        }
    }
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
    /** Fresh exposure of the last invisible GoPay refresh, surfaced on the GOJEK provider card. */
    val gojekFlash = preferences?.gojekFlash ?: MutableStateFlow<net.wastu.artha.data.providers.GojekFlashRecord?>(null).asStateFlow()
    val providerDiagnostics = preferences?.diagnostics ?: MutableStateFlow(emptyMap<Provider, net.wastu.artha.data.providers.ProviderDiagnostic>()).asStateFlow()
    private var observerJob: Job? = null
    private var ingestionJob: Job? = null
    private var categorizationJob: Job? = null

    fun setTargetDestination(dest: String?) { _targetDestination.value = dest }
    fun setProviderEnabled(provider: Provider, enabled: Boolean) { preferences?.setEnabled(provider, enabled) }
    fun setImportEnabled(provider: Provider, enabled: Boolean) { preferences?.setImportEnabled(provider, enabled) }
    fun setAnalysisEnabled(enabled: Boolean) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setAnalysisEnabled(enabled) } }

    fun suggestedExportFileName(): String = LedgerExportStorage.fileName(clock.instant())

    suspend fun buildExportJsonl(): String = withContext(Dispatchers.IO) {
        val raw = ledger().loadTransactions()
        val editable = raw.filterNot(ProviderObservations::isObservation)
        analysisStore?.migrateMetadata(editable)
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
                                _providerCoverage.value = sourceCoverage(projection.transactions)
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
                        // Hashing every spending row is wasted work while analysis is off.
                        if (!snapshot.analysisEnabled || importing || categoryState == CategoryAutomationState.WORKING) null
                        else month to fingerprint(month, rows, snapshot)
                    }.flowOn(computationDispatcher).distinctUntilChanged().debounce(2000).collect { input ->
                        input?.let { (month, _) -> analyzeMonth(month, debounceMs = 0) }
                    }
                }
                launch(computationDispatcher) {
                    combine(
                        combine(_reconciledCache, _selectedMonth, analysisSnapshot) { rows, month, snapshot ->
                            AnalysisStatusData(rows, month, snapshot)
                        },
                        combine(_refreshing, _categoryAutomation) { importing, catState ->
                            importing to catState
                        },
                        combine(_analyzingMonths, _analysisStages, analysisFailures) { running, stages, failures ->
                            AnalysisStatusExecution(running, stages, failures)
                        }
                    ) { data, (importing, catState), exec ->
                        val month = data.month
                        val snapshot = data.snapshot
                        val rows = data.rows
                        val key = analysisKey(month)
                        val config = snapshot.aiConfig
                        val configured = config.baseUrl.isNotBlank() && config.model.isNotBlank() && config.apiKey.isNotBlank()
                        // Hashing every spending row is wasted work while analysis is off or unconfigured.
                        if (!snapshot.analysisEnabled) return@combine MonthAnalysisStatus.Off
                        if (!configured) return@combine MonthAnalysisStatus.NotConfigured
                        val hasSpending = rows.any {
                            YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) <= month && BudgetAnalyzer.isSpending(it)
                        }
                        val summary = snapshot.monthlySummaries[key]
                        val hasSummary = !summary.isNullOrBlank()
                        val savedAt = snapshot.updatedAt["monthSummary:$key"]
                        val currentFp = fingerprint(month, rows, snapshot)
                        val upToDate = snapshot.analysisFingerprints[key] == currentFp
                        val running = month in exec.running
                        val stage = exec.stages[month]
                        val waiting = when {
                            importing -> AnalysisWait.IMPORT
                            catState == CategoryAutomationState.WORKING -> AnalysisWait.CATEGORIES
                            else -> null
                        }
                        val failureEntry = exec.failures[key]
                        val failure = if (failureEntry != null && failureEntry.fingerprint == currentFp) {
                            failureEntry.message to failureEntry.at
                        } else null

                        monthAnalysisStatus(
                            enabled = snapshot.analysisEnabled,
                            configured = configured,
                            hasSpending = hasSpending,
                            hasSummary = hasSummary,
                            savedAt = savedAt,
                            upToDate = upToDate,
                            running = running,
                            stage = stage,
                            waiting = waiting,
                            failure = failure,
                        )
                    }.distinctUntilChanged().collect {
                        _analysisStatus.value = it
                    }
                }
            }
        }
    }

    private val queuePumpTrigger = MutableStateFlow(0L)
    private val activeBatchJobs = mutableMapOf<Job, Set<String>>()
    private val inFlightKeys = mutableSetOf<String>()
    private val manualQueue = mutableListOf<ManualQueueEntry>()
    private val replaceManualKeys = mutableSetOf<String>()
    private val batchAttempts = mutableMapOf<String, Int>()
    private val backedOffKeys = mutableSetOf<String>()
    private val backoffJobs = mutableMapOf<String, Job>()
    private var nonRetryableError: String? = null
    private val categoryBackoff = listOf(30_000L, 120_000L, 600_000L)
    private data class ManualQueueEntry(val key: String, val replaceManual: Boolean)
    private data class QueueInput(
        val rows: List<UnifiedTransaction>,
        val snapshot: AnalysisSnapshot,
        val month: YearMonth,
    )

    private fun isNonRetryableHttp(e: Exception): Boolean {
        val http = (e as? AiHttpException) ?: (e.cause as? AiHttpException)
        return http != null && http.status in setOf(400, 401, 403, 404)
    }

    private fun triggerPump() {
        queuePumpTrigger.update { it + 1L }
    }

    private fun observeAutomaticCategories() {
        val store = analysisStore ?: return
        if (categorizationJob?.isActive == true) return
        categorizationJob = viewModelScope.launch {
            var previousConfig: AiConfig? = null
            var previousRows: List<Pair<String, java.time.Instant>>? = null
            var previousEnabled = false

            combine(
                _reconciledCache,
                analysisSnapshot,
                _selectedMonth,
                queuePumpTrigger,
            ) { rows, snapshot, month, _ ->
                QueueInput(rows, snapshot, month)
            }.collect { input ->
                val config = input.snapshot.aiConfig
                val enabled = input.snapshot.analysisEnabled
                val rowSigs = input.rows.map { it.key to it.occurredAt }

                val configChanged = config != previousConfig
                val enabledChanged = enabled != previousEnabled
                val rowsChanged = rowSigs != previousRows

                if (configChanged || enabledChanged || rowsChanged) {
                    for (job in activeBatchJobs.keys.toList()) job.cancel()
                    activeBatchJobs.clear()
                    inFlightKeys.clear()
                    for (job in backoffJobs.values) job.cancel()
                    backoffJobs.clear()
                    backedOffKeys.clear()

                    if (configChanged) {
                        batchAttempts.clear()
                        nonRetryableError = null
                    }
                    previousConfig = config
                    previousEnabled = enabled
                    previousRows = rowSigs
                }

                if (!enabled || config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
                    val progress = CategoryProgress(
                        state = CategoryAutomationState.NOT_CONFIGURED,
                        paused = !input.snapshot.autoCategorize,
                    )
                    _categoryProgress.value = progress
                    _categoryAutomation.value = CategoryAutomationState.NOT_CONFIGURED
                    return@collect
                }

                // One plan per pump, computed off the main thread: fingerprints hash every eligible payment.
                val plan = withContext(computationDispatcher) { categoryPlan(input.rows, input.snapshot) }
                lastPlan = plan
                if (nonRetryableError != null && manualQueue.isEmpty() && activeBatchJobs.isEmpty()) {
                    updateCategoryProgress(input.snapshot)
                    return@collect
                }

                // A memory commit changes the snapshot, which re-emits with a fresh plan.
                if (commitMerchantMemory(plan.memory, input.snapshot)) return@collect

                while (activeBatchJobs.size < 2) {
                    val batch = getNextCategoryBatch(input.snapshot, input.rows, plan)
                    if (batch.isEmpty()) break
                    launchCategoryBatch(batch, config, input.snapshot)
                }

                updateCategoryProgress(input.snapshot)
            }
        }
    }

    private fun isNeedingCategory(tx: UnifiedTransaction, snapshot: AnalysisSnapshot): Boolean {
        val reason = snapshot.reasons[tx.key]
        if (reason != null && (reason.startsWith(MERCHANT_REASON_PREFIX) || reason.startsWith(AI_REASON_PREFIX))) {
            if (reason.startsWith(MERCHANT_REASON_PREFIX)) return false
        }
        return AiCategorization.needsLabel(CategoryCandidate(tx, AiCategorization.fingerprint(tx, snapshot)), snapshot)
    }

    private class CategoryPlan(
        val total: Int,
        /** Payments still needing an AI label, newest first. */
        val needing: List<UnifiedTransaction>,
        val review: Int,
        val failed: Int,
        val memory: Map<String, CategorySuggestion>,
    )
    private var lastPlan: CategoryPlan? = null

    private fun categoryPlan(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): CategoryPlan {
        val eligible = rows.filter { AiCategorization.isEligible(it, snapshot) }.distinctBy { it.key }.sortedByDescending { it.occurredAt }
        // Only answers that change something; rewriting identical labels would re-emit forever.
        val memory = fromMemoryAction(rows, snapshot).filter { (key, suggestion) ->
            snapshot.categories[key] != suggestion.category || snapshot.reasons[key] != suggestion.reason
        }
        return CategoryPlan(eligible.size, eligible.filter { isNeedingCategory(it, snapshot) },
            eligible.count { needsReviewAction(it, snapshot) }, eligible.count { it.key in snapshot.categoryFailures }, memory)
    }

    private suspend fun commitMerchantMemory(memory: Map<String, CategorySuggestion>, snapshot: AnalysisSnapshot): Boolean {
        val store = analysisStore ?: return false
        if (memory.isEmpty()) return false
        return withContext(ioDispatcher) { store.commitMerchantSuggestions(memory, snapshot) } > 0
    }

    private fun getNextCategoryBatch(snapshot: AnalysisSnapshot, rows: List<UnifiedTransaction>, plan: CategoryPlan): List<CategoryCandidate> {
        val candidates = mutableListOf<CategoryCandidate>()
        val rowsByKey = rows.associateBy { it.key }

        // 1. Manual runs first (in request order)
        for (entry in manualQueue) {
            if (candidates.size >= AiCategorization.MAX_BATCH) break
            if (entry.key in inFlightKeys) continue
            val tx = rowsByKey[entry.key] ?: continue
            val candidateTx = if (entry.replaceManual) tx.copy(classificationIsManual = false) else tx
            candidates += CategoryCandidate(candidateTx, AiCategorization.fingerprint(candidateTx, snapshot))
        }

        // 2. Automatic runs (only if autoCategorize is true and nonRetryableError is null)
        if (snapshot.autoCategorize && nonRetryableError == null && candidates.size < AiCategorization.MAX_BATCH) {
            val selected = _selectedMonth.value
            // The plan may predate a batch that has since been saved; a changed category means "re-plan", not "re-send".
            val fresh = analysisSnapshot.value
            val pendingAutomatic = plan.needing.filter { tx ->
                tx.key !in inFlightKeys &&
                    fresh.updatedAt["cat:${tx.key}"] == snapshot.updatedAt["cat:${tx.key}"] &&
                    tx.key !in snapshot.categoryFailures &&
                    tx.key !in backedOffKeys
            }

            val inSelectedMonth = pendingAutomatic.filter { tx ->
                YearMonth.from(tx.occurredAt.atZone(AccountingTime.zone)) == selected
            }.sortedByDescending { it.occurredAt }

            val inOtherMonths = pendingAutomatic.filter { tx ->
                YearMonth.from(tx.occurredAt.atZone(AccountingTime.zone)) != selected
            }.sortedByDescending { it.occurredAt }

            val ordered = inSelectedMonth + inOtherMonths

            for (tx in ordered) {
                if (candidates.size >= AiCategorization.MAX_BATCH) break
                if (candidates.any { it.transaction.key == tx.key }) continue
                candidates += CategoryCandidate(tx, AiCategorization.fingerprint(tx, snapshot))
            }
        }

        if (candidates.isEmpty()) return emptyList()
        return AiCategorization.batchOf(candidates, snapshot)
    }

    private fun launchCategoryBatch(
        batch: List<CategoryCandidate>,
        config: AiConfig,
        snapshot: AnalysisSnapshot,
    ) {
        val batchKeys = batch.map { it.transaction.key }.toSet()
        inFlightKeys.addAll(batchKeys)
        manualQueue.removeAll { it.key in batchKeys }

        val job = viewModelScope.launch {
            try {
                updateCategoryProgress(analysisSnapshot.value)
                val suggestions = withContext(computationDispatcher) {
                    categorize(config, snapshot, batch)
                }
                ensureActive()

                val replaceKeys = batch.map { it.transaction.key }.filter { it in replaceManualKeys }.toSet()
                if (replaceKeys.isNotEmpty()) {
                    withContext(ioDispatcher) {
                        for (key in replaceKeys) {
                            replaceManualKeys.remove(key)
                            analysisStore?.removeManualCategory(key)
                            val suggestion = suggestions[key]
                            if (suggestion != null) {
                                analysisStore?.setClassification(key, suggestion.category, suggestion.reason, manual = false)
                            }
                        }
                    }
                }

                val standardBatch = batch.filter { it.transaction.key !in replaceKeys }
                if (standardBatch.isNotEmpty()) {
                    val currentRows = _reconciledCache.value.associateBy { it.key }
                    val currentSnapshot = analysisSnapshot.value
                    val unchanged = standardBatch.filter { candidate ->
                        currentRows[candidate.transaction.key]?.let { current ->
                            AiCategorization.fingerprint(current, currentSnapshot) == candidate.fingerprint
                        } == true
                    }
                    withContext(ioDispatcher) {
                        analysisStore?.commitCategorySuggestions(unchanged, suggestions, currentSnapshot)
                    }
                }

                val batchKey = batch.map { it.transaction.key }.sorted().joinToString(":")
                batchAttempts.remove(batchKey)
                backedOffKeys.removeAll(batchKeys)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (isNonRetryableHttp(e)) {
                    nonRetryableError = aiConnectionFailure(e)
                    for ((otherJob, _) in activeBatchJobs.toList()) {
                        if (otherJob !== coroutineContext[Job]) otherJob.cancel()
                    }
                } else {
                    val batchKey = batch.map { it.transaction.key }.sorted().joinToString(":")
                    val attempt = (batchAttempts[batchKey] ?: 0) + 1
                    batchAttempts[batchKey] = attempt
                    val waitDelay = categoryBackoff.getOrNull(attempt - 1)
                    if (waitDelay != null) {
                        backedOffKeys.addAll(batchKeys)
                        val backoffJob = viewModelScope.launch {
                            delay(waitDelay)
                            backedOffKeys.removeAll(batchKeys)
                            if (categorizationJob?.isActive == true) {
                                triggerPump()
                            }
                        }
                        for (k in batchKeys) {
                            backoffJobs[k]?.cancel()
                            backoffJobs[k] = backoffJob
                        }
                    } else {
                        val msg = aiConnectionFailure(e)
                        withContext(ioDispatcher) {
                            analysisStore?.recordCategoryFailures(batchKeys, msg)
                        }
                        backedOffKeys.removeAll(batchKeys)
                    }
                }
            } finally {
                inFlightKeys.removeAll(batchKeys)
                activeBatchJobs.remove(coroutineContext[Job])
                if (categorizationJob?.isActive == true) {
                    triggerPump()
                }
            }
        }
        activeBatchJobs[job] = batchKeys
    }

    private fun updateCategoryProgress(snapshot: AnalysisSnapshot) {
        val config = snapshot.aiConfig
        val configured = snapshot.analysisEnabled && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isBlank().not()
        if (!configured) {
            val progress = CategoryProgress(
                state = CategoryAutomationState.NOT_CONFIGURED,
                paused = !snapshot.autoCategorize,
            )
            _categoryProgress.value = progress
            _categoryAutomation.value = CategoryAutomationState.NOT_CONFIGURED
            return
        }

        val plan = lastPlan ?: return
        val total = plan.total
        val labeled = total - plan.needing.size
        val reviewCount = plan.review
        val failedCount = plan.failed

        if (nonRetryableError != null) {
            val progress = CategoryProgress(
                state = CategoryAutomationState.ERROR,
                labeled = labeled,
                total = total,
                error = nonRetryableError,
                review = reviewCount,
                failed = failedCount,
                paused = !snapshot.autoCategorize,
                activeKeys = inFlightKeys.toSet(),
            )
            _categoryProgress.value = progress
            _categoryAutomation.value = CategoryAutomationState.ERROR
            return
        }

        val inFlight = inFlightKeys.isNotEmpty()
        val manualQueued = manualQueue.any { it.key !in inFlightKeys }
        val automaticQueued = snapshot.autoCategorize && nonRetryableError == null && plan.needing.any { it.key !in snapshot.categoryFailures }

        val isWorking = inFlight || manualQueued || automaticQueued
        val state = if (isWorking) CategoryAutomationState.WORKING else CategoryAutomationState.READY

        val progress = CategoryProgress(
            state = state,
            labeled = labeled,
            total = total,
            error = null,
            review = reviewCount,
            failed = failedCount,
            paused = !snapshot.autoCategorize,
            activeKeys = inFlightKeys.toSet(),
        )
        _categoryProgress.value = progress
        _categoryAutomation.value = state
    }

    /** Runs AI categorization for [scope] now, ahead of the automatic queue and regardless of failure back-off. */
    fun recategorize(scope: RecategorizeScope) {
        val rows = _reconciledCache.value
        val snapshot = analysisSnapshot.value
        val keys = scopeKeysAction(scope, rows, snapshot)
        if (keys.isEmpty()) return

        viewModelScope.launch(ioDispatcher) {
            analysisStore?.clearCategoryFailures(keys)
        }
        backedOffKeys.removeAll(keys)
        for (k in keys) {
            backoffJobs.remove(k)?.cancel()
            batchAttempts.remove(k)
        }

        val replaceManual = (scope as? RecategorizeScope.Payment)?.replaceManual == true
        if (replaceManual) {
            replaceManualKeys.addAll(keys)
        }

        val entries = keys.filter { it !in inFlightKeys }.map { ManualQueueEntry(it, replaceManual) }
        val entryKeys = entries.map { it.key }.toSet()
        manualQueue.removeAll { it.key in entryKeys }
        manualQueue.addAll(0, entries)

        observeAutomaticCategories()
        triggerPump()
    }

    /** Payments a [recategorize] run for [scope] would touch, for confirmation dialogs. */
    fun recategorizeCount(scope: RecategorizeScope): Int =
        scopeKeysAction(scope, _reconciledCache.value, analysisSnapshot.value).size
    /** Cancels in-flight and queued categorization; those payments return to their previous state. */
    fun stopCategorization() {}
    /** Cancels a manual analysis that is waiting for categorization to finish. */
    fun cancelPendingAnalysis() {}
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
                backedOffKeys.remove(transaction.key)
                backoffJobs.remove(transaction.key)?.cancel()
                batchAttempts.remove(transaction.key)
                triggerPump()
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
                BudgetAnalyzer.isSpending(peer) &&
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
                BudgetAnalyzer.isSpending(peer) &&
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
                val peerKeys = peers.map { it.key }
                backedOffKeys.removeAll(peerKeys)
                for (k in peerKeys) {
                    backoffJobs.remove(k)?.cancel()
                    batchAttempts.remove(k)
                }
                triggerPump()
            }
            onComplete(result)
        }
    }

    fun retryAutomaticCategories() {
        nonRetryableError = null
        backedOffKeys.clear()
        for ((_, job) in backoffJobs) job.cancel()
        backoffJobs.clear()
        batchAttempts.clear()
        val failures = analysisSnapshot.value.categoryFailures.keys
        if (failures.isNotEmpty()) {
            viewModelScope.launch(ioDispatcher) {
                analysisStore?.clearCategoryFailures(failures)
            }
        }
        triggerPump()
        observeAutomaticCategories()
    }

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
        // Every app foreground triggers a sync; per-provider staleness and rate-limit windows
        // inside the importer decide whether each source actually refreshes.
        importHistory(null, automatic = true)
    }

    fun refresh() { importHistory(null) }
    fun syncProvider(provider: Provider) { importHistory(provider) }
    /** Gojek needs its visible history walked first; the host decides how (root UI automation). */
    fun requestGojekWalk() { (gojekHistoryWalk ?: { syncProvider(Provider.GOJEK) }).invoke() }
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
            ingestionJob?.cancel(); _rootGate.value = RootGateState.LOCKED
        }
    }
    fun lock() {
        stopForegroundImports(); observerJob?.cancel(); categorizationJob?.cancel(); monthJobs.values.forEach(Job::cancel); monthJobs.clear()
        for ((job, _) in activeBatchJobs.toList()) job.cancel()
        activeBatchJobs.clear(); inFlightKeys.clear(); backedOffKeys.clear()
        for ((_, job) in backoffJobs) job.cancel(); backoffJobs.clear()
        manualQueue.clear()
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
    fun addCustomCategory(category: String, onComplete: (Result<Unit>) -> Unit = {}) =
        storeWrite("Categories unavailable", onComplete) { addCustomCategory(category) }
    fun removeCustomCategory(category: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.removeCustomCategory(category) } }
    fun resetCategoriesToDefault() { viewModelScope.launch(Dispatchers.IO) { analysisStore?.resetCategoriesToDefault() } }
    fun setCategoryIcon(category: String, iconKey: String?) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setCategoryIcon(category, iconKey) } }
    fun setMonthlyNote(month: String, note: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setMonthlyNote(month, note) } }

    internal fun fingerprint(month: YearMonth, rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): String =
        BudgetAnalyzer.computeMonthFingerprint(
            rows.filter {
                YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) <= month &&
                    BudgetAnalyzer.isSpending(it)
            },
            snapshot.monthlyNotes[month.toString()].orEmpty(),
            snapshot.knowledge + "\nresearch-v7\n${snapshot.aiConfig.baseUrl}\n${snapshot.aiConfig.model}",
            snapshot.customCategories,
        )

    fun analyzeMonth(month: YearMonth, forceReanalyze: Boolean = false, debounceMs: Long = 600, allowRetry: Boolean = true) {
        if (!analysisSnapshot.value.analysisEnabled) return
        if ((_refreshing.value || _categoryAutomation.value == CategoryAutomationState.WORKING) && !forceReanalyze) return
        val config = analysisSnapshot.value.aiConfig
        if (analysisStore == null || config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) return
        monthJobs[month]?.cancel()
        val key = analysisKey(month)
        val job = viewModelScope.launch {
            var shouldRetry = false
            try {
                if (debounceMs > 0) delay(debounceMs)
                val snapshot = analysisSnapshot.value
                val rows = _reconciledCache.value
                if (withContext(computationDispatcher) { rows.none { YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) <= month && BudgetAnalyzer.isSpending(it) } }) return@launch
                val fp = withContext(computationDispatcher) { fingerprint(month, rows, snapshot) }
                val failure = analysisFailures.value[key]
                if (!forceReanalyze && (failure?.fingerprint == fp || snapshot.analysisFingerprints[key] == fp)) return@launch
                _analyzingMonths.update { it + month }; _analysisErrors.update { it - month }
                try {
                    val result = analyzeMonthAction(snapshot.aiConfig, snapshot.knowledge, month.toString(), rows,
                        snapshot.customCategories, snapshot.monthlyNotes[month.toString()].orEmpty()) { stage ->
                        _analysisStages.update { it + (month to stage) }
                    }
                    ensureActive()
                    if (withContext(computationDispatcher) { fingerprint(month, _reconciledCache.value, analysisSnapshot.value) } != fp) {
                        if (allowRetry) shouldRetry = true
                        return@launch
                    }
                    _analysisStages.update { it + (month to AnalysisStage.SAVING) }
                    val committed = withContext(Dispatchers.IO) { analysisStore.commitMonthSummary(key, result.monthlySummary, fp, snapshot) }
                    if (!committed) {
                        if (allowRetry) shouldRetry = true
                        return@launch
                    }
                    clearAnalysisFailure(key)
                } catch (timeout: TimeoutCancellationException) {
                    val msg = "Analysis timed out. Try again"
                    recordAnalysisFailure(key, msg, fp)
                    _analysisErrors.update { it + (month to msg) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    val msg = aiConnectionFailure(e)
                    recordAnalysisFailure(key, msg, fp)
                    _analysisErrors.update { it + (month to msg) }
                }
            } finally {
                if (monthJobs[month] === coroutineContext[Job]) {
                    monthJobs.remove(month); _analyzingMonths.update { it - month }; _analysisStages.update { it - month }
                }
                if (shouldRetry) {
                    analyzeMonth(month, forceReanalyze = false, allowRetry = false)
                }
            }
        }
        monthJobs[month] = job
    }

    fun analyzeCurrentMonth() { analyzeMonth(_selectedMonth.value, forceReanalyze = true) }
}

private data class AnalysisStatusData(
    val rows: List<UnifiedTransaction>,
    val month: YearMonth,
    val snapshot: AnalysisSnapshot,
)

private data class AnalysisStatusExecution(
    val running: Set<YearMonth>,
    val stages: Map<YearMonth, AnalysisStage>,
    val failures: Map<String, AnalysisFailure>,
)

