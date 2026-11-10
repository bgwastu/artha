package net.wastu.artha.data.sync

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.ledger.ProviderObservations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GojekFlashSyncTest {

    @Test fun cooldownOnlyAfterARealLaunch() {
        val now = 1_000_000L
        assertTrue(GojekFlashPolicy.cooldownActive("FLASHED", now - 1_000L, now))
        assertTrue(GojekFlashPolicy.cooldownActive("QUIET", now - 14 * 60_000L, now))
        assertFalse(GojekFlashPolicy.cooldownActive("FLASHED", now - 15 * 60_000L, now))
        assertFalse(GojekFlashPolicy.cooldownActive("SKIPPED_SCREEN_OFF", now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive("ABORTED", now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive("BUSY", now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive(null, now, now + 1_000L))
        assertFalse(GojekFlashPolicy.cooldownActive("FLASHED", null, now))
    }

    @Test fun onlySettledLaunchesUpdateTheLastRunStamp() {
        assertTrue(GojekFlashPolicy.recordsAsLastRun(GojekScreenSync.Result.FLASHED))
        assertTrue(GojekFlashPolicy.recordsAsLastRun(GojekScreenSync.Result.QUIET))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekScreenSync.Result.ABORTED))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekScreenSync.Result.SKIPPED_SCREEN_OFF))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekScreenSync.Result.SKIPPED_COOLDOWN))
        assertFalse(GojekFlashPolicy.recordsAsLastRun(GojekScreenSync.Result.BUSY))
    }

    private fun gopay(id: String, evidence: String, merchant: String? = null) = UnifiedTransaction(
        Provider.GOJEK, "gopay:$id", Direction.DEBIT, Money(2_500_000), Instant.parse("2026-10-10T03:00:00Z"), merchant ?: "GoPay Payment",
        JSONObject().put("origin", "gopay-order-history").put("orderNumber", id).put("paymentEvidence", evidence)
            .apply { merchant?.let { put("merchantName", it) } }.toString(),
    )

    @Test fun walkReopensPaymentsOnlyJagoDescribed() {
        val generic = gopay("a", "jago-linked-order-reference")
        val named = gopay("b", "settled-wallet-history", "Warung Bu Sari")
        assertEquals(listOf("gopay:b"), gopayPaymentsAlreadyRead(listOf(generic, named), emptyList()).map { it.providerTransactionId })
    }

    @Test fun aPaymentTheStoreAlreadyReadIsNotOpenedAgainAndCountsOnce() {
        val generic = gopay("a", "jago-linked-order-reference")
        val read = gopay("a", "gopay-screen", "Warung Bu Sari")
        assertEquals(listOf("gopay:a"), gopayPaymentsAlreadyRead(listOf(generic), listOf(read)).map { it.providerTransactionId })
        // Once the ledger holds the screen revision, the stored copy is not needed to know it was read.
        val revision = ProviderObservations.capture(listOf(generic), listOf(read), 1000)
        assertEquals(1, gopayPaymentsAlreadyRead(listOf(generic) + revision, emptyList()).size)
    }
}
