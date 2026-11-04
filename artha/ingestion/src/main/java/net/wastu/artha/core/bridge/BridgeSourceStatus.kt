package net.wastu.artha.core.bridge

import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.ingestion.ProviderReadException
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * How one source's last import on the bridge went. Artha shows these on its Sources page
 * as they are, since the bridge is the phone that actually reads the provider apps.
 */
data class BridgeSourceStatus(
    val provider: Provider,
    /** Last import that read the source, kept across later failures; null if none has. */
    val syncedAt: Long?,
    val attemptedAt: Long,
    val status: AccountStatus,
    val summary: String?,
    val error: String?,
    val accountIdentifier: String? = null,
    val coverageHint: String? = null,
    val paymentCount: Int? = null,
    val earliestMillis: Long? = null,
    val latestMillis: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("provider", provider.name)
        .put("syncedAt", syncedAt ?: JSONObject.NULL)
        .put("attemptedAt", attemptedAt)
        .put("status", status.name)
        .put("summary", summary ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
        .put("account", accountIdentifier ?: JSONObject.NULL)
        .put("coverage", coverageHint ?: JSONObject.NULL)
        .put("count", paymentCount ?: JSONObject.NULL)
        .put("earliest", earliestMillis ?: JSONObject.NULL)
        .put("latest", latestMillis ?: JSONObject.NULL)

    companion object {
        fun fromJson(obj: JSONObject) = BridgeSourceStatus(
            provider = Provider.valueOf(obj.getString("provider")),
            syncedAt = obj.longOrNull("syncedAt"),
            attemptedAt = obj.getLong("attemptedAt"),
            status = runCatching { AccountStatus.valueOf(obj.getString("status")) }.getOrDefault(AccountStatus.UNKNOWN),
            summary = obj.stringOrNull("summary"),
            error = obj.stringOrNull("error"),
            accountIdentifier = obj.stringOrNull("account"),
            coverageHint = obj.stringOrNull("coverage"),
            paymentCount = obj.longOrNull("count")?.toInt(),
            earliestMillis = obj.longOrNull("earliest"),
            latestMillis = obj.longOrNull("latest"),
        )

        fun listToJson(statuses: Collection<BridgeSourceStatus>) = JSONArray().apply { statuses.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray?): List<BridgeSourceStatus> = if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }

        /** A source that was read, possibly with a gap named by [partialReason]. */
        fun imported(
            provider: Provider, transactions: List<UnifiedTransaction>, now: Long,
            accountIdentifier: String?, coverageHint: String?, partialReason: String? = null,
        ): BridgeSourceStatus {
            val payments = transactions.filter { it.isConfirmed }.distinctBy { it.key }
            val times = payments.map { it.occurredAt.toEpochMilli() }
            return BridgeSourceStatus(
                provider, syncedAt = now, attemptedAt = now,
                status = if (partialReason == null) AccountStatus.CONNECTED else AccountStatus.PARTIAL,
                summary = listOfNotNull("${payments.size} payments", partialReason).joinToString(". "),
                error = partialReason, accountIdentifier = accountIdentifier, coverageHint = coverageHint,
                paymentCount = payments.size, earliestMillis = times.minOrNull(), latestMillis = times.maxOrNull(),
            )
        }

        /** A source that could not be read; what was last read stays as it was. */
        fun failed(previous: BridgeSourceStatus?, provider: Provider, error: Exception, now: Long, coverageHint: String?): BridgeSourceStatus {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Unable to read ${provider.displayName} data"
            return (previous ?: BridgeSourceStatus(provider, null, now, AccountStatus.UNKNOWN, null, null)).copy(
                attemptedAt = now, status = statusFor(error), error = message, coverageHint = coverageHint ?: previous?.coverageHint,
            )
        }

        /** The account state a read failure points to, as Artha's own import judges it. */
        fun statusFor(error: Exception): AccountStatus {
            (error as? ProviderReadException)?.let { return it.status }
            val lower = error.message.orEmpty().lowercase()
            return when {
                "login" in lower || "cookie" in lower || "session" in lower -> AccountStatus.NEEDS_LOGIN
                "not installed" in lower -> AccountStatus.APP_NOT_INSTALLED
                else -> AccountStatus.ERROR
            }
        }

        private fun JSONObject.longOrNull(key: String) = if (isNull(key) || !has(key)) null else optLong(key)
        private fun JSONObject.stringOrNull(key: String) = if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
    }
}
