package net.wastu.artha.notifications

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderNotificationRulesTest {
    private val now = Instant.parse("2026-11-09T10:00:00Z")

    private fun tx(id: String, at: String, provider: Provider = Provider.JAGO, direction: Direction = Direction.DEBIT, confirmed: Boolean = true) =
        UnifiedTransaction(provider, id, direction, Money(25_000_00), Instant.parse(at), "Kopi $id", "{}", isConfirmed = confirmed)

    @Test
    fun mapsPackagesToProviders() {
        assertEquals(Provider.GOJEK, ProviderNotificationRules.providerFor("com.gojek.app"))
        assertNull(ProviderNotificationRules.providerFor("com.example.other"))
    }

    @Test
    fun bankNotificationsAlwaysSync() {
        assertTrue(ProviderNotificationRules.worthSyncing(Provider.JAGO, "Hai", "Selamat pagi"))
    }

    @Test
    fun promotionsFromOtherAppsDoNotSync() {
        assertFalse(ProviderNotificationRules.worthSyncing(Provider.SHOPEE, "Flash sale!", "Diskon hingga 90% hari ini"))
        assertTrue(ProviderNotificationRules.worthSyncing(Provider.GRAB, "Pembayaran berhasil", null))
        assertTrue(ProviderNotificationRules.worthSyncing(Provider.GOJEK, "GoPay", "Kamu bayar Rp25.000"))
    }

    @Test
    fun firstImportNeverAlerts() {
        assertTrue(ProviderNotificationRules.freshPayments(emptySet(), listOf(tx("1", "2026-11-09T09:00:00Z")), now).isEmpty())
    }

    @Test
    fun onlyNewConfirmedRecentPaymentsAlert() {
        val fresh = tx("new", "2026-11-09T09:30:00Z")
        val current = listOf(
            tx("known", "2026-11-09T09:00:00Z"),
            fresh,
            tx("old", "2026-10-01T09:00:00Z"),
            tx("pending", "2026-11-09T09:40:00Z", confirmed = false),
        )
        val result = ProviderNotificationRules.freshPayments(setOf("JAGO:known"), current, now)
        assertEquals(listOf("JAGO:new"), result.map { it.key })
    }

    @Test
    fun singlePaymentAlertNamesAmountAndSource() {
        val alert = ProviderNotificationRules.alertFor(listOf(tx("1", "2026-11-09T09:00:00Z", Provider.GOJEK)))!!
        assertTrue(alert.title.startsWith("Paid "))
        assertTrue(alert.title.endsWith(" with Gojek"))
        assertEquals("Kopi 1", alert.text)
        val credit = ProviderNotificationRules.alertFor(listOf(tx("1", "2026-11-09T09:00:00Z", direction = Direction.CREDIT)))!!
        assertTrue(credit.title.startsWith("Received "))
        assertTrue(credit.title.endsWith(" in Bank Jago"))
    }

    @Test
    fun severalPaymentsAreSummarised() {
        val alert = ProviderNotificationRules.alertFor(
            listOf(tx("1", "2026-11-09T09:00:00Z"), tx("2", "2026-11-09T09:10:00Z", Provider.GRAB)),
        )!!
        assertEquals("2 new transactions", alert.title)
        assertEquals("Bank Jago, Grab", alert.text)
        assertNull(ProviderNotificationRules.alertFor(emptyList()))
    }
}
