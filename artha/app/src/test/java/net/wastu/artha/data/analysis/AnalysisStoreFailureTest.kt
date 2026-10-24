package net.wastu.artha.data.analysis

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalysisStoreFailureTest {
    @Test
    fun recordAndClearAnalysisFailureRoundTrip() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)

        val key = "2026-10:research"
        val message = "Check the API key"
        val fp = "fp-test-123"

        store.recordAnalysisFailure(key, message, fp)

        val failure = store.analysisFailures.value[key]
        assertNotNull(failure)
        assertEquals(message, failure?.message)
        assertEquals(fp, failure?.fingerprint)
        assertTrue((failure?.at ?: 0L) > 0L)

        // Verify JSON representation in preferences key analysis_failures
        val rawJson = prefs.getString("analysis_failures", null)
        assertNotNull(rawJson)
        val json = JSONObject(rawJson!!)
        assertTrue(json.has(key))
        val entry = json.getJSONObject(key)
        assertEquals(message, entry.getString("message"))
        assertEquals(fp, entry.getString("fingerprint"))
        assertEquals(failure?.at, entry.getLong("at"))

        // Reopening store preserves failures
        val reopened = AnalysisStore(prefs.context)
        val reopenedFailure = reopened.analysisFailures.value[key]
        assertNotNull(reopenedFailure)
        assertEquals(message, reopenedFailure?.message)
        assertEquals(fp, reopenedFailure?.fingerprint)
        assertEquals(failure?.at, reopenedFailure?.at)

        // Clear failure
        store.clearAnalysisFailure(key)
        assertNull(store.analysisFailures.value[key])
        assertNull(AnalysisStore(prefs.context).analysisFailures.value[key])
    }

    @Test
    fun commitMonthSummaryClearsExistingFailure() {
        val prefs = TestPrefs(mapOf("custom_categories" to "[\"Uncategorized\"]"))
        val store = AnalysisStore(prefs.context)

        val key = "2026-10:research"
        store.recordAnalysisFailure(key, "Timeout", "fp-old")
        assertNotNull(store.analysisFailures.value[key])

        val input = store.snapshot.value
        val committed = store.commitMonthSummary(key, "New summary", "fp-new", input)
        assertTrue(committed)
        assertNull(store.analysisFailures.value[key])
        assertNull(AnalysisStore(prefs.context).analysisFailures.value[key])
    }

    @Test
    fun failureCacheIsLocalAndNotOverwrittenByApplyMergedSnapshot() {
        val prefs = TestPrefs()
        val store = AnalysisStore(prefs.context)

        val key = "2026-10:research"
        store.recordAnalysisFailure(key, "Rate limited. Try again", "fp-1")

        val merged = store.snapshot.value.copy(
            knowledge = "New knowledge from sync",
            monthlySummaries = mapOf(key to "Synced summary"),
        )
        store.applyMergedSnapshot(merged)

        // Failure cache must remain intact after applyMergedSnapshot
        val failure = store.analysisFailures.value[key]
        assertNotNull(failure)
        assertEquals("Rate limited. Try again", failure?.message)
    }

    private class TestPrefs(initial: Map<String, String> = emptyMap()) {
        val values = mutableMapOf<String, String>().apply { putAll(initial) }
        fun getString(key: String, def: String?): String? = values[key] ?: def
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
