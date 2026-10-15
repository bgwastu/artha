package net.wastu.artha.data.ingestion

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.wastu.artha.ArthaApplication
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.reconciliation.LedgerReconciler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in rooted-device verification. Run explicitly on the user's phone, never an emulator. */
@RunWith(AndroidJUnit4::class)
class LiveProviderIngestionTest {
    @Test fun realBankHistoryIsDecryptedAndRepeatedIngestionIsIdempotent(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ArthaApplication>()
        assertTrue("Artha must be granted root on the device", app.rootAccess.verify())
        val live = JagoTransactionReader(RootFileReader(app)).transactions()
        assertTrue("Expected actual local Jago history", live.isNotEmpty())
        assertEquals(live.size, live.map { it.providerTransactionId }.toSet().size)
        assertTrue(live.all { it.rawPayload.contains("jago-local-hive") })
        val first = app.ingestion.syncProvider(Provider.JAGO)
        assertFalse("Bank import must succeed", first.startsWith("Unavailable"))
        val before = app.ledger.observeTransactions().first().size
        val second = app.ingestion.syncProvider(Provider.JAGO)
        assertTrue(second.startsWith("0 added"))
        assertEquals(before, app.ledger.observeTransactions().first().size)
        assertTrue(before >= live.size)
        android.util.Log.i("ArthaVerification", "Live transactions=${live.size}; Ledger transactions=$before; " +
            live.groupingBy { it.provider }.eachCount().toString())
    }
    @Test fun realGojekHistoryIsIngestedAndFusedWithJago(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ArthaApplication>()
        assertTrue("Artha must be granted root on the device", app.rootAccess.verify())
        val result = app.ingestion.syncProvider(Provider.GOJEK)
        assertTrue(result.contains("saved") || result.contains("added"))
        val all = app.ledger.observeTransactions().first()
        val reconciled = LedgerReconciler.reconcileLedger(all)
        val gojekTx = reconciled.filter { it.provider == Provider.GOJEK }
        assertTrue("Expected Gojek transactions in ledger", gojekTx.isNotEmpty())
    }
}
