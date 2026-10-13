package net.wastu.artha.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.core.root.RootGateState
import net.wastu.artha.data.ledger.LedgerRepository
import java.time.Clock
import java.util.UUID
import java.util.Locale
import java.time.YearMonth
import java.time.ZoneId
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.analysis.AnalysisSnapshot
import net.wastu.artha.data.analysis.AiConfig
import net.wastu.artha.data.analysis.AiAnalysisService
import net.wastu.artha.data.analysis.CategoryClassifier
import net.wastu.artha.data.analysis.CategoryConstants
import net.wastu.artha.data.analysis.FeedbackResult
import net.wastu.artha.data.reconciliation.LedgerReconciler
import net.wastu.artha.data.analysis.BudgetAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface LedgerState {
    data object Loading : LedgerState
    data class Ready(val transactions: List<UnifiedTransaction>) : LedgerState
    data object Failed : LedgerState
}

sealed interface EntryState {
    data object Idle : EntryState
    data object Saving : EntryState
    data object Saved : EntryState
    data object Failed : EntryState
}

data class CategorySpending(
    val category: String,
    val amountMinor: Long,
    val sharePercent: Float,
    val previousMonthAmountMinor: Long,
    val deltaPercent: Float?,
    val deltaAmountMinor: Long,
    val transactions: List<UnifiedTransaction>,
)

data class MonthSpendingAnalysis(
    val month: YearMonth,
    val totalPersonalSpendingMinor: Long,
    val previousMonthTotalMinor: Long,
    val totalDeltaPercent: Float?,
    val categories: List<CategorySpending>,
    val monthlyAiSummary: String?,
)

class ArthaViewModel(
    private val rootAccess: RootAccess,
    private val ledger: () -> LedgerRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val ingest: (suspend () -> Map<Provider, String>)? = null,
    private val syncSingle: (suspend (Provider) -> String)? = null,
    private val preferences: net.wastu.artha.data.providers.ProviderPreferences? = null,
    val syncingProvider: kotlinx.coroutines.flow.StateFlow<Provider?> = MutableStateFlow<Provider?>(null).asStateFlow(),
    val analysisStore: AnalysisStore? = null,
    val driveSyncManager: net.wastu.artha.data.sync.DriveSyncManager? = null,
    private val computationDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _rootGate = MutableStateFlow(RootGateState.LOCKED)
    val rootGate = _rootGate.asStateFlow()
    private val _ledgerState = MutableStateFlow<LedgerState>(LedgerState.Loading)
    val ledgerState = _ledgerState.asStateFlow()
    private val _providerCounts = MutableStateFlow<Map<Provider, Int>>(emptyMap())
    val providerCounts = _providerCounts.asStateFlow()
    private val _entryState = MutableStateFlow<EntryState>(EntryState.Idle)
    val entryState = _entryState.asStateFlow()

    val analysisSnapshot = analysisStore?.snapshot ?: MutableStateFlow(AnalysisSnapshot()).asStateFlow()
    private val _selectedMonth = MutableStateFlow(YearMonth.now(clock))
    val selectedMonth = _selectedMonth.asStateFlow()
    private val _dateRangeFilter = MutableStateFlow<Pair<Long?, Long?>?>(null)
    val dateRangeFilter = _dateRangeFilter.asStateFlow()
    private val _analyzingMonths = MutableStateFlow<Set<YearMonth>>(emptySet())
    val analyzingMonths = _analyzingMonths.asStateFlow()
    val analyzingAi = _analyzingMonths.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val monthAnalysisJobs = ConcurrentHashMap<YearMonth, Job>()
    private val monthDebounceJobs = ConcurrentHashMap<YearMonth, Job>()
    private val _targetDestination = MutableStateFlow<String?>(null)
    val targetDestination = _targetDestination.asStateFlow()
    fun setTargetDestination(dest: String?) { _targetDestination.value = dest }
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _monthAnalysis = MutableStateFlow(BudgetAnalyzer.analyzeMonth(YearMonth.now(clock), emptyList(), null))
    private val _reconciledCache = MutableStateFlow<List<UnifiedTransaction>>(emptyList())
    val monthAnalysis = _monthAnalysis.asStateFlow()

    val enabledProviders = preferences?.enabled ?: MutableStateFlow(Provider.entries.toSet()).asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _ingestionStatus = MutableStateFlow<Map<Provider, String>>(emptyMap())
    val ingestionStatus = _ingestionStatus.asStateFlow()
    private var ingestionJob: Job? = null

    fun setProviderEnabled(provider: Provider, enabled: Boolean) {
        if (_rootGate.value == RootGateState.GRANTED) preferences?.setEnabled(provider, enabled)
    }



    val providerDiagnostics = preferences?.diagnostics ?: MutableStateFlow(emptyMap<Provider, net.wastu.artha.data.providers.ProviderDiagnostic>()).asStateFlow()
    val driveSyncState = driveSyncManager?.syncState ?: MutableStateFlow(net.wastu.artha.data.sync.DriveSyncState()).asStateFlow()

    fun setDriveSyncEnabled(enabled: Boolean) { driveSyncManager?.setEnabled(enabled) }
    fun setDriveAccountEmail(email: String?) { driveSyncManager?.setAccountEmail(email) }
    fun setDriveEncryptionPassword(password: String) { driveSyncManager?.setEncryptionPassword(password) }
    fun syncDriveNow() {
        viewModelScope.launch { driveSyncManager?.syncNow() }
    }

    fun restoreDriveBackup() {
        viewModelScope.launch { driveSyncManager?.restoreNow() }
    }

    fun syncProvider(provider: Provider) {
        if (_rootGate.value != RootGateState.GRANTED || ingestionJob?.isActive == true) return
        val currentSession = session
        ingestionJob = viewModelScope.launch {
            try {
                if (!rootAccess.verify()) {
                    lock()
                    _rootGate.value = RootGateState.DENIED
                    return@launch
                }
                if (syncSingle != null) {
                    val status = syncSingle.invoke(provider)
                    if (currentSession == session) {
                        _ingestionStatus.value = _ingestionStatus.value + (provider to status)
                    }
                } else if (ingest != null) {
                    val status = ingest.invoke()
                    if (currentSession == session) _ingestionStatus.value = status
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    fun refresh() {
        if (ingest == null || _rootGate.value != RootGateState.GRANTED || ingestionJob?.isActive == true) return
        val currentSession = session
        _refreshing.value = true
        ingestionJob = viewModelScope.launch {
            try {
                if (!rootAccess.verify()) {
                    lock()
                    _rootGate.value = RootGateState.DENIED
                    return@launch
                }
                val status = ingest.invoke()
                if (currentSession == session) _ingestionStatus.value = status
                if (!rootAccess.verify() && currentSession == session) {
                    lock()
                    _rootGate.value = RootGateState.DENIED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentSession == session) {
                    if (!rootAccess.verify()) {
                        lock()
                        _rootGate.value = RootGateState.DENIED
                    } else _ingestionStatus.value = Provider.entries.associateWith { "Unavailable" }
                }
            } finally {
                if (currentSession == session) _refreshing.value = false
            }
        }
        if (driveSyncManager?.syncState?.value?.isEnabled == true) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { driveSyncManager.syncNow() }
            }
        }
    }

    private var sessionJob: Job? = null
    private var appendJob: Job? = null
    private var session = 0L

    fun enterForeground() {
        if (_rootGate.value == RootGateState.GRANTED && sessionJob?.isActive == true) {
            refresh()
            return
        }
        lock()
        val currentSession = session
        _rootGate.value = RootGateState.CHECKING
        sessionJob = viewModelScope.launch {
            try {
                if (!rootAccess.verify()) {
                    if (currentSession == session) _rootGate.value = RootGateState.DENIED
                    return@launch
                }
                if (currentSession != session) return@launch
                _rootGate.value = RootGateState.GRANTED
                launch(Dispatchers.Default) {
                    combine(_reconciledCache, _selectedMonth, analysisSnapshot) { reconciledAll, month, analysis ->
                        val monthKey = month.toString()
                        val summary = analysis.monthlySummaries[monthKey]
                        BudgetAnalyzer.analyzeMonth(month, reconciledAll, summary)
                    }.collect { result ->
                        _monthAnalysis.value = result
                        val month = result.month
                        val monthKey = month.toString()
                        if (result.categories.isNotEmpty() && month !in _analyzingMonths.value) {
                            val snap = analysisSnapshot.value
                            val allTxs = _reconciledCache.value
                            val zone = ZoneId.systemDefault()
                            val monthTxs = allTxs.filter {
                                it.direction == Direction.DEBIT && YearMonth.from(it.occurredAt.atZone(zone)) == month
                            }
                            val currentFp = BudgetAnalyzer.computeMonthFingerprint(
                                monthTransactions = monthTxs,
                                monthlyNote = snap.monthlyNotes[monthKey].orEmpty(),
                                knowledge = snap.knowledge,
                                customCategories = snap.customCategories,
                            )
                            val lastFp = snap.analysisFingerprints[monthKey]
                            if (lastFp == null || lastFp != currentFp || snap.monthlySummaries[monthKey] == null) {
                                analyzeMonth(month)
                            }
                        }
                    }
                }

                var initialLoaded = false
                combine(
                    ledger().observeTransactions(),
                    enabledProviders,
                    analysisSnapshot,
                    _dateRangeFilter,
                ) { rawTransactions, enabled, analysis, dateRange ->
                    _providerCounts.value = rawTransactions.groupingBy { it.provider }.eachCount()
                    val enriched = rawTransactions.map { tx ->
                        val id = tx.providerTransactionId.substringAfter(":")
                        val note = analysis.userNotes[id] ?: tx.userNote
                        val isCredit = tx.direction == Direction.CREDIT
                        val classification = if (isCredit) null else CategoryClassifier.classify(tx, note)
                        val cat = if (isCredit) null else (analysis.categories[id] ?: classification?.category)
                        val reason = if (isCredit) null else (analysis.reasons[id] ?: classification?.reason)
                        tx.copy(
                            userNote = note,
                            category = cat,
                            aiReasoning = reason,
                            isReversal = tx.rawPayload.contains("\"isReversal\":true") || tx.description.contains("(Reversed)"),
                        )
                    }
                    val reconciledAll = LedgerReconciler.reconcileLedger(enriched)
                    _reconciledCache.value = reconciledAll
                    val filteredByProvider = reconciledAll.filter { it.provider in enabled }
                    if (dateRange != null) {
                        val (start, end) = dateRange
                        filteredByProvider.filter { tx ->
                            val epoch = tx.occurredAt.toEpochMilli()
                            (start == null || epoch >= start) && (end == null || epoch <= end + 86_399_999L)
                        }
                    } else {
                        filteredByProvider
                    }
                }.flowOn(computationDispatcher).collect { transactions ->
                    _ledgerState.value = LedgerState.Ready(transactions)
                    if (!initialLoaded) {
                        initialLoaded = true
                        refresh()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentSession != session) return@launch
                if (_rootGate.value == RootGateState.GRANTED) {
                    _ledgerState.value = LedgerState.Failed
                } else {
                    _rootGate.value = RootGateState.DENIED
                }
            }
        }
    }

    /** Called synchronously on backgrounding; no financial state stays on screen. */
    fun lock() {
        session++
        sessionJob?.cancel()
        appendJob?.cancel()
        ingestionJob?.cancel()
        _refreshing.value = false
        _ingestionStatus.value = emptyMap()
        _rootGate.value = RootGateState.LOCKED
        _ledgerState.value = LedgerState.Loading
        _entryState.value = EntryState.Idle
    }

    fun resetEntry() {
        if (_entryState.value != EntryState.Saving) _entryState.value = EntryState.Idle
    }

    fun appendManual(provider: Provider, direction: Direction, money: Money, description: String) {
        if (_rootGate.value != RootGateState.GRANTED || _entryState.value == EntryState.Saving) return
        if (description.isBlank()) return
        val currentSession = session
        _entryState.value = EntryState.Saving
        appendJob = viewModelScope.launch {
            try {
                if (!rootAccess.verify()) {
                    lock()
                    _rootGate.value = RootGateState.DENIED
                    return@launch
                }
                if (currentSession != session) return@launch
                val now = clock.instant()
                val transaction = UnifiedTransaction(
                    provider = provider,
                    providerTransactionId = "manual:${UUID.randomUUID()}",
                    direction = direction,
                    money = money,
                    occurredAt = now,
                    description = description.trim(),
                    rawPayload = JSONObject()
                        .put("origin", "manual")
                        .put("provider", provider.name)
                        .put("direction", direction.name)
                        .put("amountMinor", money.amountMinor)
                        .put("currencyCode", money.currencyCode)
                        .put("occurredAt", now.toString())
                        .put("description", description.trim())
                        .toString(),
                )
                ledger().append(listOf(transaction))
                if (currentSession == session) _entryState.value = EntryState.Saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentSession == session) _entryState.value = EntryState.Failed
            }
        }
    }
    fun setAnalysisMonth(month: YearMonth) {
        val currentMax = YearMonth.now(clock)
        if (month <= currentMax) {
            _selectedMonth.value = month
        }
    }
    fun setDateRangeFilter(startMillis: Long?, endMillis: Long?) {
        _dateRangeFilter.value = if (startMillis == null && endMillis == null) null else (startMillis to endMillis)
    }
    fun clearDateRangeFilter() { _dateRangeFilter.value = null }
    fun setUserNote(transactionId: String, note: String) {
        val cleanId = transactionId.substringAfter(":")
        val currentTxs = _reconciledCache.value
        val tx = currentTxs.firstOrNull { it.providerTransactionId.endsWith(cleanId) }

        val updatedTxs = currentTxs.map {
            if (it.providerTransactionId.endsWith(cleanId)) {
                it.copy(userNote = note)
            } else it
        }
        _reconciledCache.value = updatedTxs
        analysisStore?.setUserNote(transactionId, note)

        if (tx != null && tx.direction == Direction.DEBIT) {
            val txMonth = YearMonth.from(tx.occurredAt.atZone(ZoneId.systemDefault()))
            analyzeMonth(txMonth, forceReanalyze = true, debounceMs = 800L)
        }
    }

    fun setUserNote(transaction: UnifiedTransaction, note: String) {
        setUserNote(transaction.providerTransactionId, note)
    }

    fun applyKnowledgeRewrite(rewritten: String) {
        analysisStore?.setKnowledge(rewritten)
        analyzeCurrentMonth()
    }

    fun updateAiConfig(config: AiConfig) { analysisStore?.setAiConfig(config) }
    fun addCustomCategory(category: String) { analysisStore?.addCustomCategory(category) }
    fun removeCustomCategory(category: String) { analysisStore?.removeCustomCategory(category) }
    fun resetCategoriesToDefault() { analysisStore?.resetCategoriesToDefault() }

    fun setTransactionCategory(transactionId: String, category: String) {
        val cleanId = transactionId.substringAfter(":")
        // 1. Immediately update in-memory reconciled cache on frame 0
        val currentTxs = _reconciledCache.value
        val updatedTxs = currentTxs.map { tx ->
            if (tx.providerTransactionId.endsWith(cleanId)) {
                tx.copy(category = category)
            } else tx
        }
        _reconciledCache.value = updatedTxs

        // 2. Immediately recompute month analysis on frame 0
        val month = _selectedMonth.value
        val summary = analysisSnapshot.value.monthlySummaries[month.toString()]
        _monthAnalysis.value = BudgetAnalyzer.analyzeMonth(month, updatedTxs, summary)

        // 3. Persist to store asynchronously
        analysisStore?.setClassification(transactionId, category, "")

        // 4. Trigger debounced AI analysis to update month summary
        val tx = updatedTxs.firstOrNull { it.providerTransactionId.endsWith(cleanId) }
        if (tx != null) {
            val txMonth = YearMonth.from(tx.occurredAt.atZone(ZoneId.systemDefault()))
            analyzeMonth(txMonth, forceReanalyze = true, debounceMs = 600L)
        }
    }

    fun setCategoryIcon(category: String, iconKey: String?) { analysisStore?.setCategoryIcon(category, iconKey) }

    fun setMonthlyNote(month: String, note: String) {
        analysisStore?.setMonthlyNote(month, note)
        val ym = runCatching { YearMonth.parse(month) }.getOrNull()
        if (ym != null) analyzeMonth(ym, forceReanalyze = true, debounceMs = 800L)
        else analyzeCurrentMonth()
    }

    fun recategorizeAll() {
        analysisStore?.clearAllClassifications()

        // 1. Immediately re-run CategoryClassifier over all reconciled transactions locally on frame 0!
        val currentTxs = _reconciledCache.value
        val snap = analysisSnapshot.value
        val resetTxs = currentTxs.map { tx ->
            if (tx.direction == Direction.CREDIT) tx.copy(category = null)
            else {
                val cleanId = tx.providerTransactionId.substringAfter(":")
                val note = snap.userNotes[cleanId] ?: tx.userNote
                val heuristic = CategoryClassifier.classify(tx, note)
                tx.copy(category = heuristic.category, aiReasoning = heuristic.reason)
            }
        }
        _reconciledCache.value = resetTxs

        // 2. Immediately update month analysis on frame 0!
        val month = _selectedMonth.value
        _monthAnalysis.value = BudgetAnalyzer.analyzeMonth(month, resetTxs, null)

        // 3. Immediately trigger fresh AI analysis for current month (cancel previous, 0ms debounce)
        analyzeMonth(month, forceReanalyze = true, debounceMs = 0L)
    }

    fun analyzeMonth(month: YearMonth, forceReanalyze: Boolean = false, debounceMs: Long = 600L) {
        // Cancel any pending debounce timer for this month
        monthDebounceJobs[month]?.cancel()
        // Cancel any running in-flight network request for this month
        monthAnalysisJobs[month]?.cancel()

        val debounceJob = analysisScope.launch {
            if (debounceMs > 0L && !forceReanalyze) {
                delay(debounceMs)
            }

            val snap = analysisSnapshot.value
            val monthStr = month.toString()
            val allTxs = _reconciledCache.value
            val zone = ZoneId.systemDefault()
            val monthTxs = allTxs.filter {
                it.direction == Direction.DEBIT && YearMonth.from(it.occurredAt.atZone(zone)) == month
            }
            if (monthTxs.isEmpty()) return@launch

            val monthlyNote = snap.monthlyNotes[monthStr].orEmpty()
            val currentFingerprint = BudgetAnalyzer.computeMonthFingerprint(
                monthTransactions = monthTxs,
                monthlyNote = monthlyNote,
                knowledge = snap.knowledge,
                customCategories = snap.customCategories,
            )
            val lastFingerprint = snap.analysisFingerprints[monthStr]
            if (!forceReanalyze && lastFingerprint != null && lastFingerprint == currentFingerprint && snap.monthlySummaries[monthStr] != null) {
                return@launch
            }

            val analysisJob = launch {
                _analyzingMonths.value = _analyzingMonths.value + month
                try {
                    val result = AiAnalysisService.analyzeMonth(
                        snap.aiConfig,
                        snap.knowledge,
                        monthStr,
                        monthTxs,
                        snap.customCategories,
                        monthlyNote,
                    )
                    analysisStore?.setBatchClassifications(result.classifications)
                    analysisStore?.setMonthlySummary(monthStr, result.monthlySummary)
                    analysisStore?.setMonthFingerprint(monthStr, currentFingerprint)

                    val currentCache = _reconciledCache.value
                    val updated = currentCache.map { tx ->
                        val clean = tx.providerTransactionId.substringAfter(":")
                        val aiCat = result.classifications[clean]?.first
                        val aiReason = result.classifications[clean]?.second
                        if (aiCat != null) tx.copy(category = aiCat, aiReasoning = aiReason) else tx
                    }
                    _reconciledCache.value = updated
                    _monthAnalysis.value = BudgetAnalyzer.analyzeMonth(month, updated, result.monthlySummary)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    android.util.Log.e("ArthaAi", "AI Analysis failed for $month", e)
                } finally {
                    _analyzingMonths.value = _analyzingMonths.value - month
                    monthAnalysisJobs.remove(month)
                }
            }
            monthAnalysisJobs[month] = analysisJob
        }
        monthDebounceJobs[month] = debounceJob
    }

    fun analyzeCurrentMonth() {
        analyzeMonth(_selectedMonth.value, forceReanalyze = true)
    }

    fun submitAiFeedback(transaction: UnifiedTransaction, feedback: String, onResult: (FeedbackResult) -> Unit) {
        analysisScope.launch {
            try {
                val snap = analysisSnapshot.value
                val res = AiAnalysisService.submitFeedback(snap.aiConfig, snap.knowledge, transaction, feedback, snap.customCategories)
                analysisStore?.setClassification(transaction.providerTransactionId, res.category, res.reason)
                onResult(res)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val fallbackReason = "Updated based on your input: $feedback"
                analysisStore?.setClassification(transaction.providerTransactionId, transaction.category ?: CategoryConstants.UNCATEGORIZED, fallbackReason)
                onResult(FeedbackResult(transaction.category ?: CategoryConstants.UNCATEGORIZED, fallbackReason, "${analysisSnapshot.value.knowledge}\n- ${transaction.description}: $feedback"))
            }
        }
    }
}
