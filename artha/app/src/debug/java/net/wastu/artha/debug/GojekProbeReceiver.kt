package net.wastu.artha.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import net.wastu.artha.ArthaApplication
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.ingestion.GojekOrderReader
import net.wastu.artha.data.ingestion.JagoTransactionReader
import net.wastu.artha.data.ingestion.PartialImportException
import net.wastu.artha.data.ingestion.RootFileReader
import org.json.JSONObject

/** Debug-only probes via adb broadcast. */
class GojekProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "probe started action=$action")
        val pending = goAsync()
        val app = context.applicationContext as ArthaApplication
        Thread {
            try {
                when (action) {
                    ACTION_PROBE -> {
                        val report = runBlocking {
                            GojekOrderReader(RootFileReader(app)).diagnoseHistoryAccess()
                        }
                        Log.i(TAG, report)
                    }
                    ACTION_INGEST_AUDIT -> runBlocking {
                        val root = app.rootAccess.verify()
                        Log.i(TAG, "root=$root")
                        val jagoGopay = JagoTransactionReader(RootFileReader(app)).transactions()
                            .filter {
                                val code = runCatching { JSONObject(it.rawPayload).optString("code") }.getOrNull()
                                code in setOf("GPD01", "GPD05", "GPC02")
                            }
                        Log.i(TAG, "jagoGopayRows=${jagoGopay.size}")
                        jagoGopay.take(5).forEach { row ->
                            val raw = JSONObject(row.rawPayload)
                            Log.i(
                                TAG,
                                "jago id=${row.providerTransactionId} desc=${row.description} qris=${row.isQris} " +
                                    "code=${raw.optString("code")} counterpart=${raw.optString("counterpart")} " +
                                    "notes=${raw.optString("notes").take(80)} localized=${raw.optString("localized").take(80)} " +
                                    "f6=${raw.optJSONObject("fields")?.optString("f6")} f17=${raw.optJSONObject("fields")?.optString("f17")}",
                            )
                        }
                        val transactions = try {
                            GojekOrderReader(RootFileReader(app)).transactions(interactive = true)
                        } catch (partial: PartialImportException) {
                            Log.i(TAG, "partial=${partial.message}")
                            partial.transactions
                        }
                        Log.i(TAG, "gojekIngestCount=${transactions.size} qris=${transactions.count { it.isQris }}")
                        transactions.take(10).forEach { tx ->
                            val payload = runCatching { JSONObject(tx.rawPayload) }.getOrNull()
                            Log.i(
                                TAG,
                                "gojek id=${tx.providerTransactionId} desc=${tx.description} qris=${tx.isQris} " +
                                    "evidence=${payload?.optString("paymentEvidence")} merchant=${payload?.optString("merchantName")} " +
                                    "detailItems=${payload?.optJSONArray("detailViewItems")?.length() ?: 0}",
                            )
                        }
                        val sync = app.ingestion.syncProvider(Provider.GOJEK)
                        Log.i(TAG, "sync=$sync")
                    }
                }
            } catch (error: Exception) {
                Log.i(TAG, "failure=${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "GojekProbe"
        const val ACTION_PROBE = "net.wastu.artha.DEBUG_GOJEK_PROBE"
        const val ACTION_INGEST_AUDIT = "net.wastu.artha.DEBUG_GOJEK_INGEST"
    }
}
