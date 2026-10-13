@file:android.annotation.SuppressLint("SdCardPath")

package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class GojekOrderReader(private val files: RootFileReader) {
    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        try {
            val bytes = files.read("/data/data/com.gojek.app/files/gojek_orders_2026.json")
            val array = JSONArray(String(bytes, Charsets.UTF_8))
            val formatter = SimpleDateFormat("dd/MM/yyyy hh:mm:ss a", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Jakarta")
            }
            buildList {
                for (i in 0 until array.length()) {
                    val order = array.optJSONObject(i) ?: continue
                    val orderNumber = order.optString("order_number").ifBlank { continue }
                    val service = order.optString("service", "GoRide")
                    val origin = order.optString("origin").trim().trimEnd(',')
                    val destination = order.optString("destination").trim().trimEnd(',')
                    val amountRupiah = order.optDouble("amount", 0.0).toLong()
                    val datetimeStr = order.optString("datetime")
                    val occurredAt = try {
                        val parsed = formatter.parse(datetimeStr)
                        parsed?.toInstant() ?: Instant.now()
                    } catch (_: Exception) {
                        Instant.now()
                    }
                    val shortService = when {
                        service.contains("GoCar", ignoreCase = true) -> "GoCar"
                        service.contains("GoFood", ignoreCase = true) -> "GoFood"
                        service.contains("GoSend", ignoreCase = true) -> "GoSend"
                        else -> "GoRide"
                    }
                    val description = if (origin.isNotBlank() && destination.isNotBlank()) {
                        "$shortService: $origin to $destination"
                    } else if (origin.isNotBlank()) {
                        "$shortService: $origin"
                    } else {
                        service
                    }
                    val rawPayload = JSONObject()
                        .put("origin", "gojek-orders-local")
                        .put("provider", Provider.GOJEK.name)
                        .put("orderNumber", orderNumber)
                        .put("serviceTier", service)
                        .put("pickupLocation", origin)
                        .put("dropoffLocation", destination)
                        .put("paymentMethod", order.optString("payment_method", "GoPay"))
                        .toString()
                    add(
                        UnifiedTransaction(
                            provider = Provider.GOJEK,
                            providerTransactionId = "gojek:$orderNumber",
                            direction = Direction.DEBIT,
                            money = Money(amountRupiah * 100L, "IDR"),
                            occurredAt = occurredAt,
                            description = description,
                            rawPayload = rawPayload,
                        )
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
}
