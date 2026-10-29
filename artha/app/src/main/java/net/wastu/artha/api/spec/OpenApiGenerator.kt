package net.wastu.artha.api.spec

import org.json.JSONArray
import org.json.JSONObject

object OpenApiGenerator {

    fun generateJson(): String {
        val root = JSONObject()
        root.put("openapi", "3.1.0")

        val info = JSONObject().apply {
            put("title", "Artha API")
            put("description", "Direct HTTP API for Artha transactions and spending insights, running locally on Android and shared through a temporary public link. The server only listens on the device's loopback interface, so requests must come through that link. Designed for AI agents, automation, and personal tools.")
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
                put(param("type", "query", "string", "Filter by type: expense, income or transfer. Transfers are moves between the user's own accounts and are not counted in totals."))
                put(param("category", "query", "string", "Filter by category name (e.g. 'Dining Out', 'Groceries')"))
                put(param("search", "query", "string", "Search keyword in description, merchant, or user note"))
                put(param("unreviewedOnly", "query", "boolean", "Only return transactions needing classification review"))
                put(param("minAmountMinor", "query", "integer", "Minimum amount in minor units (1 IDR = 100 minor units)"))
                put(param("maxAmountMinor", "query", "integer", "Maximum amount in minor units"))
                put(param("sort", "query", "string", "Sort order: date_desc, date_asc, amount_desc or amount_asc. Defaults to the app's own order."))
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
            put("parameters", JSONArray()
                .put(param("key", "path", "string", "Transaction key (e.g. JAGO:12345)", required = true))
                .put(param("include", "query", "string", "Set to 'raw' to also return the unparsed source payload")))
            put("responses", jsonResponse("200", "Detailed transaction information"))
        }
        val txKeyPatch = JSONObject().apply {
            put("summary", "Update transaction")
            put("description", "Update the category, type, user note, or classification reasoning of a transaction. A category must belong to the transaction's type; transfers have no categories.")
            put("operationId", "updateTransaction")
            put("parameters", JSONArray().put(param("key", "path", "string", "Transaction key", required = true)))
            put("requestBody", requestBody(JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("category", JSONObject().put("type", "string").put("description", "Target category name from the transaction's own type list"))
                    put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("expense", "income", "transfer"))).put("description", "Reclassify the transaction"))
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
                            put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("expense", "income", "transfer"))))
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
            put("summary", "Get monthly expense or income summary")
            put("description", "Get the breakdown for expenses (default) or income: category totals, percentage shares, comparison with previous month, and generated human summary.")
            put("operationId", "getSpendingSummary")
            put("parameters", JSONArray().put(param("month", "query", "string", "Year-Month (e.g. 2026-04). Defaults to current month.")).put(param("type", "query", "string", "expense (default) or income")))
            put("responses", jsonResponse("200", "Monthly analysis"))
        }
        summaryPath.put("get", summaryGet)
        paths.put("/api/v1/summary", summaryPath)

        // /api/v1/categories
        val catPath = JSONObject()
        val catGet = JSONObject().apply {
            put("summary", "List categories")
            put("description", "List the expense (default) or income categories with transaction counts.")
            put("operationId", "listCategories")
            put("parameters", JSONArray().put(param("type", "query", "string", "expense (default) or income")))
            put("responses", jsonResponse("200", "Categories list"))
        }
        val catPost = JSONObject().apply {
            put("summary", "Add custom category")
            put("description", "Create a new custom spending category.")
            put("operationId", "createCategory")
            put("requestBody", requestBody(JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("name"))
                put("properties", JSONObject().put("name", JSONObject().put("type", "string")).put("type", JSONObject().put("type", "string").put("description", "expense (default) or income")))
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
            put("parameters", JSONArray().put(param("name", "path", "string", "Name of the custom category", required = true)).put(param("type", "query", "string", "expense (default) or income")))
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
