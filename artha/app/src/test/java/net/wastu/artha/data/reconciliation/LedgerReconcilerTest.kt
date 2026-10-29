package net.wastu.artha.data.reconciliation

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Pocket
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerReconcilerTest {

    @Test
    fun reversalsArePairedAndGroupedIntoSingleReversedRecord() {
        val originalDebit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:debit-1",
            direction = Direction.DEBIT,
            money = Money(10_000_00L),
            occurredAt = Instant.parse("2026-10-09T05:54:31Z"),
            description = "LinkedIn Premium",
            rawPayload = """{"code":"PSD02","cardLast4":"8384"}""",
            cardLast4 = "8384",
        )
        val reversalCredit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:credit-1",
            direction = Direction.CREDIT,
            money = Money(10_000_00L),
            occurredAt = Instant.parse("2026-10-09T05:56:24Z"),
            description = "LinkedIn Premium",
            rawPayload = """{"code":"PSD02-R"}""",
        )

        val result = LedgerReconciler.reconcileLedger(listOf(originalDebit, reversalCredit))
        assertEquals(1, result.size)
        val fused = result.first()
        assertEquals("LinkedIn Premium (Reversed)", fused.description)
        assertEquals("8384", fused.cardLast4)
        assertTrue(fused.rawPayload.contains("groupedTransactions"))
    }

    @Test
    fun internalPocketTransfersArePairedIntoSinglePocketTransferRecord() {
        val pocketDebit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:sad-1",
            direction = Direction.DEBIT,
            money = Money(5_000_000_00L),
            occurredAt = Instant.parse("2026-09-20T06:00:00Z"),
            description = "Pocket: Main Pocket",
            rawPayload = """{"code":"SAD02","localized":"Main Pocket"}""",
            pocket = Pocket("103657482926", "Business Pocket"),
        )
        val pocketCredit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:sac-1",
            direction = Direction.CREDIT,
            money = Money(5_000_000_00L),
            occurredAt = Instant.parse("2026-09-20T06:00:01Z"),
            description = "Pocket: Business",
            rawPayload = """{"code":"SAC02","localized":"Business"}""",
            pocket = Pocket("102670254190", "Main Pocket"),
        )

        val result = LedgerReconciler.reconcileLedger(listOf(pocketDebit, pocketCredit))
        assertEquals(1, result.size)
        val fused = result.first()
        assertEquals("Pocket Transfer: Business Pocket to Main Pocket", fused.description)
        assertEquals(net.wastu.artha.core.model.TransactionType.TRANSFER, fused.type)
        assertTrue(fused.rawPayload.contains("isPocketTransfer"))
    }

    @Test
    fun crossProviderOrdersAndBankDebitsAreFusedWithGroupedMetadata() {
        val shopeeOrder = UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:245292854212545",
            direction = Direction.DEBIT,
            money = Money(35_600_00L),
            occurredAt = Instant.parse("2026-10-10T00:54:14Z"),
            description = "Ready Pen Kualitas Air TDS 3-in-1",
            rawPayload = """{"shopName":"Wodon Indonesia Official Store"}""",
        )
        val jagoDebit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:261010-VBBF",
            direction = Direction.DEBIT,
            money = Money(35_600_00L),
            occurredAt = Instant.parse("2026-10-10T00:55:08Z"),
            description = "Shopee",
            rawPayload = """{"code":"PSD02","cardLast4":"8384"}""",
            pocket = Pocket("102670254190", "Main Pocket"),
            cardLast4 = "8384",
        )

        val result = LedgerReconciler.reconcileLedger(listOf(shopeeOrder, jagoDebit))
        assertEquals(1, result.size)
        val fused = result.first()
        assertEquals(Provider.SHOPEE, fused.provider)
        assertEquals("Ready Pen Kualitas Air TDS 3-in-1", fused.description)
        assertEquals("8384", fused.cardLast4)
        assertEquals("Main Pocket", fused.pocket?.name)
        assertTrue(fused.rawPayload.contains("groupedTransactions"))
    }

    @Test
    fun multiDayMarketplaceCheckoutAndJagoDebitsAreFusedWithoutDuplicates() {
        val shopeeOrder = UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:243442014262713",
            direction = Direction.DEBIT,
            money = Money(520_645_00L),
            occurredAt = Instant.parse("2026-09-21T00:16:52Z"), // 2.4 days after checkout
            description = "Ortuseight Sepatu Sneakers Bristol 1.2 - Ash Grey",
            rawPayload = """{"origin":"shopee-order-api","shopName":"RANK Sports Official"}""",
        )
        val jagoDebit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:260918-BX2R-GEMHA8",
            direction = Direction.DEBIT,
            money = Money(520_645_00L),
            occurredAt = Instant.parse("2026-09-18T14:47:21Z"), // checkout debit
            description = "Shopee",
            rawPayload = """{"origin":"jago-linked-local-hive","cardLast4":"8384"}""",
            cardLast4 = "8384",
        )

        val result = LedgerReconciler.reconcileLedger(listOf(shopeeOrder, jagoDebit))
        assertEquals(1, result.size)
        val fused = result.first()
        assertEquals("Ortuseight Sepatu Sneakers Bristol 1.2 - Ash Grey", fused.description)
        assertEquals("8384", fused.cardLast4)
        assertTrue(fused.rawPayload.contains("groupedTransactions"))
    }

    @Test
    fun duplicateSyntheticJagoLinkedCopiesArePrunedWhenRealFoodOrdersExist() {
        val jagoDebit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:260928-T3XP",
            direction = Direction.DEBIT,
            money = Money(75_180_00L),
            occurredAt = Instant.parse("2026-09-28T06:07:30Z"),
            description = "Shopee",
            rawPayload = """{"origin":"jago-linked-local-hive","cardLast4":"8384"}""",
        )
        val syntheticShopeeLinked = UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:260928-T3XP",
            direction = Direction.DEBIT,
            money = Money(75_180_00L),
            occurredAt = Instant.parse("2026-09-28T06:07:30Z"),
            description = "Shopee",
            rawPayload = """{"origin":"jago-linked-local-hive","cardLast4":"8384"}""",
        )
        val realShopeeFoodOrder = UnifiedTransaction(
            provider = Provider.SHOPEE,
            providerTransactionId = "shopee:3280254410673152098",
            direction = Direction.DEBIT,
            money = Money(75_180_00L),
            occurredAt = Instant.parse("2026-09-28T06:07:31Z"),
            description = "SeIndonesia (Sei Sapi Dan Ayam) - [FS] #B2G2",
            rawPayload = """{"origin":"shopeefood-order-api","restaurantName":"SeIndonesia"}""",
        )

        val result = LedgerReconciler.reconcileLedger(listOf(jagoDebit, syntheticShopeeLinked, realShopeeFoodOrder))
        assertEquals(1, result.size)
        val fused = result.first()
        assertEquals("SeIndonesia (Sei Sapi Dan Ayam) - [FS] #B2G2", fused.description)
        assertTrue(fused.rawPayload.contains("groupedTransactions"))
    }

    @Test
    fun gopayOrderAndJagoDebitAreFusedWithReferenceMatching() {
        val gopayOrder = UnifiedTransaction(
            provider = Provider.GOJEK,
            providerTransactionId = "gopay:0320261015013200cLFRf1Yw0yID",
            direction = Direction.DEBIT,
            money = Money(41_100_00L),
            occurredAt = Instant.parse("2026-10-15T01:32:00Z"),
            description = "Indomaret",
            rawPayload = """{"origin":"gopay-order-history","orderNumber":"0320261015013200cLFRf1Yw0yID","isQris":true}""",
            isQris = true,
        )
        val jagoDebit = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:261015-FB7X-ZECLX7",
            direction = Direction.DEBIT,
            money = Money(41_100_00L),
            occurredAt = Instant.parse("2026-10-15T01:32:22Z"),
            description = "GoPay Payment",
            rawPayload = """{"code":"GPD05","fields":{"f6":"0320261015013200cLFRf1Yw0yID-1","f17":"GoPay"},"pocketName":"Main Pocket"}""",
            pocket = Pocket("102670254190", "Main Pocket"),
        )

        val result = LedgerReconciler.reconcileLedger(listOf(gopayOrder, jagoDebit))
        assertEquals(1, result.size)
        val fused = result.first()
        assertEquals(Provider.GOJEK, fused.provider)
        assertEquals("Indomaret", fused.description)
        assertTrue(fused.isQris)
        assertEquals("Main Pocket", fused.pocket?.name)
        assertTrue(fused.rawPayload.contains("groupedTransactions"))
    }

    @Test
    fun jagoEWalletTopUpsAreIdentifiedAsWalletFunding() {
        val topUp = UnifiedTransaction(
            provider = Provider.JAGO,
            providerTransactionId = "jago:topup-1",
            direction = Direction.DEBIT,
            money = Money(100_000_00L),
            occurredAt = Instant.parse("2026-10-15T01:32:22Z"),
            description = "Top Up OVO",
            rawPayload = """{"code":"WTD-AJ-EWALLET","counterpart":"OVO","fields":{"f16":"{\"code\":\"C058\",\"en\":\"Top up\"}"}}""",
        )
        // OVO is read through Grab; with Grab connected the top-up is a transfer, not spending.
        val grabRide = UnifiedTransaction(Provider.GRAB, "grab:ride-1", Direction.DEBIT, Money(20_000_00L), Instant.parse("2026-10-14T01:00:00Z"), "Ride", "{}")
        val reconciled = LedgerReconciler.reconcileLedger(listOf(topUp, grabRide)).filter { it.provider == Provider.JAGO }
        assertEquals(1, reconciled.size)
        val payload = org.json.JSONObject(reconciled.first().rawPayload)
        assertTrue(payload.getBoolean("isWalletFunding"))
    }
}
