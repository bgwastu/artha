package net.wastu.artha.ui

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.analysis.SpendingState
import net.wastu.artha.data.ledger.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerRecoveryTest {
    private val time = Instant.parse("2026-10-02T00:00:00Z")
    private val payment = UnifiedTransaction(Provider.JAGO, "jago:recovery", Direction.DEBIT,
        Money(10000), time, "Fixture", "{}", category = "Groceries")

    @Test fun failedProjectionKeepsLastValidPaymentsAndFiltersUntilRetrySucceeds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val source = MutableStateFlow<SpendingState>(SpendingState.Ready(listOf(payment), mapOf(Provider.JAGO to 1)))
            var retries = 0
            val model = ArthaViewModel(RootAccess { false }, { error("Shared projection must be reused") },
                clock = Clock.fixed(time, AccountingTime.zone), computationDispatcher = dispatcher, spendingState = source,
                retrySpending = { retries++; source.value = SpendingState.Loading })
            model.enterForeground(); advanceUntilIdle()
            source.value = SpendingState.Failed; advanceUntilIdle()
            assertEquals(LedgerState.Ready(listOf(payment)), model.ledgerState.value)
            assertTrue(model.ledgerLoadFailed.value)
            assertEquals(10000L, model.monthAnalysis.value.totalPersonalSpendingMinor)
            model.setDateRangeFilter(time.plusSeconds(86400).toEpochMilli(), null); advanceUntilIdle()
            assertEquals(LedgerState.Ready(emptyList()), model.ledgerState.value)
            assertEquals(listOf(payment), model.allTransactions.value)
            model.retryLedger(); advanceUntilIdle()
            assertEquals(1, retries)
            assertEquals(LedgerState.Ready(emptyList()), model.ledgerState.value)
            model.clearDateRangeFilter()
            val updated = payment.copy(money = Money(20000))
            source.value = SpendingState.Ready(listOf(updated), mapOf(Provider.JAGO to 1)); advanceUntilIdle()
            assertFalse(model.ledgerLoadFailed.value)
            assertEquals(LedgerState.Ready(listOf(updated)), model.ledgerState.value)
            assertEquals(20000L, model.monthAnalysis.value.totalPersonalSpendingMinor)
            model.lock()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun initialRepositoryFailureCanRetryWithoutAnotherRootRequestOrImport() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var opens = 0
            var rootRequests = 0
            var imports = 0
            val repo = object : LedgerRepository {
                override fun observeTransactions() = MutableStateFlow(listOf(payment))
                override suspend fun append(transactions: List<UnifiedTransaction>) = AppendResult(0, transactions.size)
            }
            val model = ArthaViewModel(RootAccess { rootRequests++; false }, {
                opens++
                if (opens == 1) error("Fixture database temporarily unavailable") else repo
            }, ingest = { imports++; emptyMap() }, computationDispatcher = dispatcher)
            model.enterForeground(); advanceUntilIdle()
            assertEquals(LedgerState.Failed, model.ledgerState.value)
            assertTrue(model.ledgerLoadFailed.value)
            model.retryLedger(); advanceUntilIdle()
            assertTrue(model.ledgerState.value is LedgerState.Ready)
            assertFalse(model.ledgerLoadFailed.value)
            assertEquals(2, opens)
            assertEquals(1, rootRequests)
            assertEquals(0, imports)
            model.lock()
        } finally { Dispatchers.resetMain() }
    }
}
