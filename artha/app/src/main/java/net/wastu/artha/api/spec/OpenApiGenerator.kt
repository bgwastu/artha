package net.wastu.artha.api.spec

import org.json.JSONArray
import org.json.JSONObject

object OpenApiGenerator {

    fun generateJson(): String {
        val root = JSONObject()
        root.put("openapi", "3.1.0")

        val info = JSONObject().apply {
            put("title", "Artha API")
            put("description", "Direct HTTP API for Artha transactions and spending insights, running locally on Android and shared through a temporary public link. Designed for AI agents, automation, and personal tools.")
            put("version", "1.0.0")
        }
        root.put("info", info)

        val paths = JSONObject()

        // /api/v1/transactions
        val txPath = JSONObject()
        val txGet = JSONObject().apply {
            put("summary", "List transactions")
            put("description", "Query unified transactions with flexible filtering by date, provider, category, amount, and search keyword.")
            put("operationId", "listTransactions")
            val params = JSONArray().apply {
                put(param("limit", "query", "integer", "Maximum number of transactions to return (default 50, max 200)"))
                put(param("offset", "query", "integer", "Pagination offset (default 0)"))
                put(param("month", "query", "string", "Filter by Year-Month (e.g. 2026-04)"))
                put(param("from", "query", "string", "Filter transactions on or after this ISO-8601 timestamp"))
                put(param("to", "query", "string", "Filter transactions before this ISO-8601 timestamp"))
                put(param("provider", "query", "string", "Filter by provider (JAGO, GOJEK, SHOPEE, GRAB)"))
                put(param("direction", "query", "string", "Filter by flow direction (DEBIT or CREDIT)"))
                put(param("category", "query", "string", "Filter by category name (e.g. 'Dining Out', 'Groceries')"))
                put(param("search", "query", "string", "Search keyword in description, merchant, or user note"))
                put(param("unreviewedOnly", "query", "boolean", "Only return transactions needing classification review"))
                put(param("minAmountMinor", "query", "integer", "Minimum amount in minor units (1 IDR = 100 minor units)"))
                put(param("maxAmountMinor", "query", "integer", "Maximum amount in minor units"))
            }
            put("parameters", params)
            put("responses", jsonResponse("200", "List of transactions and pagination metadata"))
        }
        txPath.put("get", txGet)
        paths.put("/api/v1/transactions", txPath)

        // /api/v1/transactions/{key}
        val txKeyPath = JSONObject()
        val txKeyGet = JSONObject().apply {
            put("summary", "Get transaction details")
            put("description", "Get full details for a transaction including parsed food order items, ride routes, and sanitized source evidence.")
            put("operationId", "getTransactionDetails")
            put("parameters", JSONArray().put(param("key", "path", "string", "Transaction key (e.g. JAGO:12345)", required = true)))
            put("responses", jsonResponse("200", "Detailed transaction information"))
        }
        val txKeyPatch = JSONObject().apply {
            put("summary", "Update transaction")
            put("description", "Update the category, user note, or classification reasoning of a transaction.")
            put("operationId", "updateTransaction")
            put("parameters", JSONArray().put(param("key", "path", "string", "Transaction key", required = true)))
            put("requestBody", requestBody(JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("category", JSONObject().put("type", "string").put("description", "Target category name"))
                    put("userNote", JSONObject().put("type", "string").put("description", "Custom user note (null or empty to remove)"))
                    put("reason", JSONObject().put("type", "string").put("description", "Explanation or reasoning for this classification"))
                }
                put("properties", props)
            }))
            put("responses", jsonResponse("200", "Transaction updated successfully"))
        }
        txKeyPath.put("get", txKeyGet)
        txKeyPath.put("patch", txKeyPatch)
        paths.put("/api/v1/transactions/{key}", txKeyPath)

        // /api/v1/transactions/batch
        val batchPath = JSONObject()
        val batchPost = JSONObject().apply {
            put("summary", "Batch update transactions")
            put("description", "Update categories, user notes, and reasons for multiple transactions in a single call. Ideal for AI agents.")
            put("operationId", "batchUpdateTransactions")
            put("requestBody", requestBody(JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("updates"))
                val props = JSONObject().apply {
                    val updateItem = JSONObject().apply {
                        put("type", "object")
                        put("required", JSONArray().put("key"))
                        val itemProps = JSONObject().apply {
                            put("key", JSONObject().put("type", "string"))
                            put("category", JSONObject().put("type", "string"))
                            put("userNote", JSONObject().put("type", "string"))
                            put("reason", JSONObject().put("type", "string"))
                        }
                        put("properties", itemProps)
                    }
                    put("updates", JSONObject().put("type", "array").put("items", updateItem))
                }
                put("properties", props)
            }))
            put("responses", jsonResponse("200", "Batch update result"))
        }
        batchPath.put("post", batchPost)
        paths.put("/api/v1/transactions/batch", batchPath)

        // /api/v1/summary
        val summaryPath = JSONObject()
        val summaryGet = JSONObject().apply {
            put("summary", "Get monthly spending summary")
            put("description", "Get spending breakdown, category totals, percentage shares, comparison with previous month, and generated human summary.")
            put("operationId", "getSpendingSummary")
            put("parameters", JSONArray().put(param("month", "query", "string", "Year-Month (e.g. 2026-04). Defaults to current month.")))
            put("responses", jsonResponse("200", "Monthly spending analysis"))
        }
        summaryPath.put("get", summaryGet)
        paths.put("/api/v1/summary", summaryPath)

        // /api/v1/categories
        val catPath = JSONObject()
        val catGet = JSONObject().apply {
            put("summary", "List categories")
            put("description", "List all default and custom spending categories with transaction counts.")
            put("operationId", "listCategories")
            put("responses", jsonResponse("200", "Categories list"))
        }
        val catPost = JSONObject().apply {
            put("summary", "Add custom category")
            put("description", "Create a new custom spending category.")
            put("operationId", "createCategory")
            put("requestBody", requestBody(JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("name"))
                put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
            }))
            put("responses", jsonResponse("200", "Category created"))
        }
        catPath.put("get", catGet)
        catPath.put("post", catPost)
        paths.put("/api/v1/categories", catPath)

        // /api/v1/categories/{name}
        val catNamePath = JSONObject()
        val catDelete = JSONObject().apply {
            put("summary", "Delete custom category")
            put("description", "Delete an existing custom category.")
            put("operationId", "deleteCategory")
            put("parameters", JSONArray().put(param("name", "path", "string", "Name of the custom category", required = true)))
            put("responses", jsonResponse("200", "Category deleted"))
        }
        catNamePath.put("delete", catDelete)
        paths.put("/api/v1/categories/{name}", catNamePath)

        // /api/v1/knowledge
        val knowledgePath = JSONObject()
        val knowledgeGet = JSONObject().apply {
            put("summary", "Get categorization knowledge")
            put("description", "Read the user knowledge text that guides classification and AI behavior.")
            put("operationId", "getKnowledge")
            put("responses", jsonResponse("200", "Knowledge text"))
        }
        val knowledgePut = JSONObject().apply {
            put("summary", "Update categorization knowledge")
            put("description", "Save updated guidelines for spending categorization.")
            put("operationId", "updateKnowledge")
            put("requestBody", requestBody(JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("knowledge"))
                put("properties", JSONObject().put("knowledge", JSONObject().put("type", "string")))
            }))
            put("responses", jsonResponse("200", "Knowledge updated"))
        }
        knowledgePath.put("get", knowledgeGet)
        knowledgePath.put("put", knowledgePut)
        paths.put("/api/v1/knowledge", knowledgePath)

        // /api/v1/actions/sync
        val syncPath = JSONObject()
        val syncPost = JSONObject().apply {
            put("summary", "Trigger provider sync")
            put("description", "Request fresh transaction import from installed apps on the device using root.")
            put("operationId", "triggerSync")
            put("requestBody", requestBody(JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().put("provider", JSONObject().put("type", "string").put("enum", JSONArray().put("JAGO").put("GOJEK").put("SHOPEE").put("GRAB"))))
            }, required = false))
            put("responses", jsonResponse("200", "Sync triggered"))
        }
        syncPath.put("post", syncPost)
        paths.put("/api/v1/actions/sync", syncPath)

        // /api/v1/agent/tools
        val toolsPath = JSONObject()
        val toolsGet = JSONObject().apply {
            put("summary", "Get AI agent tool definitions")
            put("description", "Returns ready-to-use OpenAI / Anthropic format tool definitions for AI agents.")
            put("operationId", "getAgentTools")
            put("responses", jsonResponse("200", "Tools schema"))
        }
        toolsPath.put("get", toolsGet)
        paths.put("/api/v1/agent/tools", toolsPath)

        root.put("paths", paths)
        return root.toString(2)
    }

    private fun param(name: String, `in`: String, type: String, description: String, required: Boolean = false): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("in", `in`)
            put("required", required)
            put("description", description)
            put("schema", JSONObject().put("type", type))
        }

    private fun requestBody(schema: JSONObject, required: Boolean = true): JSONObject =
        JSONObject().apply {
            put("required", required)
            val content = JSONObject().apply {
                put("application/json", JSONObject().put("schema", schema))
            }
            put("content", content)
        }

    private fun jsonResponse(code: String, description: String): JSONObject =
        JSONObject().apply {
            put(code, JSONObject().apply {
                put("description", description)
                put("content", JSONObject().put("application/json", JSONObject().put("schema", JSONObject().put("type", "object"))))
            })
        }
}

object AgentTools {
    fun generateJson(): String {
        val root = JSONObject()
        val tools = JSONArray()

        tools.put(tool(
            name = "list_transactions",
            description = "List and filter spending transactions in Artha. Supports filtering by date, month (YYYY-MM), provider (JAGO, GOJEK, SHOPEE, GRAB), category, direction (DEBIT/CREDIT), search keyword, and amounts.",
            properties = mapOf(
                "limit" to prop("integer", "Maximum number of transactions to return (default 50, max 200)"),
                "offset" to prop("integer", "Pagination offset (default 0)"),
                "month" to prop("string", "Year-Month format (e.g. 2026-04)"),
                "from" to prop("string", "Filter transactions on or after this ISO timestamp"),
                "to" to prop("string", "Filter transactions before this ISO timestamp"),
                "provider" to prop("string", "Provider name (JAGO, GOJEK, SHOPEE, GRAB)", enumValues = listOf("JAGO", "GOJEK", "SHOPEE", "GRAB")),
                "direction" to prop("string", "Money direction (DEBIT for expense, CREDIT for income)", enumValues = listOf("DEBIT", "CREDIT")),
                "category" to prop("string", "Category name"),
                "search" to prop("string", "Keyword search across description, merchant, and user note"),
                "unreviewedOnly" to prop("boolean", "If true, only returns transactions needing review"),
                "minAmountMinor" to prop("integer", "Minimum amount in minor units (1 rupiah = 100 minor units)"),
                "maxAmountMinor" to prop("integer", "Maximum amount in minor units")
            )
        ))

        tools.put(tool(
            name = "get_transaction_details",
            description = "Get full detailed evidence for a specific transaction key, including itemized food dishes, route, merchant, and source evidence.",
            properties = mapOf(
                "key" to prop("string", "Transaction key (e.g. JAGO:12345 or GOJEK:67890)")
            ),
            required = listOf("key")
        ))

        tools.put(tool(
            name = "update_transaction",
            description = "Update the category, user note, or classification reasoning for a transaction in Artha.",
            properties = mapOf(
                "key" to prop("string", "Transaction key"),
                "category" to prop("string", "Target category name"),
                "userNote" to prop("string", "Custom note to attach to the transaction"),
                "reason" to prop("string", "Reasoning for the categorization")
            ),
            required = listOf("key")
        ))

        tools.put(tool(
            name = "batch_update_transactions",
            description = "Update categories and/or notes for multiple transactions at once in a single roundtrip.",
            properties = mapOf(
                "updates" to JSONObject().apply {
                    put("type", "array")
                    put("description", "List of transaction updates")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("required", JSONArray().put("key"))
                        put("properties", JSONObject().apply {
                            put("key", prop("string", "Transaction key"))
                            put("category", prop("string", "Target category name"))
                            put("userNote", prop("string", "Custom user note"))
                            put("reason", prop("string", "Reasoning for this category"))
                        })
                    })
                }
            ),
            required = listOf("updates")
        ))

        tools.put(tool(
            name = "get_spending_summary",
            description = "Get monthly spending analysis, total personal spending, category breakdown with percentages, and month-over-month comparison.",
            properties = mapOf(
                "month" to prop("string", "Year-Month in YYYY-MM format (e.g. 2026-04). Defaults to current month if omitted.")
            )
        ))

        tools.put(tool(
            name = "list_categories",
            description = "List all spending categories in Artha with transaction counts.",
            properties = emptyMap()
        ))

        tools.put(tool(
            name = "create_category",
            description = "Create a new custom spending category in Artha.",
            properties = mapOf(
                "name" to prop("string", "Name of the new category")
            ),
            required = listOf("name")
        ))

        tools.put(tool(
            name = "get_knowledge",
            description = "Get user knowledge guidelines that guide spending categorization.",
            properties = emptyMap()
        ))

        tools.put(tool(
            name = "update_knowledge",
            description = "Update user knowledge guidelines for spending categorization.",
            properties = mapOf(
                "knowledge" to prop("string", "Guidelines or context for categorization")
            ),
            required = listOf("knowledge")
        ))

        tools.put(tool(
            name = "trigger_sync",
            description = "Request transaction refresh from installed banking/shopping apps on the phone.",
            properties = mapOf(
                "provider" to prop("string", "Specific provider to sync, or omit to sync all", enumValues = listOf("JAGO", "GOJEK", "SHOPEE", "GRAB"))
            )
        ))

        root.put("tools", tools)
        return root.toString(2)
    }

    private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String> = emptyList()): JSONObject {
        val fn = JSONObject().apply {
            put("name", name)
            put("description", description)
            val params = JSONObject().apply {
                put("type", "object")
                val props = JSONObject()
                properties.forEach { (k, v) -> props.put(k, v) }
                put("properties", props)
                if (required.isNotEmpty()) {
                    val req = JSONArray()
                    required.forEach { req.put(it) }
                    put("required", req)
                }
            }
            put("parameters", params)
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", fn)
        }
    }

    private fun prop(type: String, description: String, enumValues: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("description", description)
            if (!enumValues.isNullOrEmpty()) {
                val arr = JSONArray()
                enumValues.forEach { arr.put(it) }
                put("enum", arr)
            }
        }
}

object ScalarDocs {
    const val HTML: String = """<!doctype html>
<html>
  <head>
    <title>Artha API & Agent Reference</title>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <link rel="icon" type="image/png" href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=" />
    <style>
      body {
        margin: 0;
        padding: 0;
        background: #0f172a;
      }
    </style>
  </head>
  <body>
    <script
      id="api-reference"
      data-url="/api/v1/openapi.json"
      data-proxy-url=""
      data-theme="purple"
      src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
  </body>
</html>"""
}
