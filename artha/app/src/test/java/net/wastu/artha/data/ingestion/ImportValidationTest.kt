package net.wastu.artha.data.ingestion

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import net.wastu.artha.core.model.*
import net.wastu.artha.core.root.RootAccess
import net.wastu.artha.data.ledger.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportValidationTest {
    private fun payment() = JSONObject("""{"order_id":"1","payment_type":"DEBIT","amount":{"value":15000},"description":"QRIS Coffee","order_timestamp":"2026-10-02T12:00:00+07:00"}""")

    @Test fun sourceDateDirectionUnitsAndQrisArePreserved() {
        val row = ProviderRowParser.goPay(payment())
        assertEquals(1_500_000L, row.money.amountMinor)
        assertEquals(Instant.parse("2026-10-02T05:00:00Z"), row.occurredAt)
        assertTrue(row.isQris)
        assertTrue(JSONObject(row.rawPayload).getBoolean("isQris"))
        assertEquals(Direction.CREDIT, ProviderRowParser.goPay(payment().put("payment_type", "CREDIT")).direction)
    }

    @Test fun unknownDateDirectionAndZeroAmountAreRejected() {
        assertThrows(Exception::class.java) { ProviderRowParser.goPay(payment().put("order_timestamp", "")) }
        assertThrows(Exception::class.java) { ProviderRowParser.goPay(payment().put("payment_type", "UNKNOWN")) }
        assertThrows(Exception::class.java) { ProviderRowParser.goPay(payment().put("amount", JSONObject().put("value", 0))) }
    }

    @Test fun undocumentedOrderTotalsNeverBecomeTodayOrOneRupiah() {
        assertNull(ProviderRowParser.tokopedia(JSONObject("""{"order_id":"1","total_amount":1000}""")))
    }

    @Test fun oneBadRowDoesNotDiscardValidRowsAndFailureRemainsVisible() {
        val rows = ImportRows()
        rows.readRow { ProviderRowParser.goPay(payment()) }
        rows.readRow { ProviderRowParser.goPay(payment().put("order_timestamp", "bad")) }
        val error = assertThrows(PartialImportException::class.java) { rows.finish() }
        assertEquals(1, error.transactions.size)
    }

    @Test fun coordinatorAppendsPartialValidHistoryWithoutReportingCompleteSuccess() = runTest {
        var saved = emptyList<UnifiedTransaction>()
        val ledger = object : LedgerRepository {
            override fun observeTransactions() = flowOf(saved)
            override suspend fun append(transactions: List<UnifiedTransaction>): AppendResult {
                saved = transactions; return AppendResult(saved.size, 0)
            }
        }
        val adapter = object : ProviderAdapter {
            override val provider = Provider.GOJEK
            override suspend fun ingest(): List<UnifiedTransaction> = throw PartialImportException(listOf(ProviderRowParser.goPay(payment())), "Incomplete cache")
        }
        val coordinator = IngestionCoordinator(RootAccess { true }, { ledger }, listOf(adapter))
        assertTrue(coordinator.syncProvider(Provider.GOJEK).contains("Incomplete cache"))
        assertEquals(1, saved.size)
    }
}
