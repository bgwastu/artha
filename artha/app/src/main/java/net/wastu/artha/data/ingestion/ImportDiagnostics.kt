package net.wastu.artha.data.ingestion

import java.net.HttpURLConnection
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.providers.AccountStatus

class ProviderReadException(val status: AccountStatus, message: String) : Exception(message)
class PartialImportException(val transactions: List<UnifiedTransaction>, message: String) : Exception(message)

/** Valid rows survive a malformed row; failures remain visible and never invent financial facts. */
internal class ImportRows {
    val transactions = mutableListOf<UnifiedTransaction>()
    private val issues = mutableListOf<String>()
    private var providerFailure: ProviderReadException? = null
    fun issue(message: String) { issues += message }
    fun issue(error: Exception) {
        if (error is ProviderReadException && (providerFailure == null ||
                error.status == AccountStatus.NEEDS_LOGIN || error.status == AccountStatus.RATE_LIMITED)) {
            providerFailure = error
        }
        issue(error.message ?: "Provider request unavailable")
    }
    inline fun readRow(block: () -> UnifiedTransaction?) {
        try {
            val row = block()
            if (row == null) issue("Row missing required payment fields") else transactions += row
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issue("Unrecognized transaction row") }
    }
    fun finish(): List<UnifiedTransaction> {
        if (transactions.isEmpty()) providerFailure?.let { throw it }
        if (issues.isNotEmpty()) {
            val summary = (listOfNotNull(providerFailure?.message) + issues).distinct().take(3).joinToString(". ")
            throw PartialImportException(transactions.distinctBy { it.key }, "Incomplete import: ${issues.size} issues. $summary")
        }
        return transactions.distinctBy { it.key }
    }
}

internal fun requireHttpSuccess(code: Int, provider: String, retryAfterHeader: String? = null) {
    if (code == 401) throw ProviderReadException(AccountStatus.NEEDS_LOGIN, "$provider login expired (HTTP $code). Open the provider app and log in again.")
    if (code == 403) throw ProviderReadException(AccountStatus.ERROR, "$provider blocked this history request (HTTP 403). Open its app to check for verification or access restrictions.")
    if (code == 429) {
        val retry = parseRetryAfterSeconds(retryAfterHeader)?.let { " Retry after ${it}s." } ?: ""
        throw ProviderReadException(AccountStatus.RATE_LIMITED, "$provider rate-limited (HTTP 429).$retry Wait and sync again.")
    }
    check(code in 200..299) { "$provider request failed (HTTP $code)" }
}

private fun parseRetryAfterSeconds(header: String?): Int? =
    header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

internal const val GOJEK_NEEDS_LOGIN_MESSAGE = "Open Gojek and leave it signed in, then sync again."
internal const val GOJEK_HISTORY_LIST_UNAVAILABLE_MESSAGE =
    "GoPay history list is unavailable from Gojek (GoPay-1000). Artha will try Jago-linked order references instead."

internal fun requireGojekSuccess(code: Int, connection: HttpURLConnection? = null) {
    if (code in 200..299) return
    val auth = gojekWwwAuthenticate(connection)
    val errorBody = connection?.errorStream?.let { stream ->
        runCatching { GojekProviderParser.readBounded(stream).toString(Charset.forName("UTF-8")) }.getOrDefault("")
    }.orEmpty()
    requireGojekHttpFailure(code, auth, errorBody)?.let { throw it }
}

internal fun gojekWwwAuthenticate(connection: HttpURLConnection?): String {
    if (connection == null) return ""
    val fields = connection.headerFields
    if (fields != null) {
        for ((name, values) in fields) {
            if (name != null && name.equals("WWW-Authenticate", ignoreCase = true)) {
                return values.joinToString(", ")
            }
        }
    }
    return connection.getHeaderField("WWW-Authenticate").orEmpty()
}

internal fun requireGojekHttpFailure(code: Int, wwwAuthenticate: String, errorBody: String): ProviderReadException? {
    if (code in 200..299) return null
    val auth = wwwAuthenticate.lowercase()
    val body = errorBody.lowercase()
    val invalidToken = body.contains("invalid-token") || body.contains("invalid_token") ||
        body.contains("invalid token") ||
        auth.contains("invalid-token") ||
        body.contains("token_expired") || body.contains("token expired") ||
        body.contains("session expired") || body.contains("session has expired") ||
        (code == 400 && auth.contains("realm=gojek"))
    val goPayListUnavailable = code == 400 && body.contains("gopay-1000") &&
        !auth.contains("invalid-token") && !body.contains("invalid-token") && !body.contains("invalid_token")
    if (goPayListUnavailable) {
        return ProviderReadException(AccountStatus.ERROR, GOJEK_HISTORY_LIST_UNAVAILABLE_MESSAGE)
    }
    if (code == 401 || invalidToken) {
        return ProviderReadException(AccountStatus.NEEDS_LOGIN, GOJEK_NEEDS_LOGIN_MESSAGE)
    }
    if (code == 403 && (invalidToken || body.contains("invalid-token") || body.contains("invalid_token"))) {
        return ProviderReadException(AccountStatus.NEEDS_LOGIN, GOJEK_NEEDS_LOGIN_MESSAGE)
    }
    if (code == 403) requireHttpSuccess(code, "Gojek")
    return ProviderReadException(AccountStatus.ERROR, "Gojek history request failed (HTTP $code); valid cached payments are retained")
}

internal fun isTransientNetworkError(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is java.net.UnknownHostException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            -> return true
        }
        val message = current.message.orEmpty()
        if (message.contains("No address associated with hostname", ignoreCase = true) ||
            message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("ETIMEDOUT", ignoreCase = true) ||
            message.contains("ECONNRESET", ignoreCase = true)) {
            return true
        }
        current = current.cause
    }
    return false
}

internal fun transientNetworkSummary(error: Throwable): String =
    error.message?.lineSequence()?.first()?.trim().orEmpty().ifBlank { "network unavailable" }

/** Foody often returns HTTP 403 on the paginated /orders list while search-history still works. */
internal fun isShopeeFoodAccessChallenge(error: Throwable): Boolean = isShopeeAccessChallenge(error)

internal fun isShopeeAccessChallenge(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return error is ProviderReadException &&
        message.contains("HTTP 403", ignoreCase = true) &&
        message.contains("Shopee access", ignoreCase = true)
}

internal fun isRecoverableShopeeAccessChallengePartial(message: String): Boolean =
    message.contains("1 issues") && message.contains("Shopee access challenge (HTTP 403)")
