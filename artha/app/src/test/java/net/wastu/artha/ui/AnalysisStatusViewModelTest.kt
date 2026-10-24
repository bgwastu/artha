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
        "custom_categories" to org.json.JSONArray(INITIAL_DEFAULT_CATEGORIES).toString(),
    )

    private fun readySpending(rows: List<UnifiedTransaction>): SpendingState =
        SpendingState.Ready(rows, rows.groupingBy { it.provider }.eachCount())

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }







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
            ioDispatcher = dispatcher,
            spendingState = state,
            categorize = { _, _, batch ->
                batch.associate { it.transaction.key to CategorySuggestion("Groceries", "AI suggestion: Food") }
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
