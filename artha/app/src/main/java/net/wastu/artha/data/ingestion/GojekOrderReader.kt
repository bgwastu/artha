@file:android.annotation.SuppressLint("SdCardPath")
package net.wastu.artha.data.ingestion

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.*
import org.json.JSONArray
import org.json.JSONObject

internal class GojekOrderReader(private val files: RootFileReader) {
    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        val rows = ImportRows()
        try {
            val bytes = files.read("/data/data/com.gojek.app/files/gojek_orders_2026.json")
            val orders = JSONArray(String(bytes, Charsets.UTF_8))
            val formatter = SimpleDateFormat("dd/MM/yyyy hh:mm:ss a", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Jakarta"); isLenient = false
            }
            for (i in 0 until orders.length()) rows.readRow {
                val order = orders.getJSONObject(i)
                val id = order.getString("order_number")
                val service = order.optString("service").ifBlank { "GoRide" }
                val pickup = order.optString("origin").trim().trimEnd(',')
                val dropoff = order.optString("destination").trim().trimEnd(',')
                val amount = BigDecimal(order.get("amount").toString()).movePointRight(2).longValueExact()
                val date = requireNotNull(formatter.parse(order.getString("datetime"))).toInstant()
                val title = if (pickup.isNotBlank() && dropoff.isNotBlank()) "$service: $pickup to $dropoff" else service
                val payload = JSONObject().put("origin", "gojek-orders-local").put("orderNumber", id)
                    .put("pickupLocation", pickup).put("dropoffLocation", dropoff).put("serviceTier", service)
                    .put("paymentMethod", order.optString("payment_method"))
                UnifiedTransaction(Provider.GOJEK, "gojek:$id", Direction.DEBIT, Money(amount), date, title, payload.toString())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { rows.issue("Optional 2026 ride file unavailable; only cached GoPay payments can be imported") }

        try {
            val base = "/data/data/com.gojek.app/cache/okhttp_cache"
            val names = files.list(base).filter { it.endsWith(".0") }.take(1000)
            val headers = files.readBatch(names.map { "$base/$it" })
            val bodyPaths = headers.filterValues { bytes ->
                val url = bytes?.toString(Charsets.UTF_8)?.lineSequence()?.firstOrNull().orEmpty()
                "customer.gopayapi.com/v2/users/order-history" in url && "/details" !in url
            }.keys.map { it.removeSuffix(".0") + ".1" }
            if (bodyPaths.isEmpty()) rows.issue("No GoPay history cached. Open GoPay history in Gojek first.")
            else {
                val bodies = files.stableBatch(bodyPaths)
                for (path in bodyPaths) {
                    coroutineContext.ensureActive()
                    try {
                        val bytes = requireNotNull(bodies[path])
                        val completed = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("data").getJSONArray("completed")
                        for (i in 0 until completed.length()) {
                            val items = completed.getJSONObject(i).getJSONArray("items")
                            for (j in 0 until items.length()) rows.readRow { ProviderRowParser.goPay(items.getJSONObject(j)) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { rows.issue("One cached GoPay page is missing or unrecognized") }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { rows.issue(e.message ?: "GoPay cache unavailable") }
        rows.finish().sortedByDescending { it.occurredAt }
    }
}
