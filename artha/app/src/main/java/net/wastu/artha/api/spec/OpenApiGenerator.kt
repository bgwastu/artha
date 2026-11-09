package net.wastu.artha.api.spec

import org.json.JSONArray
import org.json.JSONObject

object OpenApiGenerator {
    private val TRANSACTION_TYPES = listOf("expense", "income", "transfer")
    private val PROVIDERS = listOf("JAGO", "GOJEK", "SHOPEE", "GRAB")
    private const val CATEGORY_TYPE_HINT = "expense (default) or income"

    fun generateJson(): String = JSONObject()
        .put("openapi", "3.1.0")
        .put("info", info())
        .put(
            "paths",
            JSONObject()
                .put("/api/v1/transactions", JSONObject().put("get", listTransactions()))
                .put("/api/v1/transactions/{key}", JSONObject().put("get", getTransaction()).put("patch", updateTransaction()))
                .put("/api/v1/transactions/batch", JSONObject().put("post", batchUpdate()))
                .put("/api/v1/summary", JSONObject().put("get", summary()))
                .put("/api/v1/categories", JSONObject().put("get", listCategories()).put("post", createCategory()))
                .put("/api/v1/categories/{name}", JSONObject().put("delete", deleteCategory()))
                .put("/api/v1/knowledge", JSONObject().put("get", getKnowledge()).put("put", updateKnowledge()))
                .put("/api/v1/actions/sync", JSONObject().put("post", triggerSync())),
        )
        .toString(2)

    private fun info() = JSONObject()
        .put("title", "Artha API")
        .put(
            "description",
            "Direct HTTP API for Artha transactions and spending insights, running locally on Android and shared through a temporary public link. " +
                "The server only listens on the device's loopback interface, so requests must come through that link. " +
                "Designed for AI agents, automation, and personal tools.",
        )
        .put("version", "1.0.0")

    private fun listTransactions() = operation(
        summary = "List transactions",
        description = "Query unified transactions with flexible filtering by date, provider, category, amount, and search keyword.",
        operationId = "listTransactions",
        ok = "List of transactions and pagination metadata",
        parameters = listOf(
            param("limit", "query", "integer", "Maximum number of transactions to return (default 50, max 200)"),
            param("offset", "query", "integer", "Pagination offset (default 0)"),
            param("month", "query", "string", "Filter by Year-Month (e.g. 2026-04)"),
            param("from", "query", "string", "Filter transactions on or after this ISO-8601 timestamp"),
            param("to", "query", "string", "Filter transactions before this ISO-8601 timestamp"),
            param("provider", "query", "string", "Filter by provider (JAGO, GOJEK, SHOPEE, GRAB)"),
            param("direction", "query", "string", "Filter by flow direction (DEBIT or CREDIT)"),
            param("type", "query", "string", "Filter by type: expense, income or transfer. Transfers are moves between the user's own accounts and are not counted in totals."),
            param("category", "query", "string", "Filter by category name (e.g. 'Dining Out', 'Groceries')"),
            param("search", "query", "string", "Search keyword in description, merchant, or user note"),
            param("unreviewedOnly", "query", "boolean", "Only return transactions needing classification review"),
            param("minAmountMinor", "query", "integer", "Minimum amount in minor units (1 IDR = 100 minor units)"),
            param("maxAmountMinor", "query", "integer", "Maximum amount in minor units"),
            param("sort", "query", "string", "Sort order: date_desc, date_asc, amount_desc or amount_asc. Defaults to the app's own order."),
        ),
    )

    private fun getTransaction() = operation(
        summary = "Get transaction details",
        description = "Get full details for a transaction including parsed food order items, ride routes, and sanitized source evidence.",
        operationId = "getTransactionDetails",
        ok = "Detailed transaction information",
        parameters = listOf(
            param("key", "path", "string", "Transaction key (e.g. JAGO:12345)", required = true),
            param("include", "query", "string", "Set to 'raw' to also return the unparsed source payload"),
        ),
    )

    private fun updateTransaction() = operation(
        summary = "Update transaction",
        description = "Update the category, type, user note, or classification reasoning of a transaction. A category must belong to the transaction's type; transfers have no categories.",
        operationId = "updateTransaction",
        ok = "Transaction updated successfully",
        parameters = listOf(param("key", "path", "string", "Transaction key", required = true)),
        requestBody = requestBody(
            objectSchema(
                "category" to stringSchema("Target category name from the transaction's own type list"),
                "type" to stringSchema("Reclassify the transaction", TRANSACTION_TYPES),
                "userNote" to stringSchema("Custom user note (null or empty to remove)"),
                "reason" to stringSchema("Explanation or reasoning for this classification"),
            ),
        ),
    )

    private fun batchUpdate() = operation(
        summary = "Batch update transactions",
        description = "Update categories, user notes, and reasons for multiple transactions in a single call. Ideal for AI agents.",
        operationId = "batchUpdateTransactions",
        ok = "Batch update result",
        requestBody = requestBody(
            objectSchema(
                "updates" to JSONObject().put("type", "array").put(
                    "items",
                    objectSchema(
                        "key" to stringSchema(),
                        "category" to stringSchema(),
                        "type" to stringSchema(enum = TRANSACTION_TYPES),
                        "userNote" to stringSchema(),
                        "reason" to stringSchema(),
                        required = listOf("key"),
                    ),
                ),
                required = listOf("updates"),
            ),
        ),
    )

    private fun summary() = operation(
        summary = "Get monthly expense or income summary",
        description = "Get the breakdown for expenses (default) or income: category totals, percentage shares, comparison with previous month, and generated human summary.",
        operationId = "getSpendingSummary",
        ok = "Monthly analysis",
        parameters = listOf(
            param("month", "query", "string", "Year-Month (e.g. 2026-04). Defaults to current month."),
            param("type", "query", "string", CATEGORY_TYPE_HINT),
        ),
    )

    private fun listCategories() = operation(
        summary = "List categories",
        description = "List the expense (default) or income categories with transaction counts.",
        operationId = "listCategories",
        ok = "Categories list",
        parameters = listOf(param("type", "query", "string", CATEGORY_TYPE_HINT)),
    )

    private fun createCategory() = operation(
        summary = "Add custom category",
        description = "Create a new custom spending category.",
        operationId = "createCategory",
        ok = "Category created",
        requestBody = requestBody(
            objectSchema("name" to stringSchema(), "type" to stringSchema(CATEGORY_TYPE_HINT), required = listOf("name")),
        ),
    )

    private fun deleteCategory() = operation(
        summary = "Delete custom category",
        description = "Delete an existing custom category.",
        operationId = "deleteCategory",
        ok = "Category deleted",
        parameters = listOf(
            param("name", "path", "string", "Name of the custom category", required = true),
            param("type", "query", "string", CATEGORY_TYPE_HINT),
        ),
    )

    private fun getKnowledge() = operation(
        summary = "Get categorization knowledge",
        description = "Read the user knowledge text that guides classification and AI behavior.",
        operationId = "getKnowledge",
        ok = "Knowledge text",
    )

    private fun updateKnowledge() = operation(
        summary = "Update categorization knowledge",
        description = "Save updated guidelines for spending categorization.",
        operationId = "updateKnowledge",
        ok = "Knowledge updated",
        requestBody = requestBody(objectSchema("knowledge" to stringSchema(), required = listOf("knowledge"))),
    )

    private fun triggerSync() = operation(
        summary = "Trigger provider sync",
        description = "Request fresh transaction import from installed apps on the device using root.",
        operationId = "triggerSync",
        ok = "Sync triggered",
        requestBody = requestBody(objectSchema("provider" to stringSchema(enum = PROVIDERS)), required = false),
    )

    /** One HTTP operation; every endpoint answers 200 with a JSON object. */
    private fun operation(
        summary: String,
        description: String,
        operationId: String,
        ok: String,
        parameters: List<JSONObject>? = null,
        requestBody: JSONObject? = null,
    ): JSONObject = JSONObject().apply {
        put("summary", summary)
        put("description", description)
        put("operationId", operationId)
        if (parameters != null) put("parameters", JSONArray().apply { parameters.forEach { put(it) } })
        if (requestBody != null) put("requestBody", requestBody)
        put("responses", jsonResponse("200", ok))
    }

    private fun param(name: String, `in`: String, type: String, description: String, required: Boolean = false): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("in", `in`)
            put("required", required)
            put("description", description)
            put("schema", JSONObject().put("type", type))
        }

    private fun stringSchema(description: String? = null, enum: List<String>? = null): JSONObject = JSONObject().apply {
        put("type", "string")
        if (enum != null) put("enum", JSONArray().apply { enum.forEach { put(it) } })
        if (description != null) put("description", description)
    }

    private fun objectSchema(vararg properties: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject().apply {
            put("type", "object")
            if (required.isNotEmpty()) put("required", JSONArray().apply { required.forEach { put(it) } })
            put("properties", JSONObject().apply { properties.forEach { (name, schema) -> put(name, schema) } })
        }

    private fun requestBody(schema: JSONObject, required: Boolean = true): JSONObject =
        JSONObject().apply {
            put("required", required)
            put("content", JSONObject().put("application/json", JSONObject().put("schema", schema)))
        }

    private fun jsonResponse(code: String, description: String): JSONObject =
        JSONObject().put(
            code,
            JSONObject()
                .put("description", description)
                .put("content", JSONObject().put("application/json", JSONObject().put("schema", JSONObject().put("type", "object")))),
        )
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
