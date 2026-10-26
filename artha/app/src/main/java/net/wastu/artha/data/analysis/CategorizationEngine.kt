package net.wastu.artha.data.analysis

import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.UnifiedTransaction

/**
 * The AI categorization queue. New payments this month go automatically; everything else runs when
 * the user asks ([recategorize]). Up to four batches run at once, manual work first, with back-off on
 * transient errors and rate limits and a hard stop on bad credentials. Within a run each merchant is
 * asked once: its other payments wait for that answer and copy it when the merchant always means one
 * category. All state lives on [scope]'s (main) thread.
 */
private const val MAX_LANES = 4

class CategorizationEngine(
    private val scope: CoroutineScope,
    private val store: AnalysisStore?,
    private val rows: StateFlow<List<UnifiedTransaction>>,
    private val snapshot: StateFlow<AnalysisSnapshot>,
    private val clock: Clock,
    private val computationDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    /** Test seam; null sends batches to the configured model with the saved rows as userExamples. */
    private val categorize: (suspend (AiConfig, AnalysisSnapshot, List<CategoryCandidate>) -> Map<String, CategorySuggestion>)? = null,
    private val fromMemory: (List<UnifiedTransaction>, AnalysisSnapshot) -> Map<String, CategorySuggestion> = AiCategorization::fromMemory,
    private val scopeKeys: (RecategorizeScope, List<UnifiedTransaction>, AnalysisSnapshot) -> Set<String> = AiCategorization::scopeKeys,
    private val needsReview: (UnifiedTransaction, AnalysisSnapshot) -> Boolean = ::needsReview,
) {
    private val _progress = MutableStateFlow(CategoryProgress())
    val progress: StateFlow<CategoryProgress> = _progress.asStateFlow()
    private val _automation = MutableStateFlow(CategoryAutomationState.NOT_CONFIGURED)
    val automation: StateFlow<CategoryAutomationState> = _automation.asStateFlow()
    private val _runResults = MutableSharedFlow<CategoryRunResult>(extraBufferCapacity = 1)
    val runResults: SharedFlow<CategoryRunResult> = _runResults.asSharedFlow()

    private data class ManualEntry(val key: String, val replaceManual: Boolean)

    private val pump = MutableStateFlow(0L)
    private var job: Job? = null
    private val batches = mutableMapOf<Job, Set<String>>()
    private val inFlight = mutableSetOf<String>()
    private val manualQueue = mutableListOf<ManualEntry>()
    /** New current-month payments waiting for the automatic queue. */
    private val automatic = mutableSetOf<String>()
    /** Manual-run keys that may take a merchant-memory label instead of a request. */
    private val memoryAllowed = mutableSetOf<String>()
    private val replaceManual = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private val sendAlone = mutableSetOf<String>()
    private val requeuedForInputChange = mutableSetOf<String>()
    private val backedOff = mutableSetOf<String>()
    private val backoffJobs = mutableMapOf<String, Job>()
    private var stopped = false
    private var fatalError: String? = null
    private val backoff = listOf(30_000L, 120_000L, 600_000L)
    /** Batches allowed in flight; halved on HTTP 429 and restored when the queue drains. */
    private var lanes = MAX_LANES

    /** Merchant key to the payment asked on its behalf; same-merchant payments wait for its answer. */
    private val probing = mutableMapOf<String, String>()
    /** This run's confident answers for merchants that always mean one category. */
    private val merchantAnswers = mutableMapOf<String, CategorySuggestion>()
    /** Merchants whose answer cannot be copied (marketplaces, wallets, unsure); their payments go one by one. */
    private val perPayment = mutableSetOf<String>()

    /** The current manual run, so in-flight automatic work is not counted as part of it. */
    private val run = mutableSetOf<String>()
    private var runTotal = 0
    private val runCommitted = mutableSetOf<String>()
    private val runReview = mutableSetOf<String>()
    private val runFailed = mutableSetOf<String>()

    private data class Plan(val total: Int, val review: Int, val failed: Int)
    private var lastPlan: Plan? = null

    private fun configured(s: AnalysisSnapshot) =
        s.analysisEnabled && s.aiConfig.baseUrl.isNotBlank() && s.aiConfig.apiKey.isNotBlank() && s.aiConfig.model.isNotBlank()

    private fun currentMonth(): YearMonth = YearMonth.from(clock.instant().atZone(AccountingTime.zone))
    private fun monthOf(tx: UnifiedTransaction): YearMonth = YearMonth.from(tx.occurredAt.atZone(AccountingTime.zone))
    private fun triggerPump() = pump.update { it + 1L }
    private val automaticOn get() = snapshot.value.autoCategorize && !stopped && fatalError == null

    fun start() {
        if (store == null || job?.isActive == true) return
        job = scope.launch {
            var previousConfig: AiConfig? = null
            var previousEnabled = false
            var previousRows: List<Pair<String, java.time.Instant>>? = null
            combine(rows, snapshot, pump) { r, s, _ -> r to s }.collect { (rows, snapshot) ->
                val config = snapshot.aiConfig
                if (config != previousConfig || snapshot.analysisEnabled != previousEnabled) {
                    cancelBatches()
                    clearBackoff()
                    if (config != previousConfig) {
                        attempts.clear(); sendAlone.clear(); fatalError = null; lanes = MAX_LANES
                    }
                    clearMerchants()
                    previousConfig = config
                    previousEnabled = snapshot.analysisEnabled
                }
                val signature = rows.map { it.key to it.occurredAt }
                if (signature != previousRows) {
                    noteRowsChanged(rows, snapshot)
                    previousRows = signature
                }
                if (!configured(snapshot)) {
                    publishNotConfigured(snapshot)
                    return@collect
                }
                lastPlan = withContext(computationDispatcher) { plan(rows, snapshot) }
                if (fatalError != null && manualQueue.isEmpty() && batches.isEmpty()) {
                    publish(snapshot)
                    return@collect
                }
                // Keys that stopped being new (labeled elsewhere, failed, gone) would otherwise stay "queued" forever.
                val byKey = rows.associateBy { it.key }
                automatic.retainAll { key -> byKey[key]?.let { AiCategorization.isNew(it, snapshot) } == true }
                if (commitMemory(rows, snapshot)) return@collect
                if (commitMerchantAnswers(rows, snapshot)) return@collect
                if (batches.isEmpty() && manualQueue.isEmpty() && automatic.isEmpty()) {
                    clearMerchants(); lanes = MAX_LANES
                }
                while (batches.size < lanes) {
                    val batch = nextBatch(rows, snapshot)
                    if (batch.isEmpty()) break
                    launchBatch(batch, config, snapshot)
                }
                publish(snapshot)
            }
        }
    }

    private fun noteRowsChanged(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot) {
        val month = currentMonth()
        val fresh = rows.filter { monthOf(it) == month && AiCategorization.isNew(it, snapshot) }
        if (fresh.isNotEmpty()) {
            stopped = false
            automatic.addAll(fresh.map { it.key })
        }
        if (rows.isEmpty()) return
        val present = rows.mapTo(mutableSetOf()) { it.key }
        automatic.retainAll(present)
        memoryAllowed.retainAll(present)
        manualQueue.removeAll { it.key !in present }
        run.retainAll(present); runCommitted.retainAll(present); runReview.retainAll(present); runFailed.retainAll(present)
        sendAlone.retainAll(present); requeuedForInputChange.retainAll(present)
        attempts.keys.retainAll(present)
    }

    private fun plan(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): Plan {
        val eligible = rows.filter { AiCategorization.isEligible(it, snapshot) }.distinctBy { it.key }
        return Plan(eligible.size, eligible.count { needsReview(it, snapshot) }, eligible.count { it.key in snapshot.categoryFailures })
    }

    /** Labels new and pending payments from merchant memory without a request; true when something was saved. */
    private suspend fun commitMemory(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): Boolean {
        val store = store ?: return false
        val wanted = (if (automaticOn) automatic else emptySet()) + memoryAllowed.filter { key -> manualQueue.any { it.key == key } }
        if (wanted.isEmpty()) return false
        val memory = withContext(computationDispatcher) { fromMemory(rows, snapshot) }.filter { (key, suggestion) ->
            // Only answers that change something; rewriting identical labels would re-emit forever.
            key in wanted && key !in inFlight &&
                (snapshot.categories[key] != suggestion.category || snapshot.reasons[key] != suggestion.reason)
        }
        if (memory.isEmpty()) return false
        val saved = withContext(ioDispatcher) { store.commitMerchantSuggestions(memory, snapshot, rows) } > 0
        if (saved) {
            manualQueue.removeAll { it.key in memory.keys }
            for (key in memory.keys) {
                if (key in run) runCommitted.add(key)
                settle(key)
            }
        }
        return saved
    }

    private fun nextBatch(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): List<CategoryCandidate> {
        val candidates = mutableListOf<CategoryCandidate>()
        val byKey = rows.associateBy { it.key }
        val asking = mutableSetOf<String>()
        /** Another payment of this merchant is being asked, or its answer is about to be copied. */
        fun waitsForMerchant(tx: UnifiedTransaction, replace: Boolean): Boolean {
            if (replace) return false
            val merchant = MerchantMemory.merchantKey(tx) ?: return false
            if (merchant in perPayment) return false
            if (merchant in merchantAnswers) return true
            if (probing[merchant].let { it != null && it != tx.key }) return true
            return !asking.add(merchant)
        }
        fun add(tx: UnifiedTransaction): Boolean {
            // A payment that broke a batch with a 400 is retried on its own.
            if (tx.key in sendAlone) {
                if (candidates.isEmpty()) candidates += CategoryCandidate(tx, AiCategorization.fingerprint(tx, snapshot))
                return false
            }
            candidates += CategoryCandidate(tx, AiCategorization.fingerprint(tx, snapshot))
            return candidates.size < AiCategorization.MAX_BATCH
        }
        for (entry in manualQueue) {
            if (entry.key in inFlight || entry.key in backedOff) continue
            val tx = byKey[entry.key] ?: continue
            if (waitsForMerchant(tx, entry.replaceManual)) continue
            if (!add(if (entry.replaceManual) tx.copy(classificationIsManual = false) else tx)) break
        }
        if (automaticOn && candidates.size < AiCategorization.MAX_BATCH && candidates.none { it.transaction.key in sendAlone }) {
            val fresh = this.snapshot.value
            val waiting = automatic.mapNotNull { byKey[it] }.filter { tx ->
                tx.key !in inFlight && tx.key !in backedOff && candidates.none { it.transaction.key == tx.key } &&
                    fresh.updatedAt["cat:${tx.key}"] == snapshot.updatedAt["cat:${tx.key}"] &&
                    AiCategorization.isNew(tx, fresh)
            }.sortedByDescending { it.occurredAt }
            for (tx in waiting) {
                if (waitsForMerchant(tx, replace = false)) continue
                if (!add(tx)) break
            }
        }
        return if (candidates.isEmpty()) emptyList() else AiCategorization.batchOf(candidates, snapshot)
    }

    private fun launchBatch(batch: List<CategoryCandidate>, config: AiConfig, input: AnalysisSnapshot) {
        val keys = batch.mapTo(mutableSetOf()) { it.transaction.key }
        val manualInBatch = manualQueue.filter { it.key in keys }
        inFlight.addAll(keys)
        manualQueue.removeAll { it.key in keys }
        val probes = batch.filter { candidate -> manualInBatch.none { it.key == candidate.transaction.key && it.replaceManual } }
            .mapNotNull { candidate -> MerchantMemory.merchantKey(candidate.transaction)?.let { it to candidate.transaction.key } }
            .filter { (merchant, _) -> merchant !in perPayment && merchant !in probing }
        probing.putAll(probes)
        fun requeue(entries: List<ManualEntry>) {
            if (entries.isEmpty()) return
            val requeued = entries.mapTo(mutableSetOf()) { it.key }
            manualQueue.removeAll { it.key in requeued }
            manualQueue.addAll(0, entries)
        }
        val batchJob = scope.launch {
            try {
                publish(snapshot.value)
                val suggestions = withContext(computationDispatcher) {
                    categorize?.invoke(config, input, batch) ?: AiCategorization.classify(config, input, batch, rows.value)
                }
                ensureActive()
                val valid = AiCategorization.categories(snapshot.value)
                fun answered(key: String) = suggestions[key]?.category?.let { it in valid } == true

                val replacing = keys.filterTo(mutableSetOf()) { it in replaceManual }
                if (replacing.isNotEmpty()) withContext(ioDispatcher) {
                    for (key in replacing) {
                        replaceManual.remove(key)
                        store?.removeManualCategory(key)
                        val suggestion = suggestions[key]
                        if (suggestion != null && answered(key)) {
                            store?.setClassification(key, suggestion.category, suggestion.reason, manual = false)
                            noteCommitted(key, suggestion)
                        } else {
                            store?.recordCategoryFailures(setOf(key), "AI did not return a valid category")
                            if (key in run) runFailed.add(key)
                        }
                        settle(key)
                    }
                }

                val standard = batch.filter { it.transaction.key !in replacing }
                val (good, missing) = standard.partition { answered(it.transaction.key) }
                if (missing.isNotEmpty()) {
                    markFailed(missing.mapTo(mutableSetOf()) { it.transaction.key }, "AI did not return a valid category")
                }
                if (good.isNotEmpty()) {
                    val current = rows.value.associateBy { it.key }
                    val now = snapshot.value
                    val unchanged = good.filter { candidate ->
                        current[candidate.transaction.key]?.let { AiCategorization.fingerprint(it, now) == candidate.fingerprint } == true
                    }
                    val committed = withContext(ioDispatcher) { store?.commitCategorySuggestionKeys(unchanged, suggestions, input).orEmpty() }
                    val retry = mutableListOf<ManualEntry>()
                    for (candidate in good) {
                        val key = candidate.transaction.key
                        if (key in committed) {
                            noteCommitted(key, suggestions.getValue(key))
                            noteMerchantAnswer(candidate.transaction, suggestions.getValue(key))
                            settle(key)
                        } else if (key in run) {
                            // The payment changed while the model was thinking: ask once more with the new evidence.
                            if (requeuedForInputChange.add(key)) retry += ManualEntry(key, key in replaceManual)
                            else requeuedForInputChange.remove(key)
                        }
                    }
                    requeue(retry)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val http = (e as? AiHttpException) ?: (e.cause as? AiHttpException)
                when {
                    http != null && http.status in setOf(401, 403, 404) -> {
                        fatalError = aiConnectionFailure(e)
                        for (other in batches.keys.toList()) if (other !== coroutineContext[Job]) other.cancel()
                    }
                    http != null && http.status == 400 && batch.size > 1 -> {
                        sendAlone.addAll(keys)
                        requeue(manualInBatch)
                    }
                    http != null && http.status == 400 -> markFailed(keys, aiConnectionFailure(e))
                    http != null && http.status == 429 -> {
                        lanes = (lanes / 2).coerceAtLeast(1)
                        scheduleRetry(keys, manualInBatch, aiConnectionFailure(e))
                    }
                    else -> scheduleRetry(keys, manualInBatch, aiConnectionFailure(e))
                }
            } finally {
                inFlight.removeAll(keys)
                // An unanswered probe hands its merchant to the next waiting payment.
                for ((merchant, key) in probes) if (probing[merchant] == key) probing.remove(merchant)
                batches.remove(coroutineContext[Job])
                if (job?.isActive == true) triggerPump()
            }
        }
        batches[batchJob] = keys
    }

    private fun noteCommitted(key: String, suggestion: CategorySuggestion) {
        if (key !in run) return
        runCommitted.add(key)
        if (suggestion.confidence == CategoryConfidence.LOW || suggestion.category == CategoryConstants.UNCATEGORIZED) runReview.add(key)
    }

    private fun noteMerchantAnswer(tx: UnifiedTransaction, suggestion: CategorySuggestion) {
        val merchant = MerchantMemory.merchantKey(tx) ?: return
        if (probing[merchant] != tx.key) return
        probing.remove(merchant)
        val copyable = suggestion.merchantConsistent && suggestion.percent > TRUSTED_ABOVE_PERCENT &&
            suggestion.category != CategoryConstants.UNCATEGORIZED
        if (copyable) merchantAnswers[merchant] = suggestion else perPayment.add(merchant)
    }

    /** Gives queued payments of a merchant answered this run that merchant's category; true when something was saved. */
    private suspend fun commitMerchantAnswers(rows: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): Boolean {
        val store = store ?: return false
        if (merchantAnswers.isEmpty()) return false
        val replacing = manualQueue.filter { it.replaceManual }.mapTo(mutableSetOf()) { it.key }
        val wanted = (manualQueue.map { it.key } + (if (automaticOn) automatic else emptySet())).toSet() - replacing - inFlight
        if (wanted.isEmpty()) return false
        val byKey = rows.associateBy { it.key }
        val copies = withContext(computationDispatcher) {
            wanted.mapNotNull { key ->
                val tx = byKey[key]?.takeIf { AiCategorization.isEligible(it, snapshot) } ?: return@mapNotNull null
                val merchant = MerchantMemory.merchantKey(tx)?.takeIf { it !in perPayment } ?: return@mapNotNull null
                val answer = merchantAnswers[merchant] ?: return@mapNotNull null
                val display = MerchantMemory.displayMerchant(tx) ?: return@mapNotNull null
                key to answer.copy(reason = MERCHANT_REASON_PREFIX + "Same as other $display payments")
            }.toMap()
        }
        if (copies.isEmpty()) return false
        val saved = withContext(ioDispatcher) { store.commitMerchantSuggestions(copies, snapshot, rows) } > 0
        // Payments the store refused (picked or edited meanwhile) are asked on their own.
        for (key in copies.keys) {
            val tx = byKey.getValue(key)
            if (store.snapshot.value.reasons[key] == copies.getValue(key).reason) {
                manualQueue.removeAll { it.key == key }
                if (key in run) runCommitted.add(key)
                settle(key)
            } else {
                MerchantMemory.merchantKey(tx)?.let(perPayment::add)
            }
        }
        return saved
    }

    private fun clearMerchants() {
        probing.clear(); merchantAnswers.clear(); perPayment.clear()
    }

    private suspend fun markFailed(keys: Set<String>, message: String) {
        withContext(ioDispatcher) { store?.recordCategoryFailures(keys, message) }
        for (key in keys) {
            if (key in run) runFailed.add(key)
            settle(key)
        }
    }

    private suspend fun scheduleRetry(keys: Set<String>, manualInBatch: List<ManualEntry>, message: String) {
        val byDelay = mutableMapOf<Long, MutableSet<String>>()
        val exhausted = mutableSetOf<String>()
        for (key in keys) {
            val attempt = (attempts[key] ?: 0) + 1
            attempts[key] = attempt
            backoff.getOrNull(attempt - 1)?.let { byDelay.getOrPut(it) { mutableSetOf() }.add(key) } ?: exhausted.add(key)
        }
        for ((wait, waiting) in byDelay) {
            backedOff.addAll(waiting)
            val requeued = manualInBatch.filter { it.key in waiting }
            manualQueue.removeAll { entry -> requeued.any { it.key == entry.key } }
            manualQueue.addAll(0, requeued)
            val timer = scope.launch {
                delay(wait)
                backedOff.removeAll(waiting)
                if (job?.isActive == true) triggerPump()
            }
            for (key in waiting) { backoffJobs[key]?.cancel(); backoffJobs[key] = timer }
        }
        if (exhausted.isNotEmpty()) markFailed(exhausted, message)
    }

    /** Drops all retry bookkeeping for [key]: it is labeled, failed for good, or the user decided. */
    private fun settle(key: String) {
        attempts.remove(key); sendAlone.remove(key); backedOff.remove(key); backoffJobs.remove(key)?.cancel()
        automatic.remove(key); memoryAllowed.remove(key); requeuedForInputChange.remove(key)
    }

    private fun publishNotConfigured(snapshot: AnalysisSnapshot) {
        _progress.value = CategoryProgress(state = CategoryAutomationState.NOT_CONFIGURED, paused = !snapshot.autoCategorize)
        _automation.value = CategoryAutomationState.NOT_CONFIGURED
        CategoryActivity.publish(emptySet())
    }

    private fun publish(snapshot: AnalysisSnapshot) {
        if (!configured(snapshot)) return publishNotConfigured(snapshot)
        val month = currentMonth()
        val thisMonth = rows.value.filter { AiCategorization.isEligible(it, snapshot) && monthOf(it) == month }.distinctBy { it.key }
        // Count what the user sees: grouped rows resolve their category from member records, not their own key.
        val labeled = thisMonth.count { tx ->
            (snapshot.categories[tx.key] ?: tx.category).let { !it.isNullOrBlank() && it != CategoryConstants.UNCATEGORIZED }
        }
        val plan = lastPlan
        val review = plan?.review ?: rows.value.count { AiCategorization.isEligible(it, snapshot) && needsReview(it, snapshot) }
        val failed = plan?.failed ?: rows.value.count { AiCategorization.isEligible(it, snapshot) && it.key in snapshot.categoryFailures }

        val active = inFlight.toSet()
        val manual = manualQueue.mapTo(mutableSetOf()) { it.key }
        val queued = (manual + (if (automaticOn) automatic else emptySet())) - active
        CategoryActivity.publish(if (stopped) emptySet() else active + queued)

        val remaining = (manual + active).count { it in run }
        val finished = runTotal > 0 && remaining == 0
        val runDone = (runTotal - remaining).coerceAtLeast(0)
        if (finished) {
            val result = CategoryRunResult(runCommitted.size, runReview.size, runFailed.size)
            clearRun()
            if (!stopped && fatalError == null) _runResults.tryEmit(result)
        }
        val state = when {
            fatalError != null && active.isEmpty() && manual.isEmpty() -> CategoryAutomationState.ERROR
            stopped -> CategoryAutomationState.READY
            active.isNotEmpty() || queued.isNotEmpty() -> CategoryAutomationState.WORKING
            else -> CategoryAutomationState.READY
        }
        _progress.value = CategoryProgress(
            state = state,
            labeled = labeled,
            total = thisMonth.size,
            error = if (state == CategoryAutomationState.ERROR || !stopped) fatalError else null,
            review = review,
            failed = failed,
            paused = !snapshot.autoCategorize,
            activeKeys = active,
            queuedKeys = queued,
            stopped = stopped,
            runDone = if (finished) 0 else runDone,
            runTotal = if (finished) 0 else runTotal,
        )
        _automation.value = state
    }

    private fun clearRun() {
        run.clear(); runTotal = 0
        runCommitted.clear(); runReview.clear(); runFailed.clear()
        requeuedForInputChange.clear()
    }

    private fun cancelBatches() {
        for (batch in batches.keys.toList()) batch.cancel()
        batches.clear()
        inFlight.clear()
    }

    private fun clearBackoff() {
        for (timer in backoffJobs.values) timer.cancel()
        backoffJobs.clear()
        backedOff.clear()
    }

    /** Counts [keys] toward the current run's progress; keys already counted are not added twice. */
    private fun queue(keys: Collection<String>, replace: Boolean = false, memory: Boolean = false) {
        val entries = keys.filter { it !in inFlight }.map { ManualEntry(it, replace) }
        val entryKeys = entries.mapTo(mutableSetOf()) { it.key }
        manualQueue.removeAll { it.key in entryKeys }
        manualQueue.addAll(0, entries)
        if (memory) memoryAllowed.addAll(entryKeys)
        runTotal += keys.count { run.add(it) }
    }

    /** Runs AI categorization for [scope] now, ahead of the automatic queue and regardless of failure back-off. */
    fun recategorize(scope: RecategorizeScope) {
        val keys = scopeKeys(scope, rows.value, snapshot.value)
        if (keys.isEmpty()) return
        stopped = false
        this.scope.launch(ioDispatcher) { store?.clearCategoryFailures(keys) }
        for (key in keys) settle(key)
        runCommitted.removeAll(keys); runReview.removeAll(keys); runFailed.removeAll(keys)
        val replace = (scope as? RecategorizeScope.Payment)?.replaceManual == true
        if (replace) replaceManual.addAll(keys)
        // "Categorize" fills gaps and may copy a merchant's earlier label; "Re-categorize" always asks the model.
        val memory = scope is RecategorizeScope.MonthPending || scope is RecategorizeScope.AllPending || scope is RecategorizeScope.New
        queue(keys, replace, memory)
        start()
        triggerPump()
    }

    /** Payments a [recategorize] run for [scope] would touch, for confirmation dialogs. */
    fun count(scope: RecategorizeScope): Int = scopeKeys(scope, rows.value, snapshot.value).size

    /** Cancels in-flight and queued categorization; those payments return to their previous state. */
    fun stop() {
        cancelBatches()
        manualQueue.clear()
        clearRun()
        sendAlone.clear(); attempts.clear(); replaceManual.clear(); automatic.clear(); memoryAllowed.clear()
        clearBackoff()
        clearMerchants()
        stopped = true
        CategoryActivity.publish(emptySet())
        publish(snapshot.value)
    }

    /** Retries this month's unlabeled and failed payments after an error or Stop. */
    fun retry() {
        stopped = false
        fatalError = null
        clearBackoff()
        attempts.clear(); sendAlone.clear()
        val snapshot = snapshot.value
        val month = currentMonth()
        // A forced run: these may not be "new", and the automatic list only sends new payments.
        val keys = rows.value.filter { tx ->
            monthOf(tx) == month && AiCategorization.isEligible(tx, snapshot) &&
                (tx.key in snapshot.categoryFailures || AiCategorization.isPending(tx, snapshot))
        }.mapTo(mutableSetOf()) { it.key }
        val failed = keys.filter { it in snapshot.categoryFailures }
        if (failed.isNotEmpty()) scope.launch(ioDispatcher) { store?.clearCategoryFailures(failed) }
        val queued = manualQueue.mapTo(mutableSetOf()) { it.key }
        val entries = keys.filter { it !in inFlight && it !in queued }
        manualQueue.addAll(entries.map { ManualEntry(it, replaceManual = false) })
        runTotal += entries.count { run.add(it) }
        triggerPump()
        start()
    }

    /** The user decided these payments themselves; forget pending retries for them. */
    fun userDecided(keys: Collection<String>) {
        for (key in keys) settle(key)
        triggerPump()
    }

    /** Stops everything when the app locks; [start] resumes. */
    fun shutdown() {
        job?.cancel()
        cancelBatches()
        clearBackoff()
        manualQueue.clear(); replaceManual.clear(); automatic.clear(); memoryAllowed.clear()
        clearRun()
        clearMerchants()
        sendAlone.clear(); attempts.clear()
        stopped = false
        CategoryActivity.publish(emptySet())
    }
}
