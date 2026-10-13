@file:android.annotation.SuppressLint("SdCardPath")

package net.wastu.artha.data.ingestion

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class GojekOrderReader(private val files: RootFileReader) {
    suspend fun transactions(): List<UnifiedTransaction> = withContext(Dispatchers.IO) {
        val rideOrders = runCatching { readRideOrders() }.getOrDefault(emptyList())
        val gopayTxs = runCatching { readGoPayTransactions() }.getOrDefault(emptyList())

        val map = mutableMapOf<String, UnifiedTransaction>()
        for (tx in rideOrders) {
            map[tx.providerTransactionId] = tx
        }
        for (tx in gopayTxs) {
            map.putIfAbsent(tx.providerTransactionId, tx)
        }
        map.values.sortedByDescending { it.occurredAt }
    }

    private suspend fun readRideOrders(): List<UnifiedTransaction> {
        return try {
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

    private suspend fun readGoPayTransactions(): List<UnifiedTransaction> {
        val cacheFiles = runCatching {
            files.list("/data/data/com.gojek.app/cache/okhttp_cache")
        }.getOrNull() ?: return emptyList()

        val results = mutableListOf<UnifiedTransaction>()
        for (fName in cacheFiles) {
            if (!fName.endsWith(".0")) continue
            val baseName = fName.removeSuffix(".0")
            val f0Path = "/data/data/com.gojek.app/cache/okhttp_cache/$fName"
            val f1Path = "/data/data/com.gojek.app/cache/okhttp_cache/$baseName.1"

            val isOrderHistory = runCatching {
                val headerBytes = files.read(f0Path)
                val firstLine = String(headerBytes, Charsets.UTF_8).lines().firstOrNull().orEmpty()
                firstLine.contains("customer.gopayapi.com/v2/users/order-history") && !firstLine.contains("/details")
            }.getOrDefault(false)

            if (!isOrderHistory) continue

            val bodyBytes = runCatching { files.read(f1Path) }.getOrNull() ?: continue
            val bodyText = String(bodyBytes, Charsets.UTF_8)
            val json = runCatching { JSONObject(bodyText) }.getOrNull() ?: continue
            val completed = json.optJSONObject("data")?.optJSONArray("completed") ?: continue

            for (i in 0 until completed.length()) {
                val group = completed.optJSONObject(i) ?: continue
                val items = group.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val orderId = item.optString("order_id").ifBlank { continue }
                    val desc = item.optString("description").ifBlank { "GoPay Payment" }
                    val amountObj = item.optJSONObject("amount")
                    val amountRupiah = amountObj?.optLong("value", 0L) ?: 0L
                    val amountMinor = amountRupiah * 100L
                    val paymentType = item.optString("payment_type", "DEBIT")
                    val direction = if (paymentType.equals("CREDIT", ignoreCase = true)) Direction.CREDIT else Direction.DEBIT
                    val timestampStr = item.optString("order_timestamp")
                    val occurredAt = runCatching {
                        OffsetDateTime.parse(timestampStr).toInstant()
                    }.getOrElse {
                        runCatching {
                            java.time.LocalDateTime.parse(timestampStr)
                                .atZone(java.time.ZoneId.of("Asia/Jakarta"))
                                .toInstant()
                        }.getOrElse {
                            runCatching { Instant.parse(timestampStr) }.getOrDefault(Instant.now())
                        }
                    }

                    val serviceType = item.optString("service_type")
                    val isQris = serviceType.equals("COLLECT", ignoreCase = true) ||
                        desc.contains("QRIS", ignoreCase = true) ||
                        paymentType.contains("QRIS", ignoreCase = true) ||
                        item.optString("payment_transfer_type").contains("QRIS", ignoreCase = true)
                    val methods = item.optJSONArray("payment_method_breakup")
                    val methodName = methods?.optJSONObject(0)?.optString("payment_method_name") ?: "GoPay"

                    val rawPayload = JSONObject()
                        .put("origin", "gopay-order-history")
                        .put("provider", Provider.GOJEK.name)
                        .put("orderNumber", orderId)
                        .put("serviceTier", serviceType)
                        .put("paymentMethod", methodName)
                        .put("item", item)
                        .toString()

                    results.add(
                        UnifiedTransaction(
                            provider = Provider.GOJEK,
                            providerTransactionId = "gopay:$orderId",
                            direction = direction,
                            money = Money(amountMinor, "IDR"),
                            occurredAt = occurredAt,
                            description = desc,
                            isQris = isQris,
                            rawPayload = rawPayload,
                        )
                    )
                }
            }
        }
        return results
    }
}
