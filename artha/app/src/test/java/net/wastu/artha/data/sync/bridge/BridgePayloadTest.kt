package net.wastu.artha.data.sync.bridge

import net.wastu.artha.core.bridge.BridgeTransactionSerializer

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePayloadTest {

    @Test
    fun roundtripBridgeMessage() {
        val original = BridgeMessage(
            type = BridgeMessageType.PING,
            timestamp = 1793690000000L,
            payload = "ping-payload",
        )
        val json = original.toJson()
        val restored = BridgeMessage.fromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun roundtripBridgeSettings() {
        val original = BridgeSettings(
            jagoPin = "147258",
            enabledProviders = setOf(Provider.JAGO, Provider.GOJEK),
            syncIntervalMinutes = 30,
        )
        val json = original.toJson()
        val restored = BridgeSettings.fromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun roundtripBridgeTransactionSerializer() {
        val tx1 = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago_123",
            occurredAt = Instant.ofEpochMilli(1793691000000L),
            money = Money(5000000L, "IDR"),
            direction = Direction.DEBIT,
            description = "Coffee payment",
            userNote = "Pocket Main",
            category = "Food & Beverage",
            rawPayload = "{}",
            isQris = true,
        )
        val tx2 = UnifiedTransaction(
            provider = Provider.GOJEK,
            providerTransactionId = "gopay_456",
            occurredAt = Instant.ofEpochMilli(1793692000000L),
            money = Money(2400000L, "IDR"),
            direction = Direction.DEBIT,
            description = "Ride payment",
            userNote = null,
            category = "Transport",
            rawPayload = "raw-payload",
        )

        val json = BridgeTransactionSerializer.serializeList(listOf(tx1, tx2))
        val restored = BridgeTransactionSerializer.deserializeList(json)

        assertEquals(2, restored.size)
        assertEquals(tx1.providerTransactionId, restored[0].providerTransactionId)
        assertEquals(tx1.provider, restored[0].provider)
        assertEquals(tx1.description, restored[0].description)
        assertEquals(tx1.money.amountMinor, restored[0].money.amountMinor)
        assertEquals(true, restored[0].isQris)
        assertEquals(tx2.providerTransactionId, restored[1].providerTransactionId)
        assertEquals(tx2.userNote, restored[1].userNote)
        assertEquals(tx2.rawPayload, restored[1].rawPayload)
    }

    @Test
    fun packKeepsRawPayloadsAndSplitsLargeLists() {
        val rows = (0 until 400).map { index ->
            UnifiedTransaction(
                provider = Provider.JAGO,
                providerTransactionId = "jago_$index",
                occurredAt = Instant.ofEpochMilli(1793691000000L + index),
                money = Money(1000L * (index + 1), "IDR"),
                direction = Direction.DEBIT,
                description = "Payment $index",
                rawPayload = """{"id":"$index","pocket":{"id":"p${index % 7}"},"note":"${java.util.UUID.randomUUID()}"}""",
            )
        }
        val chunks = BridgeTransactionSerializer.pack(rows, maxChars = 4_000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 4_000 })
        val restored = chunks.flatMap { BridgeTransactionSerializer.unpack(it)!! }
        assertEquals(rows.map { it.providerTransactionId }, restored.map { it.providerTransactionId })
        assertEquals(rows.map { it.rawPayload }, restored.map { it.rawPayload })
    }

    @Test
    fun unpackIgnoresLegacyChunks() {
        assertNull(BridgeTransactionSerializer.unpack("[]"))
        assertTrue(BridgeTransactionSerializer.pack(emptyList()).isEmpty())
    }

    @Test
    fun sourceStatusRoundTripsAndKeepsLastSyncOnFailure() {
        val ok = net.wastu.artha.core.bridge.BridgeSourceStatus.imported(Provider.SHOPEE, listOf(
            UnifiedTransaction(provider = Provider.SHOPEE, providerTransactionId = "s1", occurredAt = Instant.ofEpochMilli(1_000L), money = Money(500L, "IDR"), direction = Direction.DEBIT, description = "Order", rawPayload = "{}"),
        ), now = 5_000L, accountIdentifier = "buyer", coverageHint = null, partialReason = "One refund unresolved")
        val restored = net.wastu.artha.core.bridge.BridgeSourceStatus.listFromJson(
            net.wastu.artha.core.bridge.BridgeSourceStatus.listToJson(listOf(ok)))
        assertEquals(listOf(ok), restored)
        assertEquals(net.wastu.artha.data.providers.AccountStatus.PARTIAL, ok.status)

        val failed = net.wastu.artha.core.bridge.BridgeSourceStatus.failed(ok, Provider.SHOPEE, IllegalStateException("Shopee login expired"), 9_000L, null)
        assertEquals(5_000L, failed.syncedAt)
        assertEquals(9_000L, failed.attemptedAt)
        assertEquals(net.wastu.artha.data.providers.AccountStatus.NEEDS_LOGIN, failed.status)
    }
}
