package net.wastu.artha.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import net.wastu.artha.data.providers.ProviderPreferences
import net.wastu.artha.ui.theme.ArthaTheme
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsFiltersImmediatelyAndPersistsAcrossPreferenceInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = ProviderPreferences(context)
        val original = preferences.enabled.value
        var model: ArthaViewModel? = null
        try {
            Provider.entries.forEach { preferences.setEnabled(it, it == Provider.JAGO || it == Provider.SHOPEE) }
            val transactions = MutableStateFlow(listOf(Provider.JAGO, Provider.SHOPEE).map { provider ->
                UnifiedTransaction(provider, "visibility-test:${provider.name}", Direction.DEBIT, Money(10000), Instant.parse("2026-01-01T00:00:00Z"), "${provider.displayName} test payment", "{}")
            })
            val ledger = object : LedgerRepository {
                override fun observeTransactions() = transactions
                override suspend fun loadTransactions() = transactions.value
                override suspend fun append(transactions: List<UnifiedTransaction>) = AppendResult(0, 0)
            }
            val viewModel = ArthaViewModel(RootAccess { true }, { ledger }, preferences = preferences)
            model = viewModel
            compose.setContent { ArthaTheme(dynamicColor = false) { ArthaApp(viewModel) } }
            compose.waitUntil(10_000) { (viewModel.ledgerState.value as? LedgerState.Ready)?.transactions?.size == 2 }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Bank Jago").performClick()
            compose.waitUntil(10_000) { (viewModel.ledgerState.value as? LedgerState.Ready)?.transactions?.map { it.provider } == listOf(Provider.SHOPEE) }
            assertEquals(setOf(Provider.SHOPEE), ProviderPreferences(context).enabled.value)
            compose.onNodeWithText("Shopee").performClick()
            compose.waitUntil(10_000) { (viewModel.ledgerState.value as? LedgerState.Ready)?.transactions?.isEmpty() == true }
            assertEquals(emptySet<Provider>(), ProviderPreferences(context).enabled.value)
            compose.onNodeWithText("Bank Jago").performClick()
            compose.waitUntil(10_000) { (viewModel.ledgerState.value as? LedgerState.Ready)?.transactions?.map { it.provider } == listOf(Provider.JAGO) }
            assertEquals(2, transactions.value.size) // Hiding never removes Ledger history.
            compose.onNodeWithText("Transactions").performClick()
            compose.onNodeWithText("Bank Jago test payment").assertExists()
        } finally {
            compose.runOnIdle { model?.lock() }
            Provider.entries.forEach { preferences.setEnabled(it, it in original) }
        }
    }
}
