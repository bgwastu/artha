package net.wastu.artha.data.analysis

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import net.wastu.artha.core.model.*
import net.wastu.artha.data.ledger.LedgerRepository
import net.wastu.artha.data.reconciliation.LedgerReconciler
import org.json.JSONObject

sealed interface SpendingState {
    data object Loading : SpendingState
    data object Failed : SpendingState
    data class Ready(val transactions: List<UnifiedTransaction>, val providerCounts: Map<Provider, Int>) : SpendingState
}

/** Matching depends only on immutable source facts, never on filters, icons or AI settings. */
class SpendingProjector(private val reconcile: (List<UnifiedTransaction>) -> List<UnifiedTransaction> = LedgerReconciler::reconcileLedger) {
    private var facts: List<UnifiedTransaction>? = null
    private var matched = emptyList<UnifiedTransaction>()
    private var aliases = emptyMap<String, List<String>>()
    private data class EditInput(val note: String?, val category: String?, val manual: Boolean, val reason: String?)
    private val rowCache = mutableMapOf<String, Pair<EditInput, UnifiedTransaction>>()
    private var lastSnapshot: AnalysisSnapshot? = null
    private var lastProjection: SpendingState.Ready? = null

    @Synchronized
    fun project(raw: List<UnifiedTransaction>, snapshot: AnalysisSnapshot): SpendingState.Ready {
        if (facts == raw && snapshot == lastSnapshot) lastProjection?.let { return it }
        if (facts != raw) {
            rowCache.clear()
            matched = reconcile(net.wastu.artha.data.ledger.ProviderObservations.resolve(raw))
            facts = raw
            aliases = matched.associate { tx ->
                val sources = mutableListOf(tx.key, "${tx.provider.name}:${tx.providerTransactionId}")
                val groups = runCatching { JSONObject(tx.rawPayload).optJSONArray("groupedTransactions") }.getOrNull()
                if (groups != null) for (i in 0 until groups.length()) {
                    val group = groups.optJSONObject(i) ?: continue
                    sources += "${group.optString("provider")}:${group.optString("providerTransactionId")}"
                }
                tx.key to sources.distinct()
            }
        }
        val metadata = TransactionMetadata.migrate(snapshot, raw.filterNot(net.wastu.artha.data.ledger.ProviderObservations::isObservation))
        val rows = matched.map { tx ->
            val keys = aliases[tx.key].orEmpty()
            val noteKey = if ("note:${tx.key}" in metadata.updatedAt) tx.key else keys.firstOrNull { it in metadata.userNotes }
            val manual = keys.firstOrNull { it in metadata.manualCategories }
            val categoryKey = manual ?: keys.firstOrNull { TransactionMetadata.category(metadata, it) != null }
            val note = noteKey?.let { metadata.userNotes[it] }
            val edit = EditInput(note, categoryKey?.let { metadata.categories[it] }, manual != null, categoryKey?.let { metadata.reasons[it] })
            rowCache[tx.key]?.takeIf { it.first == edit }?.let { return@map it.second }
            tx.copy(userNote = note,
                category = if (tx.direction == Direction.CREDIT) null else categoryKey?.let { metadata.categories[it] } ?: CategoryConstants.UNCATEGORIZED,
                classificationIsManual = manual != null,
                aiReasoning = edit.reason ?: "").also { rowCache[tx.key] = edit to it }
        }
        return SpendingState.Ready(rows, raw.filterNot(net.wastu.artha.data.ledger.ProviderObservations::isObservation).groupingBy { it.provider }.eachCount()).also {
            lastSnapshot = snapshot; lastProjection = it
        }
    }
}

private data class MetadataInput(val notes: Map<String, String>, val categories: Map<String, String>,
    val reasons: Map<String, String>, val manual: Set<String>, val noteTimes: Map<String, Long>) {
    fun snapshot() = AnalysisSnapshot(userNotes = notes, categories = categories, reasons = reasons, manualCategories = manual, updatedAt = noteTimes)
}

fun spendingFlow(ledger: LedgerRepository, analysis: StateFlow<AnalysisSnapshot>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default, migrate: (List<UnifiedTransaction>) -> Unit = {}): Flow<SpendingState> {
    val projector = SpendingProjector()
    val metadata = analysis.map { MetadataInput(it.userNotes, it.categories, it.reasons, it.manualCategories,
        it.updatedAt.filterKeys { key -> key.startsWith("note:") }) }.distinctUntilChanged()
    return combine(flow { emitAll(ledger.observeTransactions()) }.distinctUntilChanged().onEach { migrate(it.filterNot(net.wastu.artha.data.ledger.ProviderObservations::isObservation)) }, metadata) { facts, annotations ->
        projector.project(facts, annotations.snapshot()) as SpendingState
    }.flowOn(dispatcher).catch { emit(SpendingState.Failed) }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpendingRepository(ledger: LedgerRepository, store: AnalysisStore, scope: CoroutineScope) {
    private val retryGeneration = MutableStateFlow(0L)
    private val _lastReady = MutableStateFlow<SpendingState.Ready?>(null)
    val lastReady: StateFlow<SpendingState.Ready?> = _lastReady.asStateFlow()
    fun retry() { retryGeneration.update { it + 1L } }
    val state = retryGeneration.flatMapLatest {
        spendingFlow(ledger, store.snapshot, migrate = store::migrateMetadata).onStart { emit(SpendingState.Loading) }
    }.onEach {
        if (it is SpendingState.Ready) {
            _lastReady.value = it
            store.migrateAiCategoryFingerprints(it.transactions)
        }
    }.stateIn(scope, SharingStarted.Eagerly, SpendingState.Loading)
}
