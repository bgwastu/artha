package net.wastu.artha.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.saveable.SaverScope
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.analysis.*
import net.wastu.artha.data.sync.SyncMerger
import net.wastu.artha.ui.screens.linkedRecordsExplanation
import net.wastu.artha.ui.screens.linkedSourceRole
import net.wastu.artha.ui.screens.transactionAccountingLabel
import net.wastu.artha.ui.screens.TransactionDetailsState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionEditFeedbackTest {
    @Test fun absentStoreReportsFailureForBothEditors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = model()
            val note = CompletableDeferred<Result<Unit>>()
            val category = CompletableDeferred<Result<Unit>>()
            model.setUserNote(payment(), "First line\nSecond line") { note.complete(it) }
            model.setTransactionCategory(payment(), "Fixture category") { category.complete(it) }
            assertTrue(note.await().isFailure)
            assertTrue(category.await().isFailure)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun noteFeedbackWaitsForCommitAndReopeningKeepsMultilineContext() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = FixturePreferences()
        val finish = CountDownLatch(1)
        try {
            val store = AnalysisStore(prefs.context)
            val committing = CompletableDeferred<Unit>()
            prefs.beforeCommit = {
                committing.complete(Unit)
                check(finish.await(5, TimeUnit.SECONDS))
            }
            val saved = CompletableDeferred<Result<Unit>>()
            model(store).setUserNote(payment(), "First line\nSecond line") { saved.complete(it) }
            committing.await()
            assertFalse(saved.isCompleted)
            assertTrue(store.snapshot.value.userNotes.isEmpty())
            finish.countDown()
            assertTrue(saved.await().isSuccess)
            assertEquals("First line\nSecond line", AnalysisStore(prefs.context).snapshot.value.userNotes[payment().key])
        } finally {
            finish.countDown()
            Dispatchers.resetMain()
        }
    }

    @Test fun failedCommitKeepsPreviousEditsAndRetrySavesTheSameDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = FixturePreferences()
            val store = AnalysisStore(prefs.context)
            val model = model(store)
            store.setUserNote(payment().key, "Original context")
            store.setClassification(payment().key, "Original category", "", manual = true)
            val original = store.snapshot.value
            prefs.commitSucceeds = false
            val note = CompletableDeferred<Result<Unit>>()
            val category = CompletableDeferred<Result<Unit>>()
            val draft = "Draft with a second line\nKept for retry"
            model.setUserNote(payment(), draft) { note.complete(it) }
            model.setTransactionCategory(payment(), "A long fictional manual category name") { category.complete(it) }
            assertTrue(note.await().isFailure)
            assertTrue(category.await().isFailure)
            assertEquals(original, store.snapshot.value)
            prefs.commitSucceeds = true
            val retry = CompletableDeferred<Result<Unit>>()
            model.setUserNote(payment(), draft) { retry.complete(it) }
            assertTrue(retry.await().isSuccess)
            assertEquals(draft, store.snapshot.value.userNotes[payment().key])
            assertEquals("Original category", store.snapshot.value.categories[payment().key])
        } finally { Dispatchers.resetMain() }
    }

    @Test fun categorySuccessPersistsManualProtectionAndExceptionsReachTheCallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = FixturePreferences()
            val store = AnalysisStore(prefs.context)
            val saved = CompletableDeferred<Result<Unit>>()
            model(store).setTransactionCategory(payment(), "Fictional chosen category") { saved.complete(it) }
            assertTrue(saved.await().isSuccess)
            val reopened = AnalysisStore(prefs.context).snapshot.value
            assertEquals("Fictional chosen category", reopened.categories[payment().key])
            assertTrue(payment().key in reopened.manualCategories)
            val schemaFailure = IllegalStateException("Fictional schema failure")
            prefs.beforeCommit = { throw schemaFailure }
            val failed = CompletableDeferred<Result<Unit>>()
            model(store).setUserNote(payment(), "Kept draft") { failed.complete(it) }
            assertSame(schemaFailure, failed.await().exceptionOrNull())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun manualChoiceAndNoteSurviveFreshFactsAutomaticInsightsAndOlderRestore() {
        val tx = payment()
        val chosen = AnalysisSnapshot(
            userNotes = mapOf(tx.key to "First line\nSecond line"),
            categories = mapOf(tx.key to "A long fictional manual category name"),
            manualCategories = setOf(tx.key), updatedAt = mapOf("cat:${tx.key}" to 20, "note:${tx.key}" to 20),
        )
        val automated = TransactionMetadata.applyAutomatic(chosen, mapOf(tx.key to ("Dining Out" to "Suggestion")), 30)
        val restored = SyncMerger.mergeAnalysis(automated, AnalysisSnapshot(
            categories = mapOf(tx.key to "Older category"), userNotes = mapOf(tx.key to "Older note"),
            updatedAt = mapOf("cat:${tx.key}" to 10, "note:${tx.key}" to 10),
        ))
        val projector = SpendingProjector()
        val first = projector.project(listOf(tx), restored).transactions.single()
        val fresh = projector.project(listOf(tx.copy(description = "Updated fictional payment")), restored).transactions.single()
        for (record in listOf(first, fresh)) {
            assertEquals("A long fictional manual category name", record.category)
            assertEquals("First line\nSecond line", record.userNote)
            assertTrue(record.classificationIsManual)
            assertEquals("Included in spending", transactionAccountingLabel(record))
        }
    }

    @Test fun linkedCountOnceRequiresReconciledPaymentAndUnknownRolesStayNeutral() {
        val tx = payment().copy(rawPayload = """{"groupedTransactions":[{"provider":"GRAB"},{"provider":"JAGO"}]}""")
        assertNull(linkedRecordsExplanation(tx))
        assertEquals("Counted once in spending.", linkedRecordsExplanation(tx.copy(canonicalKey = "JAGO:fixture")))
        assertNull(linkedRecordsExplanation(tx.copy(canonicalKey = "JAGO:fixture", isReversal = true)))
        assertEquals("Source record", linkedSourceRole(JSONObject("""{"provider":"GOJEK","role":"Bank Payment"}""")))
        assertEquals("Source record", linkedSourceRole(JSONObject("""{"provider":"GRAB","role":"Trip / Ride"}""")))
        assertEquals("Order details", linkedSourceRole(JSONObject("""{"provider":"SHOPEE","role":"Marketplace Order"}""")))
    }

    @Test fun accountingLabelUsesFactsAndPreservesRecordedReversalAmount() {
        val tx = payment().copy(category = "Investments & Transfers")
        assertEquals("Included in spending", transactionAccountingLabel(tx))
        val reversed = tx.copy(isReversal = true)
        assertEquals("Reversed", transactionAccountingLabel(reversed))
        assertEquals(tx.money, reversed.money)
        assertEquals("Wallet funding", transactionAccountingLabel(tx.copy(rawPayload = """{"isWalletFunding":true}""")))
        assertEquals("Pocket transfer", transactionAccountingLabel(tx.copy(rawPayload = """{"isPocketTransfer":true}""")))
    }

    @Test fun editorStateRestoreRetainsDraftAndRecoveryWithoutStrandingSaving() {
        val state = TransactionDetailsState().apply {
            editor = "note"
            noteDraft = "Unsaved first line\nUnsaved second line"
            categoryDraft = "Long fictional chosen category"
            saveError = "Couldn’t save note. Try again."
            linkedExpanded = true
            sourceExpanded = true
            saving = true
        }
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(TransactionDetailsState.saver) { scope.save(state) }
        val restored = TransactionDetailsState.saver.restore(saved!!)!!
        assertEquals("note", restored.editor)
        assertEquals(state.noteDraft, restored.noteDraft)
        assertEquals(state.categoryDraft, restored.categoryDraft)
        assertEquals(state.saveError, restored.saveError)
        assertTrue(restored.linkedExpanded)
        assertTrue(restored.sourceExpanded)
        assertFalse(restored.saving)
    }

    private fun model(store: AnalysisStore? = null) = ArthaViewModel(
        RootAccess { false }, { error("Editing must not open the ledger") }, analysisStore = store,
    )

    @Test fun failedCommitRestoresAndroidPreferenceMemoryAsWellAsPublishedSnapshot() {
        val prefs = FixturePreferences()
        val store = AnalysisStore(prefs.context)
        store.setUserNote(payment().key, "Original note")
        store.setClassification(payment().key, "Original category", "", manual = true)
        val original = store.snapshot.value
        prefs.updateMemoryOnFailure = true
        prefs.commitSucceeds = false
        assertTrue(runCatching { store.setUserNote(payment().key, "Failed draft") }.isFailure)
        assertTrue(runCatching { store.setClassification(payment().key, "Failed category", "", manual = true) }.isFailure)
        assertEquals(original, store.snapshot.value)
        assertEquals(original.userNotes, AnalysisStore(prefs.context).snapshot.value.userNotes)
        assertEquals(original.categories, AnalysisStore(prefs.context).snapshot.value.categories)
    }

    @Test fun aiConfigurationCommitFailureRetainsPreviousConfiguration() {
        val prefs = FixturePreferences()
        val store = AnalysisStore(prefs.context)
        // Empty secret uses no Android KeyStore; this tests the atomic metadata/secret edit.
        store.setAiConfig(AiConfig("https://fictional.example/v1", "", "old-model"))
        val original = store.snapshot.value
        prefs.updateMemoryOnFailure = true
        prefs.commitSucceeds = false
        assertTrue(runCatching { store.setAiConfig(AiConfig("https://other.example/v1", "", "new-model")) }.isFailure)
        assertEquals(original, store.snapshot.value)
        assertEquals(original.aiConfig, AnalysisStore(prefs.context).snapshot.value.aiConfig)
    }

    @Test fun aiCategoryCommitIsAtomicCachedAndCannotOverwriteAManualEdit() {
        val prefs = FixturePreferences()
        val store = AnalysisStore(prefs.context)
        val tx = payment()
        val input = store.snapshot.value
        val candidates = AiCategorization.nextBatch(listOf(tx), input)
        val suggestions = mapOf(tx.key to CategorySuggestion("Uncategorized", "AI suggestion: Fixture"))
        prefs.updateMemoryOnFailure = true; prefs.commitSucceeds = false
        assertTrue(runCatching { store.commitCategorySuggestions(candidates, suggestions, input) }.isFailure)
        assertEquals(input, store.snapshot.value)
        assertEquals(input.categories, AnalysisStore(prefs.context).snapshot.value.categories)
        prefs.commitSucceeds = true
        assertEquals(1, store.commitCategorySuggestions(candidates, suggestions, input))
        assertTrue(AiCategorization.nextBatch(listOf(tx), store.snapshot.value).isEmpty())
        store.setClassification(tx.key, "Chosen category", "", true)
        assertEquals(0, store.commitCategorySuggestions(candidates, suggestions, input))
        assertEquals("Chosen category", store.snapshot.value.categories[tx.key])
    }

    @Test fun changedContextAndDuplicateCategoryCreationAreRecoverable() {
        val prefs = FixturePreferences()
        val store = AnalysisStore(prefs.context)
        val input = store.snapshot.value
        val candidates = AiCategorization.nextBatch(listOf(payment()), input)
        store.setUserNote(payment().key, "New manual context")
        assertEquals(0, store.commitCategorySuggestions(candidates, mapOf(payment().key to CategorySuggestion("Uncategorized", "AI suggestion: Fixture")), input))
        prefs.commitSucceeds = false
        assertTrue(runCatching { store.addCustomCategory("New category") }.isFailure)
        assertFalse("New category" in store.snapshot.value.customCategories)
        prefs.commitSucceeds = true
        store.addCustomCategory("New category")
        assertTrue(runCatching { store.addCustomCategory(" new CATEGORY ") }.isFailure)
        assertEquals(1, store.snapshot.value.customCategories.count { it == "New category" })
    }

    @Test fun automaticAiCompletesMultipleBatchesAndPreservesSubsequentManualChoice() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var vm: ArthaViewModel? = null
        try {
            val prefs = FixturePreferences(mapOf("ai_base_url" to "https://fictional.example/v1", "ai_api_key" to "fictional-test-key", "ai_model" to "fictional-model"))
            val store = AnalysisStore(prefs.context)
            val state = MutableStateFlow<SpendingState>(SpendingState.Ready((0..32).map { payment().copy(providerTransactionId = if (it == 0) payment().providerTransactionId else "fixture-$it") }, emptyMap()))
            val called = CompletableDeferred<Unit>()
            var calls = 0
            vm = ArthaViewModel(RootAccess { true }, { error("Uses fixture state") }, analysisStore = store,
                ingest = { CompletableDeferred<Unit>().await(); emptyMap() },
                computationDispatcher = dispatcher, spendingState = state, categorize = { _, _, batch ->
                    calls++; if (calls == 2) called.complete(Unit)
                    batch.associate { it.transaction.key to CategorySuggestion("Uncategorized", "AI suggestion: Fixture") }
                })
            vm.enterForeground(); called.await()
            // Publishing the IO commit precedes the main-thread completion state.
            store.snapshot.first { it.aiCategoryFingerprints.size == 33 }
            vm.categoryAutomation.first { it == CategoryAutomationState.READY }
            advanceUntilIdle()
            assertEquals(2, calls)
            assertEquals(CategoryAutomationState.READY, vm.categoryAutomation.value)
            assertTrue(vm.refreshing.value) // Saved payments are categorized while the provider is still blocked.
            store.setClassification(payment().key, "Manual", "", true)
            store.setUserNote(payment().key, "New context")
            advanceUntilIdle()
            assertEquals(2, calls)
            assertEquals("Manual", store.snapshot.value.categories[payment().key])
        } finally { vm?.lock(); Dispatchers.resetMain() }
    }

    @Test fun categoryTapWaitsForPersistenceAndBlocksRepeatedSubmission() {
        val state = TransactionDetailsState().apply { editor = "category" }
        var callback: ((Result<Unit>) -> Unit)? = null
        var writes = 0
        var closes = 0
        val persist: (String, (Result<Unit>) -> Unit) -> Unit = { _, complete -> writes++; callback = complete }
        state.chooseCategory("New category", "Current", persist) { closes++ }
        assertTrue(state.saving)
        assertNull(state.savedFeedback)
        assertEquals(0, closes)
        state.chooseCategory("Another category", "Current", persist) { closes++ }
        assertEquals(1, writes)
        callback!!(Result.success(Unit))
        assertFalse(state.saving)
        assertEquals(1, closes)
    }

    @Test fun failedCategoryTapRetainsChoiceForRetryWithoutClosing() {
        val state = TransactionDetailsState().apply { editor = "category" }
        var closes = 0
        state.chooseCategory("Chosen category", "Current", { _, complete -> complete(Result.failure(Exception("Fixture"))) }) { closes++ }
        assertFalse(state.saving)
        assertNotNull(state.saveError)
        assertNull(state.savedFeedback)
        assertEquals("Chosen category", state.categoryDraft)
        assertEquals(0, closes)
        state.chooseCategory(state.categoryDraft, "Current", { chosen, complete ->
            assertEquals("Chosen category", chosen); complete(Result.success(Unit))
        }) { closes++ }
        assertEquals(1, closes)
    }

    @Test fun tappingCurrentCategoryClosesWithoutWriting() {
        val state = TransactionDetailsState().apply { editor = "category" }
        var closes = 0
        state.chooseCategory("Current", "Current", { _, _ -> error("No redundant write") }) { closes++ }
        assertEquals(1, closes)
        assertFalse(state.saving)
    }

    private fun payment() = UnifiedTransaction(
        Provider.JAGO, "fictional-payment", Direction.DEBIT, Money(10000), Instant.EPOCH, "Fictional payment", "{}",
    )

    /** Only editor writes are exercised; no Android storage, credentials or source data. */
    private class FixturePreferences(initial: Map<String, String> = emptyMap()) {
        private val values = mutableMapOf<String, String>("custom_categories" to "[\"Uncategorized\"]").apply { putAll(initial) }
        @Volatile var commitSucceeds = true
        var updateMemoryOnFailure = false
        var beforeCommit: (() -> Unit)? = null
        private val preferences = proxy<SharedPreferences> { name, args ->
            when (name) {
                "getString" -> values[args[0]] ?: args[1]
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
                    "remove" -> { changes[args[0] as String] = null; editor }
                    "commit" -> {
                        beforeCommit?.invoke()
                        if (commitSucceeds || updateMemoryOnFailure) for ((key, value) in changes) {
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        commitSucceeds
                    }
                    "apply" -> error("Save feedback requires a checked commit")
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
