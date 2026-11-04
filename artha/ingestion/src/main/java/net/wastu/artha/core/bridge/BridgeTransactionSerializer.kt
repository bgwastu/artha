package net.wastu.artha.core.bridge

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * The transaction format shared by Artha and Artha Bridge. Rows keep their provider
 * raw payload, which the ledger needs for pockets, merchants and reconciliation.
 */
object BridgeTransactionSerializer {
    /** Marks a gzip-packed chunk; older bridges sent plain JSON without raw payloads. */
    const val PACKED_PREFIX = "gz1:"

    /**
     * Packed chunk size cap. A chunk is base64 encoded twice more on its way into a
     * relay event, so this keeps events near 50 KB, well under common relay limits.
     */
    const val MAX_PACKED_CHARS = 28_000

    fun serializeList(transactions: List<UnifiedTransaction>): String =
        JSONArray().apply { transactions.forEach { put(toJson(it)) } }.toString()

    fun deserializeList(jsonStr: String): List<UnifiedTransaction> {
        val arr = JSONArray(jsonStr)
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    /** Splits [transactions] into packed chunks no longer than [maxChars] where possible. */
    fun pack(transactions: List<UnifiedTransaction>, maxChars: Int = MAX_PACKED_CHARS): List<String> {
        if (transactions.isEmpty()) return emptyList()
        val packed = PACKED_PREFIX + Base64.getEncoder().encodeToString(gzip(serializeList(transactions)))
        if (packed.length <= maxChars || transactions.size == 1) return listOf(packed)
        val half = transactions.size / 2
        return pack(transactions.subList(0, half), maxChars) + pack(transactions.subList(half, transactions.size), maxChars)
    }

    /** The rows in a packed chunk, or null for a legacy chunk that lacks raw payloads. */
    fun unpack(payload: String): List<UnifiedTransaction>? {
        if (!payload.startsWith(PACKED_PREFIX)) return null
        val bytes = Base64.getDecoder().decode(payload.substring(PACKED_PREFIX.length))
        return deserializeList(GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8))
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun toJson(tx: UnifiedTransaction) = JSONObject().apply {
        put("provider", tx.provider.name)
        put("providerTxId", tx.providerTransactionId)
        put("occurredAtEpochMs", tx.occurredAt.toEpochMilli())
        put("amountMinor", tx.money.amountMinor)
        put("currency", tx.money.currencyCode)
        put("direction", tx.direction.name)
        put("description", tx.description)
        put("userNote", tx.userNote ?: JSONObject.NULL)
        put("category", tx.category ?: JSONObject.NULL)
        put("rawPayload", tx.rawPayload)
        put("isQris", tx.isQris)
        put("isReversal", tx.isReversal)
    }

    private fun fromJson(obj: JSONObject) = UnifiedTransaction(
        provider = Provider.valueOf(obj.getString("provider")),
        providerTransactionId = obj.getString("providerTxId"),
        occurredAt = Instant.ofEpochMilli(obj.getLong("occurredAtEpochMs")),
        money = Money(obj.getLong("amountMinor"), obj.getString("currency")),
        direction = Direction.valueOf(obj.getString("direction")),
        description = obj.getString("description"),
        userNote = if (obj.isNull("userNote")) null else obj.getString("userNote"),
        category = if (obj.isNull("category")) null else obj.getString("category"),
        rawPayload = obj.optString("rawPayload").ifBlank { "{}" },
        isQris = obj.optBoolean("isQris", false),
        isReversal = obj.optBoolean("isReversal", false),
    )
}
