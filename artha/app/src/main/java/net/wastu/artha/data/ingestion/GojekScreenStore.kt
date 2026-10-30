package net.wastu.artha.data.ingestion

import java.io.File
import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject

/**
 * Append-only JSONL in Artha's own files: the screen walk writes settled GoPay payments and
 * rows it could not read, and the Gojek import reads the payments back. Keeping the walk and
 * the import apart means a crash between them loses nothing.
 */
internal class GojekScreenStore(directory: File) {
    private val file = File(directory, "gojek_screen.jsonl")
    private val state = File(directory, "gojek_screen_state.json")

    fun append(transaction: UnifiedTransaction) = write(JSONObject()
        .put("id", transaction.providerTransactionId).put("direction", transaction.direction.name)
        .put("amountMinor", transaction.money.amountMinor).put("occurredAt", transaction.occurredAt.toString())
        .put("description", transaction.description).put("raw", transaction.rawPayload)
        .put("qris", transaction.isQris).put("reversal", transaction.isReversal))

    /** Remembers a row whose detail screen was unreadable so later walks do not reopen it. */
    fun skip(signature: String) = write(JSONObject().put("skip", signature))

    /** Payments in first-seen order, one per transaction ID. */
    fun transactions(): List<UnifiedTransaction> {
        val rows = LinkedHashMap<String, UnifiedTransaction>()
        for (line in lines()) {
            if (!line.has("id")) continue
            val row = runCatching {
                UnifiedTransaction(
                    Provider.GOJEK, line.getString("id").also { require(it.startsWith("gopay:")) },
                    Direction.valueOf(line.getString("direction")), Money(line.getLong("amountMinor")),
                    Instant.parse(line.getString("occurredAt")), line.getString("description"), line.getString("raw"),
                    isQris = line.optBoolean("qris"), isReversal = line.optBoolean("reversal"),
                )
            }.getOrNull() ?: continue
            rows.putIfAbsent(row.providerTransactionId, row)
        }
        return rows.values.toList()
    }

    fun skipped(): Set<String> = lines().mapNotNull { it.optString("skip").ifEmpty { null } }.toSet()

    /** True when the last walk stopped on its detail budget, so the next one keeps scrolling past known rows. */
    var backfillPending: Boolean
        get() = synchronized(LOCK) { runCatching { JSONObject(state.readText()).optBoolean("backfillPending") }.getOrDefault(false) }
        set(value) = synchronized(LOCK) {
            state.parentFile?.mkdirs()
            state.writeText(JSONObject().put("backfillPending", value).toString())
        }

    private fun write(line: JSONObject) = synchronized(LOCK) {
        file.parentFile?.mkdirs()
        file.appendText(line.toString() + "\n")
    }

    private fun lines(): List<JSONObject> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        // A torn final line from a crash is skipped rather than failing the import.
        file.useLines { lines -> lines.take(MAX_LINES).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList() }
    }

    private companion object {
        val LOCK = Any()
        const val MAX_LINES = 100_000
    }
}
