package net.wastu.artha.data.export

import java.time.Instant
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerJsonlExportTest {
    @Test
    fun exportListsEveryProviderWithReconciledRows() {
        val jago = tx(Provider.JAGO, "jago:1")
        val gojek = tx(Provider.GOJEK, "gopay:1")
        val shopee = tx(Provider.SHOPEE, "shopee:1")
        val grab = tx(Provider.GRAB, "grab:1")
        val body = LedgerJsonlExport.build(listOf(jago, gojek, shopee, grab), AnalysisSnapshot(), Instant.parse("2026-01-01T00:00:00Z"))
        val lines = body.trim().lines()
        assertEquals(5, lines.size)
        val meta = JSONObject(lines.first())
        assertEquals("meta", meta.getString("record"))
        assertEquals(4, meta.getInt("transactionCount"))
        assertEquals(1, meta.getJSONObject("providers").getInt(Provider.JAGO.name))
        assertEquals(1, meta.getJSONObject("providers").getInt(Provider.GOJEK.name))
        assertTrue(lines.drop(1).all { JSONObject(it).getString("record") == "transaction" })
    }

    private fun tx(provider: Provider, id: String) = UnifiedTransaction(
        provider = provider,
        providerTransactionId = id,
        direction = Direction.DEBIT,
        money = Money(10_000_00L),
        occurredAt = Instant.parse("2026-01-01T12:00:00Z"),
        description = "Test ${provider.name}",
        rawPayload = """{"origin":"test"}""",
    )
}
