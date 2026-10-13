package net.wastu.artha.data.ledger

import java.security.MessageDigest
import java.time.Instant
import net.wastu.artha.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Immutable import revisions. Original ledger rows and their identities are never rewritten. */
object ProviderObservations {
    private const val PREFIX = "artha:observation:"
    private const val ORIGIN = "artha-provider-observation"
    private data class Revision(val transaction: UnifiedTransaction, val time: Long)

    fun isObservation(tx: UnifiedTransaction) = tx.providerTransactionId.startsWith(PREFIX)

    private fun encode(tx: UnifiedTransaction): JSONObject = JSONObject()
        .put("provider", tx.provider.name).put("providerTransactionId", tx.providerTransactionId)
        .put("direction", tx.direction.name).put("amountMinor", tx.money.amountMinor)
        .put("currencyCode", tx.money.currencyCode).put("occurredAtEpochMillis", tx.occurredAt.toEpochMilli())
        .put("description", tx.description).put("rawPayload", tx.rawPayload)
        .put("pocketId", tx.pocket?.providerPocketId ?: JSONObject.NULL).put("pocketName", tx.pocket?.name ?: JSONObject.NULL)
        .put("isQris", tx.isQris).put("cardLast4", tx.cardLast4 ?: JSONObject.NULL)

    private fun decode(obj: JSONObject): UnifiedTransaction {
        val payload = obj.getString("rawPayload").also { JSONObject(it) }
        return UnifiedTransaction(Provider.valueOf(obj.getString("provider")), obj.getString("providerTransactionId"),
            Direction.valueOf(obj.getString("direction")), Money(obj.getLong("amountMinor"), obj.getString("currencyCode")),
            Instant.ofEpochMilli(obj.getLong("occurredAtEpochMillis")), obj.getString("description"), payload,
            pocket = if (obj.isNull("pocketId")) null else Pocket(obj.getString("pocketId"), obj.getString("pocketName")),
            isQris = obj.getBoolean("isQris"), cardLast4 = if (obj.isNull("cardLast4")) null else obj.getString("cardLast4"))
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> value.toString().toBigDecimal().stripTrailingZeros().toPlainString()
        else -> value?.toString() ?: "null"
    }

    private fun fingerprint(tx: UnifiedTransaction): String {
        val obj = encode(tx).put("rawPayload", JSONObject(tx.rawPayload))
        return MessageDigest.getInstance("SHA-256").digest(canonical(obj).toByteArray(Charsets.UTF_8))
            .toLowerHex()
    }

    private fun eligible(tx: UnifiedTransaction): Boolean {
        if (tx.provider == Provider.JAGO || isObservation(tx)) return false
        val raw = JSONObject(tx.rawPayload)
        return when (tx.provider) {
            Provider.SHOPEE -> (raw.optString("origin") in setOf("shopee-order-api", "shopeefood-order-api") && raw.optBoolean("paymentDetailVerified")) ||
                raw.optString("origin") == "shopee-refund-api" && raw.optBoolean("verifiedRefund") && raw.optLong("sourceRefundStatus") == 5L
            Provider.GOJEK -> raw.optString("origin") == "gopay-order-history" && raw.optString("paymentEvidence") == "settled-wallet-history"
            Provider.GRAB -> raw.optString("origin") in setOf("grab-wallet-api", "grab-rides-api") &&
                (raw.optString("paymentEvidence") == "settled-charge-history" || raw.optBoolean("paymentDetailVerified"))
            else -> false
        }
    }

    private fun revision(record: UnifiedTransaction): Revision {
        require(record.provider != Provider.JAGO) { "Provider observations cannot replace bank facts" }
        val raw = JSONObject(record.rawPayload)
        require(raw.getString("origin") == ORIGIN)
        val time = raw.getLong("observedAtEpochMillis").also { require(it in 1..4102444800000L) }
        val tx = decode(raw.getJSONObject("transaction"))
        require(tx.provider == record.provider && eligible(tx)) { "Unsupported provider observation" }
        val hash = fingerprint(tx)
        require(record.providerTransactionId == "$PREFIX${tx.providerTransactionId}:$time:$hash") { "Invalid provider observation identity" }
        require(record.money == tx.money && record.direction == tx.direction && record.occurredAt == tx.occurredAt && record.description == tx.description)
        return Revision(tx, time)
    }

    fun validate(records: List<UnifiedTransaction>) {
        val originals = records.filterNot(::isObservation).mapTo(mutableSetOf()) { it.key }
        for (record in records.filter(::isObservation)) require(revision(record).transaction.key in originals) { "Provider observation is missing its original ledger row" }
    }

    fun resolve(records: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val revisions = records.filter(::isObservation).map { it to revision(it) }
            .groupBy { it.second.transaction.key }.mapValues { (_, versions) ->
                versions.maxWith(compareBy<Pair<UnifiedTransaction, Revision>> { it.second.time }.thenBy { it.first.key }).second.transaction
            }
        return records.filterNot(::isObservation).map { original -> revisions[original.key] ?: original }
    }

    /** Only fresh imports call this; backup restore must never manufacture a newer revision. */
    fun capture(existing: List<UnifiedTransaction>, incoming: List<UnifiedTransaction>, now: Long): List<UnifiedTransaction> {
        val current = resolve(existing).associateBy { it.key }.toMutableMap()
        val times = existing.filter(::isObservation).map(::revision).groupBy { it.transaction.key }
            .mapValues { (_, rows) -> rows.maxOf { it.time } }.toMutableMap()
        return buildList {
            for (tx in incoming.filter(::eligible)) {
                val previous = current[tx.key] ?: continue
                if (fingerprint(previous) == fingerprint(tx)) continue
                val time = maxOf(now, Math.addExact(times[tx.key] ?: 0L, 1L))
                val id = "$PREFIX${tx.providerTransactionId}:$time:${fingerprint(tx)}"
                val payload = JSONObject().put("origin", ORIGIN).put("observedAtEpochMillis", time).put("transaction", encode(tx))
                add(tx.copy(providerTransactionId = id, rawPayload = payload.toString(), canonicalKey = null))
                current[tx.key] = tx; times[tx.key] = time
            }
        }
    }
}
