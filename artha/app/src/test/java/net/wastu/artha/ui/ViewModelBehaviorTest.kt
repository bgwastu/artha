package net.wastu.artha.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.analysis.*
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelBehaviorTest {
    private val dispatcher = StandardTestDispatcher()
    private val transactions = MutableStateFlow<List<UnifiedTransaction>>(emptyList())
    private val ledger = object : LedgerRepository {
        override fun observeTransactions() = transactions
        override suspend fun loadTransactions() = transactions.value
        override suspend fun append(transactions: List<UnifiedTransaction>) = AppendResult(transactions.size, 0)
    }

    private val baseInstant = Instant.parse("2026-10-15T12:00:00Z")
    private val fixedClock = Clock.fixed(baseInstant, AccountingTime.zone)
    private val currentMonth = YearMonth.of(2026, 10)
    private val pastMonth = YearMonth.of(2026, 9)

    private fun testPayment(
        id: String,
        amount: Long = 50000,
        manual: Boolean = false,
        instant: Instant = baseInstant,
        description: String = "Store",
        category: String? = null,
    ) = UnifiedTransaction(
        Provider.JAGO,
        id,
        Direction.DEBIT,
        Money(amount),
        instant,
        description,
        "{}",
        classificationIsManual = manual,
        category = category,
    )

    private fun configuredPrefs() = mapOf(
        "ai_api_key" to "test-key",
        "analysis_enabled" to "true",
        "ai_base_url" to "https://ai.wastu.net",
        "ai_model" to "gemini-3.8-flash-high",
        "custom_categories" to org.json.JSONArray(INITIAL_DEFAULT_CATEGORIES).toString(),
    )

    private fun readySpending(rows: List<UnifiedTransaction>): SpendingState =
        SpendingState.Ready(rows, rows.groupingBy { it.provider }.eachCount())

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun analysisNeverAutoStartsOnForegroundOrDataChange() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val p1 = testPayment("p1")
        val state = MutableStateFlow(readySpending(listOf(p1)))

        val analysisCalls = AtomicInteger(0)
        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "Food") }
            },
            analyzeMonthAction = { _, _, _, _, _, _, _ ->
                analysisCalls.incrementAndGet()
                MonthAnalysisResult("Summary")
            },
        )

        // 1. Foreground does not start analysis
        model.enterForeground()
        advanceUntilIdle()
        assertEquals(0, analysisCalls.get())
        assertEquals(MonthAnalysisStatus.NeverRun, model.analysisStatus.value)

        // 2. Data change (new payments arrive) does not start analysis
        val p2 = testPayment("p2")
        state.value = readySpending(listOf(p1, p2))
        advanceUntilIdle()
        assertEquals(0, analysisCalls.get())
        assertEquals(MonthAnalysisStatus.NeverRun, model.analysisStatus.value)

        // 3. User note change does not start analysis
        store.setUserNote(p1.key, "New note")
        advanceUntilIdle()
        assertEquals(0, analysisCalls.get())

        // 4. Manual analysis start runs
        model.analyzeMonth(currentMonth, forceReanalyze = true)
        advanceUntilIdle()
        assertEquals(1, analysisCalls.get())
        assertTrue(model.analysisStatus.value is MonthAnalysisStatus.UpToDate)

        model.lock()
    }

    @Test
    fun manualAnalysisWaitsForInMonthCategorizationThenStarts() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val p1 = testPayment("p1")
        val state = MutableStateFlow(readySpending(listOf(p1)))

        val categoryGate = CompletableDeferred<Unit>()
        val analysisCalls = AtomicInteger(0)

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                categoryGate.await()
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
            analyzeMonthAction = { _, _, _, _, _, _, _ ->
                analysisCalls.incrementAndGet()
                MonthAnalysisResult("October summary")
            },
        )

        model.enterForeground()
        // Wait until categorization is active/in flight for p1
        model.categoryProgress.first { it.activeKeys.contains(p1.key) }

        // User taps analyze while p1 in October is categorizing
        model.analyzeMonth(currentMonth, forceReanalyze = true)
        advanceUntilIdle()

        // Analysis status should be Waiting(CATEGORIES)
        assertEquals(MonthAnalysisStatus.Waiting(AnalysisWait.CATEGORIES), model.analysisStatus.value)
        assertEquals(0, analysisCalls.get())

        // Finish categorization
        categoryGate.complete(Unit)
        advanceUntilIdle()

        // Once categorization completes, analysis runs automatically
        assertEquals(1, analysisCalls.get())
        assertTrue(model.analysisStatus.value is MonthAnalysisStatus.UpToDate)

        model.lock()
    }

    @Test
    fun cancelPendingAnalysisRevertsStatusAndPreventsAutoStart() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val p1 = testPayment("p1")
        val state = MutableStateFlow(readySpending(listOf(p1)))

        val categoryGate = CompletableDeferred<Unit>()
        val analysisCalls = AtomicInteger(0)

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                categoryGate.await()
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
            analyzeMonthAction = { _, _, _, _, _, _, _ ->
                analysisCalls.incrementAndGet()
                MonthAnalysisResult("October summary")
            },
        )

        model.enterForeground()
        model.categoryProgress.first { it.activeKeys.contains(p1.key) }

        model.analyzeMonth(currentMonth, forceReanalyze = true)
        advanceUntilIdle()
        assertEquals(MonthAnalysisStatus.Waiting(AnalysisWait.CATEGORIES), model.analysisStatus.value)

        // Cancel the wait
        model.cancelPendingAnalysis()
        advanceUntilIdle()
        assertEquals(MonthAnalysisStatus.NeverRun, model.analysisStatus.value)

        // Complete categorization
        categoryGate.complete(Unit)
        advanceUntilIdle()

        // Analysis was cancelled, so it does NOT auto-start
        assertEquals(0, analysisCalls.get())
        assertEquals(MonthAnalysisStatus.NeverRun, model.analysisStatus.value)

        model.lock()
    }

    @Test
    fun automaticCategorizationIgnoresOldMonthsAndAlreadyCategorizedPayments() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val pNewCurrent = testPayment("p-new-curr")
        val pOldMonth = testPayment("p-old-month", instant = Instant.parse("2026-09-10T12:00:00Z"))
        val pAlreadyLabeled = testPayment("p-already-labeled")
        val pManual = testPayment("p-manual", manual = true)

        // Pre-label pAlreadyLabeled in store
        val fpAlready = AiCategorization.fingerprint(pAlreadyLabeled, store.snapshot.value)
        store.commitCategorySuggestions(
            listOf(CategoryCandidate(pAlreadyLabeled, fpAlready)),
            mapOf(pAlreadyLabeled.key to CategorySuggestion("Dining Out", "AI suggestion: Pre")),
            store.snapshot.value,
        )

        val state = MutableStateFlow(readySpending(listOf(pNewCurrent, pOldMonth, pAlreadyLabeled, pManual)))
        val categorizedKeys = mutableListOf<String>()

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                categorizedKeys.addAll(batch.map { it.transaction.key })
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Auto") }
            },
        )

        model.enterForeground()
        advanceUntilIdle()
        val progress = model.categoryProgress.value
        assertEquals(CategoryAutomationState.READY, progress.state)
        assertEquals(2, progress.labeled)
        assertEquals(2, progress.total)

        // Only pNewCurrent was sent to AI
        assertEquals(listOf(pNewCurrent.key), categorizedKeys)
        assertFalse(categorizedKeys.contains(pOldMonth.key))
        assertFalse(categorizedKeys.contains(pAlreadyLabeled.key))
        assertFalse(categorizedKeys.contains(pManual.key))

        model.lock()
    }

    @Test
    fun editsToNotesContextAndConfigDoNotStartAutomaticWork() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        // Existing row is already categorized
        val p1 = testPayment("p1")
        store.setClassification(p1.key, "Groceries", "AI suggestion: Food", manual = false)

        val state = MutableStateFlow(readySpending(listOf(p1)))
        val categorizeCalls = AtomicInteger(0)

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                categorizeCalls.incrementAndGet()
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
        )

        model.enterForeground()
        advanceUntilIdle()
        assertEquals(0, categorizeCalls.get())

        // 1. Edit note
        store.setUserNote(p1.key, "Updated user note")
        advanceUntilIdle()
        assertEquals(0, categorizeCalls.get())

        // 2. Edit knowledge
        store.setKnowledge("Updated personal knowledge context")
        advanceUntilIdle()
        assertEquals(0, categorizeCalls.get())

        // 3. Edit custom categories
        store.addCustomCategory("Special Category")
        advanceUntilIdle()
        assertEquals(0, categorizeCalls.get())

        // 4. Edit monthly note
        store.setMonthlyNote(currentMonth.toString(), "Monthly note context")
        advanceUntilIdle()
        assertEquals(0, categorizeCalls.get())

        model.lock()
    }

    @Test
    fun stopCancelsAndSetsStoppedRetryClearsIt() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val p1 = testPayment("p1")
        val state = MutableStateFlow(readySpending(listOf(p1)))

        val gate = CompletableDeferred<Unit>()
        val batchesStarted = AtomicInteger(0)

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                batchesStarted.incrementAndGet()
                gate.await()
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
        )

        model.enterForeground()
        model.categoryProgress.first { it.activeKeys.contains(p1.key) }

        // Stop categorization while p1 is in flight
        model.stopCategorization()
        advanceUntilIdle()

        // Progress should show stopped = true and state READY
        val stoppedProgress = model.categoryProgress.value
        assertTrue(stoppedProgress.stopped)
        assertEquals(CategoryAutomationState.READY, stoppedProgress.state)
        assertTrue(stoppedProgress.activeKeys.isEmpty())
        assertTrue(stoppedProgress.queuedKeys.isEmpty())
        assertTrue(CategoryActivity.keys.value.isEmpty())

        // Unblock old gate - cancelled batch must not have committed
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(store.snapshot.value.categories[p1.key])

        // Retry clears stopped and restarts categorization
        val newGate = CompletableDeferred<Unit>()
        val model2 = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
        )
        model.retryAutomaticCategories()
        advanceUntilIdle()

        val resumedProgress = model.categoryProgress.first { it.state == CategoryAutomationState.READY && !it.stopped && it.labeled == 1 }
        assertFalse(resumedProgress.stopped)
        assertEquals("Groceries", store.snapshot.value.categories[p1.key])

        model.lock()
    }

    @Test
    fun queuedKeysAndActiveKeysPublishedAndCategoryActivityMirrorsThem() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val rows = (1..20).map { testPayment("tx-$it") }
        val state = MutableStateFlow(readySpending(rows))

        val gate = CompletableDeferred<Unit>()
        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                gate.await()
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
        )

        model.enterForeground()
        val inFlightProgress = model.categoryProgress.first { it.activeKeys.isNotEmpty() }

        // MAX_BATCH is 8, 2 concurrent batches in flight = 16 active, and 4 queued
        assertEquals(16, inFlightProgress.activeKeys.size)
        assertEquals(4, inFlightProgress.queuedKeys.size)
        assertEquals(inFlightProgress.activeKeys + inFlightProgress.queuedKeys, CategoryActivity.keys.value)

        // Check paymentCategoryState helper
        val activeKey = inFlightProgress.activeKeys.first()
        val queuedKey = inFlightProgress.queuedKeys.first()
        assertEquals(PaymentCategoryState.CATEGORIZING, paymentCategoryState(activeKey, inFlightProgress))
        assertEquals(PaymentCategoryState.QUEUED, paymentCategoryState(queuedKey, inFlightProgress))
        assertEquals(PaymentCategoryState.IDLE, paymentCategoryState("other-key", inFlightProgress))

        // Complete batch
        gate.complete(Unit)
        advanceUntilIdle()

        val readyProgress = model.categoryProgress.first { it.state == CategoryAutomationState.READY && it.labeled == 20 }
        assertTrue(readyProgress.activeKeys.isEmpty())
        assertTrue(readyProgress.queuedKeys.isEmpty())
        assertTrue(CategoryActivity.keys.value.isEmpty())

        model.lock()
    }

    @Test
    fun perKeyCommitKeepsOtherResultsWhenOnePaymentIsEditedMidBatch() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val tx1 = testPayment("tx1", description = "Coffee")
        val tx2 = testPayment("tx2", description = "Groceries")
        val input = store.snapshot.value

        val fp1 = AiCategorization.fingerprint(tx1, input)
        val fp2 = AiCategorization.fingerprint(tx2, input)
        val candidates = listOf(CategoryCandidate(tx1, fp1), CategoryCandidate(tx2, fp2))
        val suggestions = mapOf(
            tx1.key to CategorySuggestion("Dining Out", "AI suggestion: Cafe"),
            tx2.key to CategorySuggestion("Groceries", "AI suggestion: Market"),
        )

        // Simulate user editing note on tx1 while batch was in flight
        store.setUserNote(tx1.key, "User edited note on tx1")

        // Commit the batch with the original input snapshot
        val committedCount = store.commitCategorySuggestions(candidates, suggestions, input)

        // Only tx2 is committed; tx1 was skipped due to per-key note change
        assertEquals(1, committedCount)
        assertNull(store.snapshot.value.categories[tx1.key])
        assertEquals("Groceries", store.snapshot.value.categories[tx2.key])
        assertEquals("User edited note on tx1", store.snapshot.value.userNotes[tx1.key])

        // Now test tx2 becomes manual while tx3 is in flight
        val tx3 = testPayment("tx3", description = "Train")
        val input2 = store.snapshot.value
        val fp3 = AiCategorization.fingerprint(tx3, input2)
        val candidates2 = listOf(CategoryCandidate(tx2, fp2), CategoryCandidate(tx3, fp3))
        val suggestions2 = mapOf(
            tx2.key to CategorySuggestion("Shopping & Hardware", "AI suggestion: Shop"),
            tx3.key to CategorySuggestion("Transport", "AI suggestion: Train"),
        )

        store.setClassification(tx2.key, "Manual Category", "", manual = true)
        val committedCount2 = store.commitCategorySuggestions(candidates2, suggestions2, input2)

        // tx2 was skipped because it became manual; tx3 was committed
        assertEquals(1, committedCount2)
        assertEquals("Manual Category", store.snapshot.value.categories[tx2.key])
        assertEquals("Transport", store.snapshot.value.categories[tx3.key])
    }

    private class FixturePreferences(initial: Map<String, String> = emptyMap()) {
        private val values = mutableMapOf<String, String>("custom_categories" to "[\"Uncategorized\"]").apply { putAll(initial) }
        @Volatile var commitSucceeds = true
        var updateMemoryOnFailure = false
        var beforeCommit: (() -> Unit)? = null
        private val preferences = proxy<SharedPreferences> { name, args ->
            when (name) {
                "getString" -> values[args[0]] ?: args[1]
                "getBoolean" -> values[args[0]]?.toBooleanStrictOrNull() ?: (args[1] as Boolean)
                "contains" -> values.containsKey(args[0])
                "edit" -> editor()
                else -> error("Unexpected preference call: $name")
            }
        }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, String?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy { name, args ->
                when (name) {
                    "putString" -> { changes[args[0] as String] = args[1] as String?; editor }
                    "putBoolean" -> { changes[args[0] as String] = (args[1] as Boolean).toString(); editor }
                    "remove" -> { changes[args[0] as String] = null; editor }
                    "commit" -> {
                        beforeCommit?.invoke()
                        if (commitSucceeds || updateMemoryOnFailure) for ((key, value) in changes) {
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        commitSucceeds
                    }
                    "apply" -> {
                        beforeCommit?.invoke()
                        for ((key, value) in changes) {
                            if (value == null) values.remove(key) else values[key] = value
                        }
                    }
                    else -> error("Unexpected editor call: $name")
                }
            }
            return editor
        }
        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args.orEmpty())
            } as T
    }
}
