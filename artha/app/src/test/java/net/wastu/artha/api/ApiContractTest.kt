package net.wastu.artha.api

import net.wastu.artha.api.model.BatchUpdateRequest
import net.wastu.artha.api.model.BatchUpdateResponse
import net.wastu.artha.api.model.TransactionSummaryDto
import net.wastu.artha.api.model.UpdateTransactionRequest
import net.wastu.artha.api.spec.AgentTools
import net.wastu.artha.api.spec.OpenApiGenerator
import net.wastu.artha.api.spec.ScalarDocs
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ApiContractTest {

    @Test
    fun openApiSpecIsValidAndContainsAllEndpoints() {
        val jsonStr = OpenApiGenerator.generateJson()
        val spec = JSONObject(jsonStr)

        assertEquals("3.1.0", spec.getString("openapi"))
        assertTrue(spec.has("info"))
        val paths = spec.getJSONObject("paths")

        val expectedEndpoints = listOf(
            "/api/v1/transactions",
            "/api/v1/transactions/{key}",
            "/api/v1/transactions/batch",
            "/api/v1/summary",
            "/api/v1/categories",
            "/api/v1/categories/{name}",
            "/api/v1/knowledge",
            "/api/v1/actions/sync",
            "/api/v1/agent/tools",
        )

        for (endpoint in expectedEndpoints) {
            assertTrue("Expected endpoint $endpoint in OpenAPI paths", paths.has(endpoint))
        }
    }

    @Test
    fun agentToolsDefinitionContainsRequiredTools() {
        val jsonStr = AgentTools.generateJson()
        val root = JSONObject(jsonStr)
        val tools = root.getJSONArray("tools")

        val toolNames = mutableListOf<String>()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            assertEquals("function", t.getString("type"))
            val fn = t.getJSONObject("function")
            toolNames.add(fn.getString("name"))
        }

        val expectedTools = listOf(
            "list_transactions",
            "get_transaction_details",
            "update_transaction",
            "batch_update_transactions",
            "get_spending_summary",
            "list_categories",
            "create_category",
            "get_knowledge",
            "update_knowledge",
            "trigger_sync",
        )

        for (expected in expectedTools) {
            assertTrue("Expected tool $expected in agent tools list", toolNames.contains(expected))
        }
    }

    @Test
    fun scalarDocsHtmlPointsToOpenApiJson() {
        val html = ScalarDocs.HTML
        assertTrue(html.contains("<!doctype html>"))
        assertTrue(html.contains("/api/v1/openapi.json"))
        assertTrue(html.contains("@scalar/api-reference"))
    }

    @Test
    fun transactionSummaryDtoSerializesCorrectly() {
        val tx = UnifiedTransaction(
            provider = Provider.GOJEK,
            providerTransactionId = "order-12345",
            direction = Direction.DEBIT,
            money = Money(4500000), // Rp 45.000
            occurredAt = Instant.parse("2026-04-10T12:00:00Z"),
            description = "GoFood: Bakmi GM",
            rawPayload = """{"merchantName":"Bakmi GM Sunda"}""",
            category = "Dining Out",
            userNote = "Team lunch",
            aiReasoning = "Restaurant food purchase",
        )

        val dto = TransactionSummaryDto.fromDomain(tx)
        val json = dto.toJson()

        assertEquals("GOJEK:order-12345", json.getString("key"))
        assertEquals("GOJEK", json.getString("provider"))
        assertEquals("Gojek", json.getString("providerName"))
        assertEquals("DEBIT", json.getString("direction"))
        assertEquals(4500000L, json.getLong("amountMinor"))
        assertEquals(45000L, json.getLong("amountRupiah"))
        assertEquals("Dining Out", json.getString("category"))
        assertEquals("Team lunch", json.getString("userNote"))
        assertEquals("Bakmi GM Sunda", json.getString("merchant"))
    }

    @Test
    fun batchUpdateParsingAndResponse() {
        val reqJson = JSONObject().apply {
            val arr = JSONArray().apply {
                put(JSONObject().apply {
                    put("key", "GOJEK:1")
                    put("category", "Dining Out")
                    put("userNote", "Lunch")
                })
                put(JSONObject().apply {
                    put("key", "JAGO:2")
                    put("category", "Groceries")
                    put("reason", "Indomaret grocery snacks")
                })
            }
            put("updates", arr)
        }

        val req = BatchUpdateRequest.fromJson(reqJson)
        assertEquals(2, req.updates.size)
        assertEquals("GOJEK:1", req.updates[0].key)
        assertEquals("Dining Out", req.updates[0].category)
        assertEquals("Lunch", req.updates[0].userNote)

        assertEquals("JAGO:2", req.updates[1].key)
        assertEquals("Groceries", req.updates[1].category)
        assertEquals("Indomaret grocery snacks", req.updates[1].reason)

        val resp = BatchUpdateResponse(
            totalReceived = 2,
            successCount = 2,
            failureCount = 0,
            updatedKeys = listOf("GOJEK:1", "JAGO:2"),
            errors = emptyMap(),
        )
        val respJson = resp.toJson()
        assertEquals(2, respJson.getInt("totalReceived"))
        assertEquals(2, respJson.getInt("successCount"))
        assertEquals(0, respJson.getInt("failureCount"))
        assertEquals(2, respJson.getJSONArray("updatedKeys").length())
    }

    @Test
    fun updateTransactionRequestNullHandling() {
        val json = JSONObject().apply {
            put("category", "Groceries")
            put("userNote", JSONObject.NULL)
        }
        val req = UpdateTransactionRequest.fromJson(json)
        assertEquals("Groceries", req.category)
        assertEquals(null, req.userNote)
    }
}
