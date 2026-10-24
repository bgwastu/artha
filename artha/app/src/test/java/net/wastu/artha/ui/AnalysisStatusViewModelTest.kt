package net.wastu.artha.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class AnalysisStatusViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val transactions = MutableStateFlow<List<UnifiedTransaction>>(emptyList())
    private val ledger = object : LedgerRepository {
        override fun observeTransactions() = transactions
        override suspend fun loadTransactions() = transactions.value
        override suspend fun append(transactions: List<UnifiedTransaction>) = AppendResult(transactions.size, 0)
    }

    private val baseInstant = Instant.parse("2026-10-15T12:00:00Z")
    private val fixedClock = Clock.fixed(baseInstant, AccountingTime.zone)
    private val month = YearMonth.of(2026, 10)

    private fun testPayment(id: String = "p1", amount: Long = 50000, manual: Boolean = true) = UnifiedTransaction(
        Provider.JAGO,
        id,
        Direction.DEBIT,
        Money(amount),
        baseInstant,
        "Groceries",
        "{}",
        classificationIsManual = manual,
    )

    private fun configuredPrefs() = mapOf(
        "ai_api_key" to "test-key",
        "analysis_enabled" to "true",
        "ai_base_url" to "https://ai.wastu.net",
        "ai_model" to "gemini-3.8-flash-high",
    )

    private fun readySpending(rows: List<UnifiedTransaction>): SpendingState =
        SpendingState.Ready(rows, rows.groupingBy { it.provider }.eachCount())

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun statusTransitionsFromNeverRunToRunningToUpToDateOnSuccessfulAnalysis() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val payment = testPayment(manual = true)
        val state = MutableStateFlow(readySpending(listOf(payment)))

        val analyzeEntered = CompletableDeferred<Unit>()
        val allowComplete = CompletableDeferred<Unit>()
        val statuses = mutableListOf<MonthAnalysisStatus>()

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            spendingState = state,
            analyzeMonthAction = { _, _, _, _, _, _, onProgress ->
                onProgress(AnalysisStage.EXAMINING)
                analyzeEntered.complete(Unit)
                allowComplete.await()
                MonthAnalysisResult("Monthly summary text")
            },
        )

        val collectJob = launch { model.analysisStatus.collect { statuses.add(it) } }

        model.enterForeground()
        advanceUntilIdle()

        assertTrue("Expected NeverRun in history, got: $statuses", statuses.any { it is MonthAnalysisStatus.NeverRun })
        analyzeEntered.await()
        assertTrue("Expected Running in history, got: $statuses", statuses.any { it is MonthAnalysisStatus.Running })

        allowComplete.complete(Unit)
        model.analysisStatus.first { it is MonthAnalysisStatus.UpToDate }
        advanceUntilIdle()
        assertTrue("Expected final status UpToDate, got: ${statuses.last()}", statuses.last() is MonthAnalysisStatus.UpToDate)
        assertEquals(
            "Monthly summary text",
            store.snapshot.value.monthlySummaries[analysisKey(month)],
        )

        collectJob.cancel()
        model.lock()
        advanceUntilIdle()
    }

    @Test
    fun failureSurfacesAsFailedWithMappedMessageAndSurvivesNewViewModel() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val payment = testPayment(manual = true)
        val state = MutableStateFlow(readySpending(listOf(payment)))

        val model1 = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            spendingState = state,
            analyzeMonthAction = { _, _, _, _, _, _, _ ->
                throw AiHttpException(401)
            },
        )

        model1.enterForeground()
        advanceUntilIdle()

        val status1 = model1.analysisStatus.value
        assertTrue("Expected Failed status, got $status1", status1 is MonthAnalysisStatus.Failed)
        assertEquals("Check the API key", (status1 as MonthAnalysisStatus.Failed).message)
        assertEquals(false, status1.hasSummary)

        // Verify persisted failure in store
        val key = analysisKey(month)
        val failureInStore = store.analysisFailures.value[key]
        assertNotNull(failureInStore)
        assertEquals("Check the API key", failureInStore?.message)

        // Verify a new ViewModel on the same store loads the failure and does not auto-retry
        var model2RanAnalysis = false
        val model2 = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            spendingState = state,
            analyzeMonthAction = { _, _, _, _, _, _, _ ->
                model2RanAnalysis = true
                MonthAnalysisResult("Should not run")
            },
        )

        model2.enterForeground()
        advanceUntilIdle()

        val status2 = model2.analysisStatus.value
        assertTrue("Expected Failed status on new VM, got $status2", status2 is MonthAnalysisStatus.Failed)
        assertEquals("Check the API key", (status2 as MonthAnalysisStatus.Failed).message)
        assertFalse("Automatic retry should be prevented by persisted failure", model2RanAnalysis)

        // Forced manual run still runs
        model2.analyzeMonth(month, forceReanalyze = true)
        advanceUntilIdle()
        assertTrue("Forced re-analyze must run", model2RanAnalysis)

        model1.lock()
        model2.lock()
        advanceUntilIdle()
    }

    @Test
    fun waitingImportWhileRefreshing() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val payment = testPayment(manual = true)
        val state = MutableStateFlow(readySpending(listOf(payment)))

        val importHold = CompletableDeferred<Unit>()

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            spendingState = state,
            ingest = {
                importHold.await()
                emptyMap()
            },
        )

        model.enterForeground()
        advanceUntilIdle()

        assertTrue(model.refreshing.value)
        assertEquals(MonthAnalysisStatus.Waiting(AnalysisWait.IMPORT), model.analysisStatus.value)

        importHold.complete(Unit)
        advanceUntilIdle()

        assertFalse(model.refreshing.value)
        model.lock()
        advanceUntilIdle()
    }

    @Test
    fun categoryProgressCountsAndStatePublishing() = runTest(dispatcher) {
        val p1 = testPayment("tx1", 10000, manual = false)
        val p2 = testPayment("tx2", 20000, manual = false)
        val p3 = testPayment("tx3", 30000, manual = false)
        val pCredit = testPayment("txCredit", 50000, manual = false).copy(direction = Direction.CREDIT)
        val pManual = testPayment("txManual", 60000, manual = true)

        val rows = listOf(p1, p2, p3, pCredit, pManual)
        var snapshot = AnalysisSnapshot(analysisEnabled = true, aiConfig = AiConfig("https://ai.wastu.net", "key", "model"))

        // Initially 3 eligible spending transactions (p1, p2, p3), none labeled
        var (labeled, total) = AiCategorization.progress(rows, snapshot)
        assertEquals(3, total)
        assertEquals(0, labeled)

        // Label p1 with AI fingerprint and reason prefix
        val fp1 = AiCategorization.fingerprint(p1, snapshot)
        snapshot = snapshot.copy(
            aiCategoryFingerprints = mapOf(p1.key to fp1),
            reasons = mapOf(p1.key to "AI suggestion: Groceries"),
        )
        val progressAfterOne = AiCategorization.progress(rows, snapshot)
        assertEquals(3, progressAfterOne.second)
        assertEquals(1, progressAfterOne.first)

        // Mark p2 manual in snapshot
        snapshot = snapshot.copy(manualCategories = setOf(p2.key))
        val progressAfterManual = AiCategorization.progress(rows, snapshot)
        assertEquals(2, progressAfterManual.second)
        assertEquals(1, progressAfterManual.first)

        // In ViewModel: verify categoryProgress flow updates
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val state = MutableStateFlow(readySpending(listOf(p1, p2)))

        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                batch.associate { it.transaction.key to CategorySuggestion("Uncategorized", "AI suggestion: Food") }
            },
        )

        model.enterForeground()
        val progress = model.categoryProgress.first { it.state == CategoryAutomationState.READY && it.labeled == 2 }
        assertEquals(CategoryAutomationState.READY, progress.state)
        assertEquals(2, progress.total)
        assertEquals(2, progress.labeled)
        model.lock()
        advanceUntilIdle()
    }

    @Test
    fun fingerprintDoesNotChangeAcrossDaysOrApiKey() = runTest(dispatcher) {
        val model = ArthaViewModel(
            RootAccess { false },
            { ledger },
            clock = fixedClock,
            computationDispatcher = dispatcher,
        )

        val rows = listOf(testPayment("p1"))
        val snapshot1 = AnalysisSnapshot(
            knowledge = "Personal context",
            aiConfig = AiConfig("https://ai.wastu.net", "api-key-1", "gemini-3.8-flash-high"),
            customCategories = listOf("Groceries", "Dining Out"),
            monthlyNotes = mapOf(month.toString() to "October note"),
        )

        val fp1 = model.fingerprint(month, rows, snapshot1)

        // Different API key must NOT change fingerprint
        val snapshotDifferentKey = snapshot1.copy(
            aiConfig = snapshot1.aiConfig.copy(apiKey = "completely-different-key"),
        )
        val fpKey = model.fingerprint(month, rows, snapshotDifferentKey)
        assertEquals("Fingerprint must be independent of API key", fp1, fpKey)

        // Different clock/day must NOT change fingerprint
        val dayLaterClock = Clock.fixed(Instant.parse("2026-10-28T09:30:00Z"), AccountingTime.zone)
        val modelDayLater = ArthaViewModel(
            RootAccess { false },
            { ledger },
            clock = dayLaterClock,
            computationDispatcher = dispatcher,
        )
        val fpDayLater = modelDayLater.fingerprint(month, rows, snapshot1)
        assertEquals("Fingerprint must be independent of today's date", fp1, fpDayLater)

        // Content changes MUST change fingerprint
        val fpDifferentNote = model.fingerprint(month, rows, snapshot1.copy(monthlyNotes = mapOf(month.toString() to "Changed note")))
        assertNotEquals("Different note must produce different fingerprint", fp1, fpDifferentNote)

        val fpDifferentContext = model.fingerprint(month, rows, snapshot1.copy(knowledge = "Different knowledge"))
        assertNotEquals("Different knowledge must produce different fingerprint", fp1, fpDifferentContext)

        val fpDifferentCategories = model.fingerprint(month, rows, snapshot1.copy(customCategories = listOf("Tech")))
        assertNotEquals("Different categories must produce different fingerprint", fp1, fpDifferentCategories)

        val fpDifferentModel = model.fingerprint(month, rows, snapshot1.copy(aiConfig = snapshot1.aiConfig.copy(model = "gpt-4o")))
        assertNotEquals("Different model must produce different fingerprint", fp1, fpDifferentModel)

        val fpDifferentUrl = model.fingerprint(month, rows, snapshot1.copy(aiConfig = snapshot1.aiConfig.copy(baseUrl = "https://other.ai")))
        assertNotEquals("Different baseUrl must produce different fingerprint", fp1, fpDifferentUrl)
    }

    @Test
    fun silentDiscardReschedulesAtMostOncePerCallChain() = runTest(dispatcher) {
        val prefs = FixturePreferences(configuredPrefs())
        val store = AnalysisStore(prefs.context)
        val payment = testPayment(manual = true)
        val state = MutableStateFlow(readySpending(listOf(payment)))

        var attempts = 0
        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            clock = fixedClock,
            analysisStore = store,
            computationDispatcher = dispatcher,
            spendingState = state,
            analyzeMonthAction = { _, _, _, _, _, _, _ ->
                attempts++
                if (attempts == 1) {
                    // Invalidate commitMonthSummary input snapshot on attempt 1 to simulate mid-run change
                    store.setMonthlyNote(month.toString(), "Changed note mid-run")
                }
                MonthAnalysisResult("Summary attempt $attempts")
            },
        )

        model.enterForeground()
        model.analysisStatus.first { it is MonthAnalysisStatus.UpToDate }
        advanceUntilIdle()

        // First attempt ran and failed commit, rescheduled once (attempt 2), then succeeded
        assertEquals(2, attempts)
        model.lock()
        advanceUntilIdle()
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
