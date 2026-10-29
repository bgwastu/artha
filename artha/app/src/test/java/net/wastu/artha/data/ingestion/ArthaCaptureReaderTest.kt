package net.wastu.artha.data.ingestion

import java.io.File
import kotlinx.coroutines.test.runTest
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Replays real Artha-hook capture output through ArthaCaptureReader's dedupe + page parser path. */
class ArthaCaptureReaderTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun captureLine(seq: Int, ts: Long, url: String, body: String) =
        """{"seq":$seq,"ts":$ts,"method":"POST","url":"$url","code":200,"req_body":"{}","resp_body":""" +
            JSONObject.quote(body) + "}"

    private fun snapshot(content: () -> String): File = temporary.newFile("capture.jsonl").apply { writeText(content()) }

    @Test fun newestCaptureLineWinsAndRowsParse() = runTest {
        val pageUrl = "https://customer.gopayapi.com/v3/users/order-history"
        val items = JSONArray().apply {
            put(JSONObject().put("order_id", "capt-001").put("description", "Alfamart - UBM Tower")
                .put("service_type", "COLLECT").put("payment_type", "DEBIT")
                .put("status", "COMPLETED").put("display_status", "Selesai")
                .put("order_timestamp", "2026-10-19T10:00:00")
                .put("amount", JSONObject().put("value", 38400).put("currency", "IDR").put("display_value", "Rp38.400"))
                .put("payment_method_breakup", JSONArray()
                    .put(JSONObject().put("payment_method", "JAGO").put("payment_method_name", "Jago"))))
        }
        val stale = JSONObject().put("success", true).put("data", JSONObject().put("completed", JSONArray()
            .put(JSONObject().put("group_key", "1").put("display_date", "2026-10-19").put("items", JSONArray())))).toString()
        val fresh = JSONObject().put("success", true).put("data", JSONObject().put("completed", JSONArray()
            .put(JSONObject().put("group_key", "1").put("display_date", "2026-10-19").put("items", items)))).toString()
        val file = snapshot { captureLine(1, 100L, pageUrl, stale) + "\n" + captureLine(2, 500L, pageUrl, fresh) }
        val result = ArthaCaptureReader { file }.read()
        assertEquals(1, result.transactions.size)
        val row = result.transactions.first()
        assertEquals(Provider.GOJEK, row.provider)
        assertTrue(row.providerTransactionId.startsWith("gopay:"))
        assertEquals(3_840_000L, row.money.amountMinor)
        assertEquals("IDR", row.money.currencyCode)
        assertEquals(Direction.DEBIT, row.direction)
    }

    @Test fun emptyCaptureReportsANoteInsteadOfThrowing() = runTest {
        val result = ArthaCaptureReader { null }.read()
        assertTrue(result.transactions.isEmpty())
        assertTrue(result.notes.first().contains("missing or empty"))
    }

    @Test fun nonHistoryLinesAreSkipped() = runTest {
        val file = snapshot { captureLine(1, 100L, "https://api.gojekapi.com/gojek/v2/config", "{}") }
        val result = ArthaCaptureReader { file }.read()
        assertTrue(result.transactions.isEmpty())
        assertTrue(result.notes.first().contains("no usable GoPay history"))
    }

    @Test fun redactedCaptureLineStillParses() = runTest {
        // Fixture mirrors the sanitized hook output: header values redacted, request body absent.
        val pageUrl = "https://customer.gopayapi.com/v3/users/order-history?page=1&access_token=[redacted]"
        val body = JSONObject().put("success", true).put("data", JSONObject().put("completed", JSONArray()
            .put(JSONObject().put("group_key", "1").put("display_date", "2026-10-19")
                .put("items", JSONArray().put(JSONObject()
                    .put("order_id", "capt-redacted").put("description", "Indomaret UBM Tower")
                    .put("service_type", "COLLECT").put("payment_type", "DEBIT")
                    .put("status", "COMPLETED").put("display_status", "Selesai")
                    .put("order_timestamp", "2026-10-19T11:00:00")
                    .put("amount", JSONObject().put("value", 15000).put("currency", "IDR").put("display_value", "Rp15.000"))
                    .put("payment_method_breakup", JSONArray()
                        .put(JSONObject().put("payment_method", "JAGO").put("payment_method_name", "Jago")))))))).toString()
        val line = """{"seq":1,"ts":900,"target":"com.gojek.app","code":200,"method":"POST","url":""" +
            JSONObject.quote(pageUrl) +
            ""","req_headers":"authorization: [redacted]\ncookie: [redacted]","req_body":null,""" +
            """"resp_headers":"set-cookie: [redacted]","resp_body":""" + JSONObject.quote(body) + "}"
        val file = snapshot { line }
        val result = ArthaCaptureReader { file }.read()
        assertEquals(1, result.transactions.size)
        val row = result.transactions.single()
        assertEquals(Provider.GOJEK, row.provider)
        assertEquals(1_500_000L, row.money.amountMinor)
        assertEquals(Direction.DEBIT, row.direction)
    }
}
