package net.wastu.artha.data.analysis

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalysisStoreEngineTest {
    private fun payment(id: String = "tx1", description: String = "Test Store", manual: Boolean = false) =
        UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = id,
            direction = Direction.DEBIT,
            money = Money(50000),
            occurredAt = Instant.parse("2026-10-10T10:00:00Z"),
            description = description,
            rawPayload = "{}",
            classificationIsManual = manual,
        )

    @Test
    fun commitCategorySuggestionsStoresConfidenceMerchantConsistentAndClearsFailures() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)
        val tx1 = payment("tx1", "Indomaret")
        val tx2 = payment("tx2", "Tokopedia")

        // Record a failure on tx1 first
        store.recordCategoryFailures(listOf(tx1.key), "Temporary failure")
        assertEquals("Temporary failure", store.snapshot.value.categoryFailures[tx1.key])

        val input = store.snapshot.value
        val fp1 = AiCategorization.fingerprint(tx1, input)
        val fp2 = AiCategorization.fingerprint(tx2, input)
        val candidates = listOf(CategoryCandidate(tx1, fp1), CategoryCandidate(tx2, fp2))

        val suggestions = mapOf(
            tx1.key to CategorySuggestion("Groceries", "AI suggestion: Food", percentOf(CategoryConfidence.HIGH), merchantConsistent = true),
            tx2.key to CategorySuggestion("Shopping & Gear", "AI suggestion: Shop", percentOf(CategoryConfidence.LOW), merchantConsistent = false),
        )

        val committed = store.commitCategorySuggestions(candidates, suggestions, input)
        assertEquals(2, committed)

        val snap = store.snapshot.value
        assertEquals("Groceries", snap.categories[tx1.key])
        assertEquals("Shopping & Gear", snap.categories[tx2.key])
        assertEquals(CategoryConfidence.HIGH, snap.aiConfidence[tx1.key])
        assertEquals(CategoryConfidence.LOW, snap.aiConfidence[tx2.key])
        assertTrue(tx1.key in snap.aiMerchantConsistent)
        assertFalse(tx2.key in snap.aiMerchantConsistent)

        // tx1 failure must be cleared on commit
        assertNull(snap.categoryFailures[tx1.key])

        // Verify re-opening store loads persisted values
        val reopened = AnalysisStore(prefs.context)
        assertEquals(CategoryConfidence.HIGH, reopened.snapshot.value.aiConfidence[tx1.key])
        assertEquals(CategoryConfidence.LOW, reopened.snapshot.value.aiConfidence[tx2.key])
        assertTrue(tx1.key in reopened.snapshot.value.aiMerchantConsistent)
        assertFalse(tx2.key in reopened.snapshot.value.aiMerchantConsistent)
    }

    @Test
    fun commitMerchantSuggestionsSavesCategoriesAndClearsFailures() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)
        val tx = payment("tx_m", "Circle K")

        store.recordCategoryFailures(listOf(tx.key), "Network error")
        assertEquals(1, store.snapshot.value.categoryFailures.size)

        val input = store.snapshot.value
        val suggestions = mapOf(
            tx.key to CategorySuggestion("Groceries", "Same merchant: Same as earlier Circle K payments", percentOf(CategoryConfidence.HIGH), merchantConsistent = true)
        )

        val count = store.commitMerchantSuggestions(suggestions, input, listOf(tx))
        assertEquals(1, count)

        val snap = store.snapshot.value
        assertEquals("Groceries", snap.categories[tx.key])
        assertEquals("Same merchant: Same as earlier Circle K payments", snap.reasons[tx.key])
        assertEquals(CategoryConfidence.HIGH, snap.aiConfidence[tx.key])
        assertNull(snap.categoryFailures[tx.key])
    }

    @Test
    fun typeOverridesAndIncomeCategoriesPersistAndClearedLabelsStayCleared() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)
        val tx = payment("tx_t")
        store.setTypeOverride(tx.key, net.wastu.artha.core.model.TransactionType.TRANSFER)
        store.addCustomCategory("Freelance", net.wastu.artha.core.model.TransactionType.INCOME)
        store.setClassification(tx.key, "Groceries", "picked")
        store.clearClassification(tx.key)

        val reloaded = AnalysisStore(prefs.context).snapshot.value
        assertEquals("TRANSFER", reloaded.typeOverrides[tx.key])
        assertTrue("Freelance" in reloaded.incomeCategories)
        assertFalse("Freelance" in reloaded.customCategories)
        assertNull(reloaded.categories[tx.key])
        assertFalse(tx.key in reloaded.manualCategories)

        store.setTypeOverride(tx.key, null)
        assertNull(store.snapshot.value.typeOverrides[tx.key])
    }

    @Test
    fun setAutoCategorizeTogglesPreference() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)
        assertTrue(store.snapshot.value.autoCategorize)

        store.setAutoCategorize(false)
        assertFalse(store.snapshot.value.autoCategorize)
        assertFalse(AnalysisStore(prefs.context).snapshot.value.autoCategorize)

        store.setAutoCategorize(true)
        assertTrue(store.snapshot.value.autoCategorize)
    }

    @Test
    fun recordAndClearCategoryFailures() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)
        val keys = listOf("key1", "key2")

        store.recordCategoryFailures(keys, "Rate limited")
        assertEquals("Rate limited", store.snapshot.value.categoryFailures["key1"])
        assertEquals("Rate limited", store.snapshot.value.categoryFailures["key2"])

        store.clearCategoryFailures(listOf("key1"))
        assertNull(store.snapshot.value.categoryFailures["key1"])
        assertEquals("Rate limited", store.snapshot.value.categoryFailures["key2"])

        // Setting a manual classification also clears failures for that key
        store.setClassification("key2", "Dining Out", "Manual pick", true)
        assertNull(store.snapshot.value.categoryFailures["key2"])
    }

    @Test
    fun applyMergedSnapshotPreservesLocalFields() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)
        store.recordCategoryFailures(listOf("f1"), "Failure")
        store.setAutoCategorize(false)

        val merged = store.snapshot.value.copy(
            knowledge = "New knowledge from cloud sync",
            aiRecords = aiRecords(failures = emptyMap()),
            autoCategorize = true,
        )

        store.applyMergedSnapshot(merged)
        assertEquals("New knowledge from cloud sync", store.snapshot.value.knowledge)
        assertEquals("Failure", store.snapshot.value.categoryFailures["f1"])
        assertFalse(store.snapshot.value.autoCategorize)
    }

    @Test
    fun commitMerchantSuggestionsStoresFingerprintsForReconciledRows() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)
        val tx = payment("tx_mm", "Circle K").copy(
            rawPayload = """{"groupedTransactions":[{"provider":"JAGO","providerTransactionId":"sub2","amount":2000}]}"""
        )

        val input = store.snapshot.value
        val suggestions = mapOf(
            tx.key to CategorySuggestion("Groceries", "Same merchant: Same as earlier Circle K payments", percentOf(CategoryConfidence.HIGH))
        )

        val count = store.commitMerchantSuggestions(suggestions, input, listOf(tx))
        assertEquals(1, count)

        val expectedFp = AiCategorization.fingerprint(tx, store.snapshot.value)
        assertEquals(expectedFp, store.snapshot.value.aiCategoryFingerprints[tx.key])

        // Verify re-opening store loads persisted fingerprint
        val reopened = AnalysisStore(prefs.context)
        assertEquals(expectedFp, reopened.snapshot.value.aiCategoryFingerprints[tx.key])

        // Needs label must be false because fingerprint matches
        val candidate = CategoryCandidate(tx, expectedFp)
        assertFalse(AiCategorization.needsLabel(candidate, reopened.snapshot.value))

        val (_, pending) = AiCategorization.pending(listOf(tx), reopened.snapshot.value)
        assertEquals(0, pending.size)
    }

    private class TestPrefs(initial: Map<String, String> = emptyMap()) {
        val values = mutableMapOf<String, String>().apply { putAll(initial) }
        fun getString(key: String, def: String?): String? = values[key] ?: def
        fun putString(key: String, value: String) { values[key] = value }
        private val preferences = proxy<SharedPreferences> { name, args ->
            when (name) {
                "getString" -> values[args[0]] ?: args[1]
                "getInt" -> values[args[0]]?.toIntOrNull() ?: (args[1] as Int)
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
                    "putInt" -> { changes[args[0] as String] = (args[1] as Int).toString(); editor }
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
