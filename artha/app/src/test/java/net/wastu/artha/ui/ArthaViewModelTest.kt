package net.wastu.artha.ui

import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.core.root.RootGateState
import net.wastu.artha.data.ledger.AppendResult
import net.wastu.artha.data.ledger.LedgerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArthaViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val transactions = MutableStateFlow<List<UnifiedTransaction>>(emptyList())
    private var opens = 0
    private val ledger = object : LedgerRepository {
        override fun observeTransactions() = transactions
        override suspend fun append(transactions: List<UnifiedTransaction>) = AppendResult(transactions.size, 0)
    }

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun rootDenialNeverInitializesTheLedger() = runTest(dispatcher) {
        val model = model(RootAccess { false })
        model.enterForeground()
        advanceUntilIdle()
        assertEquals(RootGateState.DENIED, model.rootGate.value)
        assertEquals(0, opens)
        assertEquals(LedgerState.Loading, model.ledgerState.value)
        model.lock()
    }

    @Test
    fun backgroundingClearsLedgerAndNextForegroundRechecksRoot() = runTest(dispatcher) {
        var granted = true
        val model = model(RootAccess { granted })
        model.enterForeground()
        advanceUntilIdle()
        assertEquals(RootGateState.GRANTED, model.rootGate.value)
        assertEquals(LedgerState.Ready(emptyList()), model.ledgerState.value)
        model.lock()
        assertEquals(RootGateState.LOCKED, model.rootGate.value)
        assertEquals(LedgerState.Loading, model.ledgerState.value)
        granted = false
        model.enterForeground()
        advanceUntilIdle()
        assertEquals(RootGateState.DENIED, model.rootGate.value)
        assertEquals(1, opens)
        model.lock()
    }

    @Test
    fun pendingVerificationCannotUnlockAfterBackgrounding() = runTest(dispatcher) {
        val permission = CompletableDeferred<Boolean>()
        val model = model(RootAccess { permission.await() })
        model.enterForeground()
        advanceUntilIdle()
        model.lock()
        permission.complete(true)
        advanceUntilIdle()
        assertEquals(RootGateState.LOCKED, model.rootGate.value)
        assertEquals(0, opens)
    }

    @Test
    fun verificationFailureCanBeRetried() = runTest(dispatcher) {
        var fail = true
        val model = model(RootAccess { if (fail) error("su unavailable") else true })
        model.enterForeground()
        advanceUntilIdle()
        assertEquals(RootGateState.DENIED, model.rootGate.value)
        fail = false
        model.enterForeground()
        advanceUntilIdle()
        assertEquals(RootGateState.GRANTED, model.rootGate.value)
        assertEquals(1, opens)
        model.lock()
    }

    @Test
    fun launchIngestsAndConcurrentRefreshIsIgnored() = runTest(dispatcher) {
        var ingestions = 0
        val finish = CompletableDeferred<Unit>()
        val model = ArthaViewModel(
            RootAccess { true },
            { ledger },
            ingest = {
                ingestions++
                finish.await()
                emptyMap()
            },
            computationDispatcher = dispatcher,
        )
        model.enterForeground()
        advanceUntilIdle()
        assertEquals(1, ingestions)
        assertEquals(true, model.refreshing.value)
        model.refresh()
        advanceUntilIdle()
        assertEquals(1, ingestions)
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, model.refreshing.value)
        model.refresh()
        advanceUntilIdle()
        assertEquals(2, ingestions)
        model.lock()
    }

    @Test
    fun refreshDenialLocksPreviouslyOpenedLedger() = runTest(dispatcher) {
        var granted = true
        val model = ArthaViewModel(RootAccess { granted }, { ledger }, ingest = { emptyMap() }, computationDispatcher = dispatcher)
        model.enterForeground()
        advanceUntilIdle()
        granted = false
        model.refresh()
        advanceUntilIdle()
        assertEquals(RootGateState.DENIED, model.rootGate.value)
        assertEquals(LedgerState.Loading, model.ledgerState.value)
        assertEquals(false, model.refreshing.value)
    }

    private fun model(rootAccess: RootAccess) = ArthaViewModel(rootAccess, { opens++; ledger }, computationDispatcher = dispatcher)
}
