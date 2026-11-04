package net.wastu.artha.bridge.importing

import net.wastu.artha.core.bridge.BridgeSourceStatus
import net.wastu.artha.core.bridge.BridgeTransactionSerializer

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.ingestion.GojekProviderAdapter
import net.wastu.artha.data.ingestion.GrabProviderAdapter
import net.wastu.artha.data.ingestion.JagoProviderAdapter
import net.wastu.artha.data.ingestion.JagoTransactionReader
import net.wastu.artha.data.ingestion.PartialImportException
import net.wastu.artha.data.ingestion.RootFileReader
import net.wastu.artha.data.ingestion.ShopeeProviderAdapter
import net.wastu.artha.data.sync.AppSessionWakeSync

/**
 * Reads Jago, GoPay, Shopee and Grab on this phone with the same readers Artha uses, and keeps
 * the result as the snapshot the bridge sends. Artha on the main phone merges and de-duplicates
 * what it receives, so every import sends the whole history the provider apps still hold.
 */
class BridgeImporter(private val context: Context) {
    private val mutex = Mutex()
    // v2 keeps raw payloads; the first snapshot dropped them, so it is left unread.
    private val snapshot = File(context.filesDir, "transactions-v2.json").also { File(context.filesDir, "transactions.json").delete() }

    private val statusFile = File(context.filesDir, "sources.json")

    val hasSnapshot: Boolean get() = snapshot.exists()

    /** Imports every source; one that fails keeps its rows from the last good import. */
    suspend fun importAll(): Map<Provider, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val files = RootFileReader(context)
            val isInteractive = { context.getSystemService(PowerManager::class.java)?.isInteractive == true }
            val sessionWake = AppSessionWakeSync(files, isInteractive, { false })
            val adapters = listOf(
                // The bridge unlocks Jago itself beforehand, so it can record whether the PIN worked.
                JagoProviderAdapter(JagoTransactionReader(files)),
                ShopeeProviderAdapter(files, sessionWake),
                GojekProviderAdapter(files),
                GrabProviderAdapter(files, sessionWake),
            )
            val previous = load().groupBy { it.provider }
            val previousStatus = sourceStatuses().associateBy { it.provider }
            val statuses = linkedMapOf<Provider, BridgeSourceStatus>()
            val results = linkedMapOf<Provider, String>()
            val transactions = adapters.flatMap { adapter ->
                val provider = adapter.provider
                val now = System.currentTimeMillis()
                try {
                    adapter.ingest().also {
                        statuses[provider] = BridgeSourceStatus.imported(provider, it, now, adapter.accountIdentifier, adapter.coverageHint)
                        results[provider] = "${it.size} payments"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (partial: PartialImportException) {
                    // As in Artha: what was read is kept, and the gap is reported.
                    val reason = partial.message?.takeIf { it.isNotBlank() } ?: "Some history could not be imported"
                    results[provider] = "${partial.transactions.size} payments; $reason"
                    if (partial.transactions.isEmpty()) {
                        statuses[provider] = BridgeSourceStatus.failed(previousStatus[provider], provider, partial, now, adapter.coverageHint)
                        previous[provider].orEmpty()
                    } else {
                        statuses[provider] = BridgeSourceStatus.imported(provider, partial.transactions, now, adapter.accountIdentifier, adapter.coverageHint, reason)
                        partial.transactions
                    }
                } catch (e: Exception) {
                    val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    results[provider] = message
                    statuses[provider] = BridgeSourceStatus.failed(previousStatus[provider], provider, e, now, adapter.coverageHint)
                    previous[provider].orEmpty()
                }
            }
            save(transactions)
            writeAtomically(statusFile, BridgeSourceStatus.listToJson(statuses.values).toString())
            net.wastu.artha.bridge.daemon.BridgeLog.i(TAG, "Imported ${results.entries.joinToString { "${it.key}: ${it.value}" }}")
            results
        }
    }

    fun load(): List<UnifiedTransaction> =
        runCatching { BridgeTransactionSerializer.deserializeList(snapshot.readText()) }.getOrDefault(emptyList())

    /** How each source's last import went, for Artha's Sources page. */
    fun sourceStatuses(): List<BridgeSourceStatus> =
        runCatching { BridgeSourceStatus.listFromJson(org.json.JSONArray(statusFile.readText())) }.getOrDefault(emptyList())

    fun isPackageInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    fun isProviderInstalled(provider: Provider): Boolean {
        val pkg = when (provider) {
            Provider.JAGO -> "com.jago.digitalBanking"
            Provider.GOJEK -> "com.gojek.app"
            Provider.SHOPEE -> "com.shopee.id"
            Provider.GRAB -> "com.grabtaxi.passenger"
        }
        return isPackageInstalled(pkg)
    }

    private fun save(transactions: List<UnifiedTransaction>) =
        writeAtomically(snapshot, BridgeTransactionSerializer.serializeList(transactions))

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "ArthaBridgeImport"
    }
}
