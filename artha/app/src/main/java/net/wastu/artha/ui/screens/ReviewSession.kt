package net.wastu.artha.ui.screens

import org.json.JSONArray
import org.json.JSONObject

/** One move through the review: a category applied to [key] (and [similar]), or a skip when [category] is null. */
internal data class ReviewStep(val key: String, val similar: List<String>, val category: String?)

/**
 * Pure state of the one-by-one review. Decisions persist across [back], so stepping back shows the earlier
 * choice preselected and applying again overwrites it. Nothing here touches storage; the screen does that.
 */
internal data class ReviewSession(
    val keys: List<String>,
    val cursor: Int = 0,
    val decisions: Map<String, String> = emptyMap(),
    val history: List<ReviewStep> = emptyList(),
) {
    val total: Int get() = keys.size
    val current: String? get() = keys.getOrNull(cursor)
    val finished: Boolean get() = cursor >= keys.size
    val position: Int get() = (cursor + 1).coerceAtMost(total)
    val canGoBack: Boolean get() = history.isNotEmpty()

    /** The category to show selected for the current item: an earlier decision, else [fallback]. */
    fun selection(fallback: String): String = current?.let(decisions::get) ?: fallback

    fun apply(category: String, similar: Collection<String> = emptyList()): ReviewSession {
        val key = current ?: return this
        val extra = similar.filter { it != key && it in keys }.distinct()
        val next = decisions + (key to category) + extra.associateWith { category }
        return copy(
            cursor = firstOpenAfter(cursor, next),
            decisions = next,
            history = history + ReviewStep(key, extra, category),
        )
    }

    fun skip(): ReviewSession {
        val key = current ?: return this
        return copy(cursor = firstOpenAfter(cursor, decisions), history = history + ReviewStep(key, emptyList(), null))
    }

    fun back(): ReviewSession {
        val last = history.lastOrNull() ?: return this
        return copy(cursor = keys.indexOf(last.key), history = history.dropLast(1))
    }

    /** Reverts the last applied step entirely and returns the keys whose stored category should be cleared. */
    fun undo(): Pair<ReviewSession, List<String>> {
        val last = history.lastOrNull() ?: return this to emptyList()
        if (last.category == null) return back() to emptyList()
        val cleared = listOf(last.key) + last.similar
        return copy(
            cursor = keys.indexOf(last.key),
            decisions = decisions - cleared.toSet(),
            history = history.dropLast(1),
        ) to cleared
    }

    private fun firstOpenAfter(from: Int, decided: Map<String, String>): Int {
        var index = from + 1
        while (index < keys.size && keys[index] in decided) index++
        return index
    }

    fun toJson(): String = JSONObject()
        .put("keys", JSONArray(keys))
        .put("cursor", cursor)
        .put("decisions", JSONObject(decisions as Map<*, *>))
        .put("history", JSONArray(history.map {
            JSONObject().put("key", it.key).put("similar", JSONArray(it.similar)).put("category", it.category ?: JSONObject.NULL)
        }))
        .toString()

    companion object {
        fun fromJson(text: String): ReviewSession? = runCatching {
            val json = JSONObject(text)
            fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }
            val decisions = json.getJSONObject("decisions")
            ReviewSession(
                keys = strings(json.getJSONArray("keys")),
                cursor = json.getInt("cursor"),
                decisions = decisions.keys().asSequence().associateWith { decisions.getString(it) },
                history = json.getJSONArray("history").let { array ->
                    List(array.length()) {
                        val step = array.getJSONObject(it)
                        ReviewStep(step.getString("key"), strings(step.getJSONArray("similar")), if (step.isNull("category")) null else step.getString("category"))
                    }
                },
            )
        }.getOrNull()

        val Saver = androidx.compose.runtime.saveable.Saver<ReviewSession?, String>(
            save = { it?.toJson() },
            restore = { fromJson(it) },
        )
    }
}
