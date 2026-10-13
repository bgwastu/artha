package net.wastu.artha.data.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import net.wastu.artha.ui.ArthaViewModel
import net.wastu.artha.ui.LedgerState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderPreferencesTest {
    @Test fun visibilityPersistsAndFiltersTheObservedLedgerWithoutDeletingHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = ProviderPreferences(context)
        val original = preferences.enabled.value
        val transactions = MutableStateFlow(listOf(Provider.JAGO, Provider.SHOPEE).map { provider ->
            UnifiedTransaction(provider, "visibility:${provider.name}", Direction.DEBIT, Money(10000), Instant.EPOCH, "Test payment", "{}")
        })
        val ledger = object : LedgerRepository {
            override fun observeTransactions() = transactions
            override suspend fun append(transactions: List<UnifiedTransaction>) = AppendResult(0, 0)
        }
        val model = withContext(Dispatchers.Main) {
            Provider.entries.forEach { preferences.setEnabled(it, true) }
            ArthaViewModel(RootAccess { true }, { ledger }, preferences = preferences).also { it.enterForeground() }
        }
        suspend fun visible(count: Int) = withTimeout(5_000) {
            model.ledgerState.filterIsInstance<LedgerState.Ready>().first { it.transactions.size == count }
        }
        try {
            visible(2)
            withContext(Dispatchers.Main) { model.setProviderEnabled(Provider.JAGO, false) }
            assertEquals(listOf(Provider.SHOPEE), visible(1).transactions.map { it.provider })
            assertEquals(false, Provider.JAGO in ProviderPreferences(context).enabled.value)
            withContext(Dispatchers.Main) { Provider.entries.forEach { model.setProviderEnabled(it, false) } }
            visible(0)
            assertEquals(emptySet<Provider>(), ProviderPreferences(context).enabled.value)
            withContext(Dispatchers.Main) { model.setProviderEnabled(Provider.JAGO, true) }
            assertEquals(listOf(Provider.JAGO), visible(1).transactions.map { it.provider })
            assertEquals(2, transactions.value.size)
        } finally {
            withContext(Dispatchers.Main) {
                model.lock()
                Provider.entries.forEach { preferences.setEnabled(it, it in original) }
            }
        }
    }
}
