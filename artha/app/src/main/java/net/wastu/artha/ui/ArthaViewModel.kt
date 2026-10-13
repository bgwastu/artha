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
) : ViewModel() {
    private val _rootGate = MutableStateFlow(RootGateState.LOCKED)
    val rootGate = _rootGate.asStateFlow()
    private val _ledgerState = MutableStateFlow<LedgerState>(LedgerState.Loading)
    val ledgerState = _ledgerState.asStateFlow()
    private val _providerCounts = MutableStateFlow<Map<Provider, Int>>(emptyMap())
    val providerCounts = _providerCounts.asStateFlow()
    val analysisSnapshot = analysisStore?.snapshot ?: MutableStateFlow(AnalysisSnapshot()).asStateFlow()
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
    private val monthJobs = mutableMapOf<YearMonth, Job>()
    private val failedFingerprints = mutableMapOf<YearMonth, String>()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _ingestionStatus = MutableStateFlow<Map<Provider, String>>(emptyMap())
    val ingestionStatus = _ingestionStatus.asStateFlow()
    val enabledProviders = preferences?.enabled ?: MutableStateFlow(Provider.entries.toSet()).asStateFlow()
    val importEnabled = preferences?.importEnabled ?: MutableStateFlow(Provider.entries.toSet()).asStateFlow()
    val providerDiagnostics = preferences?.diagnostics ?: MutableStateFlow(emptyMap<Provider, net.wastu.artha.data.providers.ProviderDiagnostic>()).asStateFlow()
    val driveSyncState = driveSyncManager?.syncState ?: MutableStateFlow(net.wastu.artha.data.sync.DriveSyncState()).asStateFlow()
    private var observerJob: Job? = null
    private var ingestionJob: Job? = null
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
        if (observerJob?.isActive != true) {
            observerJob = viewModelScope.launch {
                val state = spendingState ?: spendingFlow(ledger(), analysisSnapshot, computationDispatcher,
                    migrate = { analysisStore?.migrateMetadata(it) }).stateIn(this, SharingStarted.Eagerly, SpendingState.Loading)
                launch {
                    combine(state, enabledProviders, _dateRangeFilter, _categoryFilter) { projection, enabled, dates, category ->
                        when (projection) {
                            SpendingState.Loading -> LedgerState.Loading
                            SpendingState.Failed -> LedgerState.Failed
                            is SpendingState.Ready -> {
                                _providerCounts.value = projection.providerCounts
                                _reconciledCache.value = projection.transactions
                                val (start, end) = dates ?: (null to null)
                                LedgerState.Ready(projection.transactions.filter { tx ->
                                    val millis = tx.occurredAt.toEpochMilli()
                                    (category != null || tx.provider in enabled) && (category == null || tx.category == category && BudgetAnalyzer.isSpending(tx)) && (start == null || millis >= start) &&
                                        (end == null || millis < AccountingTime.dayAfter(end))
                                })
                            }
                        }
                    }.flowOn(computationDispatcher).collect { _ledgerState.value = it }
                }
                launch(computationDispatcher) {
                    combine(_reconciledCache, _selectedMonth, analysisSnapshot.map { it.monthlySummaries }.distinctUntilChanged()) { rows, month, summaries ->
                        BudgetAnalyzer.analyzeMonth(month, rows, summaries[month.toString()])
                    }.collect { if (it.month == _selectedMonth.value) _monthAnalysis.value = it }
                }
                launch {
                    combine(_reconciledCache, _selectedMonth, analysisSnapshot, _refreshing) { rows, month, snapshot, importing ->
                        if (importing) null else month to fingerprint(month, rows, snapshot)
                    }.flowOn(computationDispatcher).distinctUntilChanged().debounce(2000).collect { input ->
                        input?.let { (month, _) -> analyzeMonth(month, debounceMs = 0) }
                    }
                }
            }
        }
        automaticRefresh()
        analyzeMonth(_selectedMonth.value)
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
        if (ingestionJob?.isActive == true) return
        _refreshing.value = true
        ingestionJob = viewModelScope.launch {
            try {
                _rootGate.value = RootGateState.CHECKING
                val granted = try { rootAccess.verify() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                _rootGate.value = if (granted) RootGateState.GRANTED else RootGateState.DENIED
                if (!granted) return@launch
                val result = if (provider != null && syncSingle != null) mapOf(provider to syncSingle.invoke(provider))
                    else if (automatic && ingestStale != null) ingestStale.invoke() else ingest?.invoke().orEmpty()
                _ingestionStatus.value = _ingestionStatus.value + result
                if (result.isNotEmpty() && driveSyncState.value.isEnabled) driveSyncManager?.syncNow()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { _ingestionStatus.value = _ingestionStatus.value + ((provider ?: Provider.JAGO) to (e.message ?: "Import unavailable")) }
            finally { _refreshing.value = false }
        }
    }

    fun stopForegroundImports() {
        if (ingestionJob?.isActive == true) {
            ingestionJob?.cancel(); lastAutomaticRefresh = Long.MIN_VALUE; _rootGate.value = RootGateState.LOCKED
        }
    }
    fun lock() {
        stopForegroundImports(); observerJob?.cancel(); monthJobs.values.forEach(Job::cancel); monthJobs.clear()
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
    fun setUserNote(transaction: UnifiedTransaction, note: String) {
        viewModelScope.launch(Dispatchers.IO) { analysisStore?.setUserNote(transaction.key, note) }
    }
    fun setTransactionCategory(transaction: UnifiedTransaction, category: String) {
        viewModelScope.launch(Dispatchers.IO) { analysisStore?.setClassification(transaction.key, category, "", manual = true) }
    }
    fun applyKnowledgeRewrite(rewritten: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setKnowledge(rewritten) } }
    fun updateAiConfig(config: AiConfig) { viewModelScope.launch(Dispatchers.IO) {
        try { analysisStore?.setAiConfig(config); _settingsError.value = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { _settingsError.value = e.message ?: "AI settings could not be saved securely." }
    } }
    fun addCustomCategory(category: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.addCustomCategory(category) } }
    fun removeCustomCategory(category: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.removeCustomCategory(category) } }
    fun resetCategoriesToDefault() { viewModelScope.launch(Dispatchers.IO) { analysisStore?.resetCategoriesToDefault() } }
    fun setCategoryIcon(category: String, iconKey: String?) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setCategoryIcon(category, iconKey) } }
    fun setMonthlyNote(month: String, note: String) { viewModelScope.launch(Dispatchers.IO) { analysisStore?.setMonthlyNote(month, note) } }

    private fun monthTransactions(month: YearMonth, rows: List<UnifiedTransaction>) = rows.filter {
        YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) == month && BudgetAnalyzer.isSpending(it)
    }
    private fun fingerprint(month: YearMonth, rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): String =
        BudgetAnalyzer.computeMonthFingerprint(rows.filter {
            YearMonth.from(it.occurredAt.atZone(AccountingTime.zone)) in setOf(month, month.minusMonths(1)) &&
                (BudgetAnalyzer.isSpending(it) || !it.isConfirmed && it.direction == Direction.DEBIT && it.money.currencyCode == "IDR")
        }, snapshot.monthlyNotes[month.toString()].orEmpty(),
            snapshot.knowledge + "\n${snapshot.aiConfig.baseUrl}\n${snapshot.aiConfig.model}\n${snapshot.aiConfig.apiKey}\n${if (month == YearMonth.now(clock.withZone(AccountingTime.zone))) java.time.LocalDate.now(clock.withZone(AccountingTime.zone)) else month}", snapshot.customCategories)

    fun analyzeMonth(month: YearMonth, forceReanalyze: Boolean = false, debounceMs: Long = 600) {
        if (_refreshing.value && !forceReanalyze) return
        val config = analysisSnapshot.value.aiConfig
        if (analysisStore == null || config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) return
        monthJobs[month]?.cancel()
        val job = viewModelScope.launch {
            try {
                if (debounceMs > 0) delay(debounceMs)
                val snapshot = analysisSnapshot.value
                val rows = _reconciledCache.value
                if (monthTransactions(month, rows).isEmpty()) return@launch
                val fp = withContext(computationDispatcher) { fingerprint(month, rows, snapshot) }
                if (!forceReanalyze && (failedFingerprints[month] == fp || snapshot.analysisFingerprints[month.toString()] == fp)) return@launch
                _analyzingMonths.update { it + month }; _analysisErrors.update { it - month }
                try {
                    val result = AiAnalysisService.analyzeMonth(snapshot.aiConfig, snapshot.knowledge, month.toString(), rows,
                        snapshot.customCategories, snapshot.monthlyNotes[month.toString()].orEmpty())
                    ensureActive()
                    if (withContext(computationDispatcher) { fingerprint(month, _reconciledCache.value, analysisSnapshot.value) } != fp) return@launch
                    val committed = withContext(Dispatchers.IO) { analysisStore.commitMonthSummary(month.toString(), result.monthlySummary, fp, snapshot) }
                    if (committed) failedFingerprints.remove(month)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    failedFingerprints[month] = fp
                    _analysisErrors.update { it + (month to (e.message ?: "AI commentary unavailable; local insights are current.")) }
                }
            } finally {
                if (monthJobs[month] === coroutineContext[Job]) {
                    monthJobs.remove(month); _analyzingMonths.update { it - month }
                }
            }
        }
        monthJobs[month] = job
    }
    fun analyzeCurrentMonth() { analyzeMonth(_selectedMonth.value, forceReanalyze = true) }
}
