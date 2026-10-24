package net.wastu.artha.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
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
class CategoryQueueTest {
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

    private fun testPayment(
        id: String,
        amount: Long = 50000,
        manual: Boolean = false,
        instant: Instant = baseInstant,
        description: String = "Starbucks",
        category: String? = null,
        direction: Direction = Direction.DEBIT,
    ) = UnifiedTransaction(
        Provider.JAGO,
        id,
        direction,
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
    )

    private fun readySpending(rows: List<UnifiedTransaction>): SpendingState =
        SpendingState.Ready(rows, rows.groupingBy { it.provider }.eachCount())

    private fun createModel(
        store: AnalysisStore,
        state: StateFlow<SpendingState>? = null,
        categorize: suspend (AiConfig, AnalysisSnapshot, List<CategoryCandidate>) -> Map<String, CategorySuggestion> = { _, _, batch -> batch.associate { it.transaction.key to CategorySuggestion("Uncategorized", "AI suggestion: Default") } },
        fromMemoryAction: (List<UnifiedTransaction>, AnalysisSnapshot) -> Map<String, CategorySuggestion> = { _, _ -> emptyMap() },
        scopeKeysAction: (RecategorizeScope, List<UnifiedTransaction>, AnalysisSnapshot) -> Set<String> = { scope, _, _ ->
            when (scope) {
                is RecategorizeScope.Payment -> setOf(scope.key)
                else -> emptySet()
            }
        },
        needsReviewAction: (UnifiedTransaction, AnalysisSnapshot) -> Boolean = { _, _ -> false },
        merchantKeyProvider: (UnifiedTransaction) -> String? = { it.description.lowercase().trim() },
    ) = ArthaViewModel(
        RootAccess { true },
        { ledger },
        clock = fixedClock,
        analysisStore = store,
        computationDispatcher = dispatcher,
        ioDispatcher = dispatcher,
        spendingState = state,
        categorize = categorize,
        fromMemoryAction = fromMemoryAction,
        scopeKeysAction = scopeKeysAction,
        needsReviewAction = needsReviewAction,
        merchantKeyProvider = merchantKeyProvider,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun priorityManualAndSelectedMonthFirst() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val pOctNewer = testPayment("oct-new", instant = Instant.parse("2026-10-20T10:00:00Z"))
        val pOctOlder = testPayment("oct-old", instant = Instant.parse("2026-10-05T10:00:00Z"))
        val pSepNewer = testPayment("sep-new", instant = Instant.parse("2026-09-25T10:00:00Z"))
        val pSepOlder = testPayment("sep-old", instant = Instant.parse("2026-09-10T10:00:00Z"))

        val state = MutableStateFlow(readySpending(listOf(pOctOlder, pSepOlder, pOctNewer, pSepNewer)))
        val categorizedOrder = mutableListOf<String>()

        val model = createModel(
            store = store,
            state = state,
            categorize = { _, _, batch ->
                categorizedOrder.addAll(batch.map { it.transaction.key })
                batch.associate { it.transaction.key to CategorySuggestion("Dining Out", "AI suggestion: Cafe") }
            },
        )
        model.setAnalysisMonth(currentMonth)
        // Manual recategorize requested for sep-old
        model.recategorize(RecategorizeScope.Payment(pSepOlder.key))
        model.enterForeground()
        advanceUntilIdle()

        // Manual run first: sep-old, then selected month newest first: oct-new, oct-old, then rest: sep-new
        assertEquals(pSepOlder.key, categorizedOrder.first())
        assertEquals(listOf(pSepOlder.key, pOctNewer.key, pOctOlder.key, pSepNewer.key), categorizedOrder)
        model.lock()
    }

    @Test
    fun maxTwoBatchesConcurrent() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val rows = (1..16).map { testPayment("tx-$it", amount = 10000L * it) }
        val state = MutableStateFlow(readySpending(rows))

        val activeConcurrent = AtomicInteger(0)
        var maxObserved = 0
        val batchGates = mutableListOf<CompletableDeferred<Unit>>()

        val model = createModel(
            store = store,
            state = state,
            categorize = { _, _, batch ->
                val gate = CompletableDeferred<Unit>()
                synchronized(batchGates) { batchGates.add(gate) }
                val count = activeConcurrent.incrementAndGet()
                synchronized(this@CategoryQueueTest) {
                    maxObserved = maxOf(maxObserved, count)
                }
                gate.await()
                activeConcurrent.decrementAndGet()
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
        )

        model.enterForeground()
        // Wait until 2 batches launch concurrently
        while (synchronized(batchGates) { batchGates.size } < 2) {
            advanceTimeBy(10)
        }
        assertEquals(2, maxObserved)

        // Release first batch
        synchronized(batchGates) { batchGates.removeAt(0) }.complete(Unit)
        advanceUntilIdle()
        // Release remaining batch
        while (synchronized(batchGates) { batchGates.isNotEmpty() }) {
            synchronized(batchGates) { batchGates.removeAt(0) }.complete(Unit)
            advanceUntilIdle()
        }

        assertEquals(2, maxObserved)
        model.lock()
    }

    @Test
    fun backoffContinuesOtherBatchesUsingVirtualTime() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val failingRows = (1..8).map { testPayment("tx-fail-$it") }
        val successRows = (1..4).map { testPayment("tx-ok-$it") }
        val state = MutableStateFlow(readySpending(failingRows + successRows))

        var failAttempts = 0
        val successProcessed = CompletableDeferred<Unit>()

        val model = createModel(
            store = store,
            state = state,
            categorize = { _, _, batch ->
                if (batch.any { it.transaction.key.startsWith("JAGO:tx-fail") }) {
                    failAttempts++
                    throw java.io.IOException("AI network timeout")
                } else {
                    successProcessed.complete(Unit)
                    batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
                }
            },
        )

        model.enterForeground()
        // Initial run: failing batch fails once with 30s backoff
        // Meanwhile, other batch runs and succeeds!
        advanceTimeBy(100)
        assertEquals(1, failAttempts)
        assertTrue(successProcessed.isCompleted)

        // Advance 30 s: attempt 2 fails (2 min backoff)
        advanceTimeBy(30_000L)
        assertEquals(2, failAttempts)

        // Advance 2 min: attempt 3 fails (10 min backoff)
        advanceTimeBy(120_000L)
        assertEquals(3, failAttempts)

        // Advance 10 min: attempt 4 fails (exhausts all backoff attempts!)
        advanceTimeBy(600_000L)
        assertEquals(4, failAttempts)

        advanceUntilIdle()
        // After last attempt, failures are recorded in store and no further attempts are made
        assertEquals(8, store.snapshot.value.categoryFailures.size)
        advanceTimeBy(1_000_000L)
        assertEquals(4, failAttempts)

        // Progress is READY since all remaining work is either labeled or recorded as failed
        val progress = model.categoryProgress.value
        assertEquals(CategoryAutomationState.READY, progress.state)
        assertEquals(4, progress.labeled)
        assertEquals(8, progress.failed)

        model.lock()
    }

    @Test
    fun nonRetryablePausesQueue() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val payment = testPayment("tx-auth-fail")
        val state = MutableStateFlow(readySpending(listOf(payment)))

        val model = createModel(
            store = store,
            state = state,
            categorize = { _, _, _ ->
                throw AiHttpException(401)
            },
        )

        model.enterForeground()
        advanceUntilIdle()

        val progress = model.categoryProgress.value
        assertEquals(CategoryAutomationState.ERROR, progress.state)
        assertEquals("Check the API key", progress.error)

        // Time advance does NOT retry non-retryable errors
        advanceTimeBy(600_000L)
        assertEquals(CategoryAutomationState.ERROR, model.categoryProgress.value.state)

        model.lock()
    }

    @Test
    fun recategorizeRunsWhilePausedAndWhenAutoCategorizeOff() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val payment1 = testPayment("tx-1")
        val payment2 = testPayment("tx-2")
        val state = MutableStateFlow(readySpending(listOf(payment1, payment2)))

        var attempts = 0
        val manualSuccess = CompletableDeferred<Unit>()

        val model = createModel(
            store = store,
            state = state,
            categorize = { _, _, batch ->
                attempts++
                if (batch.any { it.transaction.key == payment1.key }) {
                    throw AiHttpException(403)
                } else {
                    manualSuccess.complete(Unit)
                    batch.associate { it.transaction.key to CategorySuggestion("Transport", "AI suggestion: Ride") }
                }
            },
        )

        model.enterForeground()
        advanceUntilIdle()
        assertEquals(CategoryAutomationState.ERROR, model.categoryProgress.value.state)

        // Now run recategorize on payment2: runs while paused!
        model.recategorize(RecategorizeScope.Payment(payment2.key))
        advanceUntilIdle()
        assertTrue(manualSuccess.isCompleted)
        assertEquals("Transport", store.snapshot.value.categories[payment2.key])

        // Now test autoCategorize off
        model.setAutoCategorize(false)
        advanceUntilIdle()
        assertTrue(model.categoryProgress.value.paused)

        var manualInPausedRun = false
        val manualPayment = testPayment("tx-manual-while-paused")
        state.value = readySpending(listOf(payment1, payment2, manualPayment))

        val model2 = createModel(
            store = store,
            state = state,
            categorize = { _, _, batch ->
                if (batch.any { it.transaction.key == manualPayment.key }) {
                    manualInPausedRun = true
                }
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
        )
        model2.enterForeground()
        advanceUntilIdle()
        assertFalse(manualInPausedRun)

        // Explicit recategorize DOES run while autoCategorize is off
        model2.recategorize(RecategorizeScope.Payment(manualPayment.key))
        advanceUntilIdle()
        assertTrue(manualInPausedRun)
        model.lock()
        model2.lock()
    }

    @Test
    fun replaceManualBehavior() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val payment = testPayment("tx-pick", manual = true)
        val state = MutableStateFlow(readySpending(listOf(payment)))
        store.setClassification(payment.key, "Custom Pick", "", manual = true)
        assertTrue(payment.key in store.snapshot.value.manualCategories)

        val model = createModel(
            store = store,
            state = state,
            categorize = { _, _, batch ->
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Fresh market") }
            },
        )

        model.enterForeground()
        advanceUntilIdle()
        // Normal automatic categorizer does not touch manual picks
        assertEquals("Custom Pick", store.snapshot.value.categories[payment.key])

        // Explicit recategorize with replaceManual = true
        model.recategorize(RecategorizeScope.Payment(payment.key, replaceManual = true))
        advanceUntilIdle()

        // AI category replaces manual pick and key is removed from manualCategories
        assertFalse(payment.key in store.snapshot.value.manualCategories)
        assertEquals("Groceries", store.snapshot.value.categories[payment.key])
        assertEquals("AI suggestion: Fresh market", store.snapshot.value.reasons[payment.key])

        model.lock()
    }

    @Test
    fun applyToMerchantCountAndPeerCount() = runTest(dispatcher) {
        val prefs = FixturePreferences()
        val store = AnalysisStore(prefs.context)
        store.setClassification("JAGO:tx4", "Dining Out", "", manual = false)
        store.setClassification("JAGO:tx3", "Shopping", "", manual = false)
        val tx1 = testPayment("tx1", description = "Starbucks", category = "Dining Out", manual = true)
        val tx2 = testPayment("tx2", description = "Starbucks", category = "Uncategorized", manual = false)
        val tx3 = testPayment("tx3", description = "Starbucks", category = "Shopping", manual = false)
        val tx4SameCat = testPayment("tx4", description = "Starbucks", category = "Dining Out", manual = false)
        val tx5Credit = testPayment("tx5", description = "Starbucks", category = "Uncategorized", direction = Direction.CREDIT)
        val tx6OtherMerchant = testPayment("tx6", description = "Grab", category = "Uncategorized", manual = false)

        val allRows = listOf(tx1, tx2, tx3, tx4SameCat, tx5Credit, tx6OtherMerchant)
        val state = MutableStateFlow(readySpending(allRows))

        val model = createModel(
            store = store,
            state = state,
        )
        model.enterForeground()
        advanceUntilIdle()

        // Expected peers: tx2 and tx3
        val count = model.merchantPeerCount(tx1, "Dining Out")
        assertEquals(2, count)

        val applyResult = CompletableDeferred<Result<Int>>()
        model.applyToMerchant(tx1, "Dining Out") { applyResult.complete(it) }
        advanceUntilIdle()

        val reportedCount = applyResult.await().getOrThrow()
        assertEquals(2, reportedCount)
        assertEquals("Dining Out", store.snapshot.value.categories[tx2.key])
        assertEquals("Dining Out", store.snapshot.value.categories[tx3.key])
        assertTrue(tx2.key in store.snapshot.value.manualCategories)
        assertTrue(tx3.key in store.snapshot.value.manualCategories)

        model.lock()
    }

    @Test
    fun progressCountsAccuracy() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val p1 = testPayment("p1")
        val p2 = testPayment("p2")
        val p3 = testPayment("p3")
        val state = MutableStateFlow(readySpending(listOf(p1, p2, p3)))

        val model = createModel(
            store = store,
            state = state,
            needsReviewAction = { tx, _ -> tx.key == p2.key },
            categorize = { _, _, batch ->
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
            },
        )

        model.enterForeground()
        val progress = model.categoryProgress.first { it.state == CategoryAutomationState.READY && it.total == 3 }

        assertEquals(CategoryAutomationState.READY, progress.state)
        assertEquals(3, progress.total)
        assertEquals(3, progress.labeled)
        assertEquals(1, progress.review) // p2 is needsReview
        assertEquals(0, progress.failed)
        assertFalse(progress.paused)
        assertTrue(progress.activeKeys.isEmpty())

        model.lock()
    }

    @Test
    fun merchantMemoryAnswersCommittedDirectlyNoNetwork() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)

        val pMemory = testPayment("p-memory")
        val pAi = testPayment("p-ai")
        val state = MutableStateFlow(readySpending(listOf(pMemory, pAi)))

        val networkCalls = mutableListOf<String>()

        val model = createModel(
            store = store,
            state = state,
            fromMemoryAction = { _, _ ->
                mapOf(pMemory.key to CategorySuggestion("Dining Out", "Same merchant: Starbucks"))
            },
            categorize = { _, _, batch ->
                networkCalls.addAll(batch.map { it.transaction.key })
                batch.associate { it.transaction.key to CategorySuggestion("Transport", "AI suggestion: Ride") }
            },
        )

        model.enterForeground()
        model.categoryProgress.first { it.state == CategoryAutomationState.READY && it.total == 2 }

        // Merchant memory answer was committed directly without network
        assertEquals("Dining Out", store.snapshot.value.categories[pMemory.key])
        assertEquals("Same merchant: Starbucks", store.snapshot.value.reasons[pMemory.key])

        // Only pAi was called via network!
        assertEquals(listOf(pAi.key), networkCalls)

        model.lock()
    }

    private class FixturePreferences(initial: Map<String, String> = emptyMap()) {
        private val values = mutableMapOf<String, String>(
            "custom_categories" to "[\"Uncategorized\", \"Groceries\", \"Dining Out\", \"Transport\", \"Shopping\"]"
        ).apply { putAll(initial) }

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
                    "commit", "apply" -> {
                        for ((key, value) in changes) {
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        true
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
