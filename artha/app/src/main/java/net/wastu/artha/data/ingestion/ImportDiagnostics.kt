package net.wastu.artha.data.ingestion

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
        if (error is ProviderReadException && (providerFailure == null || error.status == AccountStatus.NEEDS_LOGIN)) providerFailure = error
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
            val summary = (listOfNotNull(providerFailure?.message) + issues).distinct().take(3).joinToString(" · ")
            throw PartialImportException(transactions.distinctBy { it.key }, "Incomplete import: ${issues.size} issues. $summary")
        }
        return transactions.distinctBy { it.key }
    }
}

internal fun requireHttpSuccess(code: Int, provider: String) {
    if (code == 401) throw ProviderReadException(AccountStatus.NEEDS_LOGIN, "$provider login expired (HTTP $code). Open the provider app and log in again.")
    if (code == 403) throw ProviderReadException(AccountStatus.ERROR, "$provider blocked this history request (HTTP 403). Open its app to check for verification or access restrictions.")
    check(code in 200..299) { "$provider request failed (HTTP $code)" }
}
