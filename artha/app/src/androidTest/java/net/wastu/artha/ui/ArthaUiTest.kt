package net.wastu.artha.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.LedgerDatabase
import net.wastu.artha.data.ledger.RoomLedgerRepository
import net.wastu.artha.ui.theme.ArthaTheme
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArthaUiTest {
    @get:Rule
    val compose = createComposeRule()
    private var database: LedgerDatabase? = null
    private var model: ArthaViewModel? = null

    @After
    fun tearDown() {
        compose.runOnIdle { model?.lock() }
        database?.close()
    }

    @Test
    fun deniedRootOffersRetryForAnEmptyLedger() {
        var opened = false
        val viewModel = ArthaViewModel(RootAccess { false }, { opened = true; object : net.wastu.artha.data.ledger.LedgerRepository {
            override fun observeTransactions() = kotlinx.coroutines.flow.flowOf(emptyList<UnifiedTransaction>())
            override suspend fun loadTransactions() = emptyList<UnifiedTransaction>()
            override suspend fun append(transactions: List<UnifiedTransaction>) = net.wastu.artha.data.ledger.AppendResult(0, 0)
        } })
        model = viewModel
        compose.setContent { ArthaTheme(dynamicColor = false) { ArthaApp(viewModel) } }
        compose.waitUntil(10_000) { viewModel.rootGate.value == net.wastu.artha.core.root.RootGateState.DENIED && viewModel.ledgerState.value is LedgerState.Ready }
        compose.onNodeWithText("Root access required").assertExists()
        compose.onNodeWithText("Retry Root Gate").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add transaction").assertDoesNotExist()
        compose.onNodeWithText("Transactions").assertDoesNotExist()
        org.junit.Assert.assertTrue(opened)
    }

    @Test
    fun recordedTransactionCanBeInspectedWithRichDetails() {
        val ledgerDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java)
            .addCallback(LedgerDatabase.AppendOnlyCallback).build()
        database = ledgerDatabase
        val ledger = RoomLedgerRepository(ledgerDatabase.transactions())
        runBlocking {
            ledger.append(
                listOf(
                    UnifiedTransaction(
                        provider = Provider.JAGO,
                        providerTransactionId = "jago:recorded-qris-payment",
                        direction = Direction.DEBIT,
                        money = Money(2_500_000),
                        occurredAt = Instant.parse("2026-10-12T05:00:00Z"),
                        description = "Kopi Kenangan",
                        rawPayload = """{"origin":"jago-linked-local-hive","isQris":true,"paymentMethod":"QRIS","counterpart":"BRI","notes":"Latte"}""",
                        isQris = true,
                    ),
                ),
            )
        }
        // A root denial must leave saved data and details available.
        val viewModel = ArthaViewModel(RootAccess { false }, { ledger })
        model = viewModel
        compose.setContent { ArthaTheme(dynamicColor = false) { ArthaApp(viewModel) } }
        compose.waitUntil(10_000) {
            (viewModel.ledgerState.value as? LedgerState.Ready)?.transactions?.size == 1
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Kopi Kenangan")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add transaction", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Kopi Kenangan").assertIsDisplayed()
        compose.onNodeWithText("QRIS", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Kopi Kenangan").performClick()
        compose.onNode(
            hasText("QRIS") and hasAnySibling(hasText("Payment Method")),
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("BRI", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Latte", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("\"origin\":\"jago-linked-local-hive\"", substring = true).assertDoesNotExist()

    }
}
