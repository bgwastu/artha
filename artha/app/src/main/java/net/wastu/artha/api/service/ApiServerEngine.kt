package net.wastu.artha.api.service

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.wastu.artha.api.model.BatchUpdateRequest
import net.wastu.artha.api.model.BatchUpdateResponse
import net.wastu.artha.api.model.CategoryDto
import net.wastu.artha.api.model.MonthSummaryDto
import net.wastu.artha.api.model.SyncRequest
import net.wastu.artha.api.model.TransactionDetailDto
import net.wastu.artha.api.model.TransactionSummaryDto
import net.wastu.artha.api.model.UpdateTransactionRequest
import net.wastu.artha.api.spec.OpenApiGenerator
import net.wastu.artha.api.spec.ScalarDocs
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.TransactionType
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.SpendingRepository
import net.wastu.artha.data.analysis.SpendingState
import org.json.JSONArray
import org.json.JSONObject

private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) {
    val code = status.description.lowercase().replace(' ', '_')
    respondJson(JSONObject().put("error", JSONObject().put("code", code).put("message", message)), status)
}

private suspend fun ApplicationCall.badParam(name: String, expected: String) =
    fail(HttpStatusCode.BadRequest, "Invalid '$name': expected $expected")

private suspend fun ApplicationCall.respondJson(body: JSONObject, status: HttpStatusCode? = null) =
    respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondJson(body: JSONArray) =
    respondText(body.toString(), ContentType.Application.Json)

/** The request body as JSON, or null when it is not. */
private suspend fun ApplicationCall.jsonBody(): JSONObject? = runCatching { JSONObject(receiveText()) }.getOrNull()

/** The path parameter [name], trimmed; answers 400 with [missing] and returns null when it is blank. */
private suspend fun ApplicationCall.pathParam(name: String, missing: String): String? {
    val value = parameters[name]?.trim()
    if (value.isNullOrBlank()) {
        fail(HttpStatusCode.BadRequest, missing)
        return null
    }
    return value
}

/** Runs [parse]; a [BadParam] becomes a 400 answer and a null result. */
private suspend fun <T : Any> ApplicationCall.validated(parse: () -> T): T? = try {
    parse()
} catch (e: BadParam) {
    badParam(e.name, e.expected)
    null
}

/** Category lists exist for expenses and income only; a missing value means expenses. */
private fun categoryType(value: String?): TransactionType? =
    if (value.isNullOrBlank()) TransactionType.EXPENSE
    else parseType(value)?.takeIf { it != TransactionType.TRANSFER }

/** Matches [name] to a known category ignoring case, so agents cannot create stray duplicates by typo. */
private fun resolveCategory(name: String, known: Collection<String>): String? =
    known.firstOrNull { it.equals(name.trim(), ignoreCase = true) }

private fun SpendingState.Ready.find(key: String): UnifiedTransaction? =
    transactions.firstOrNull { it.key == key || it.canonicalKey == key }

/** The outcome of checking a requested change to one payment before anything is written. */
private sealed interface UpdateCheck {
    data class Valid(val newType: TransactionType?, val category: String?) : UpdateCheck
    data class Invalid(val message: String) : UpdateCheck
}

class ApiServerEngine(
    private val spendingRepository: SpendingRepository,
    private val analysisStore: AnalysisStore,
    private val port: Int = 8080,
    private val onTriggerSync: (Provider?) -> Unit = {},
) {
    private companion object {
        const val MAX_BATCH = 200
    }

    private var engine: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return

        // Loopback only: the Cloudflare tunnel connects from this device, so never expose the port on the LAN.
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                documentationRoutes()
                transactionRoutes()
                summaryRoutes()
                categoryRoutes()
                knowledgeRoutes()
                syncRoutes()
            }
        }

        server.start(wait = false)
        engine = server
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
    }

    private fun knownCategories(type: TransactionType): Set<String> =
        analysisStore.snapshot.value.categoriesFor(type).toSet()

    private fun unknownCategoryMessage(name: String, type: TransactionType) =
        "Unknown ${type.name.lowercase()} category '$name'. List valid ones with GET /api/v1/categories?type=${type.name.lowercase()} or create it with POST /api/v1/categories."

    private suspend fun awaitReadyTransactions(): SpendingState.Ready? {
        val cached = spendingRepository.lastReady.value
        if (cached != null) return cached
        return withTimeoutOrNull(5000L) {
            spendingRepository.state.filterIsInstance<SpendingState.Ready>().first()
        }
    }

    private suspend fun ApplicationCall.ready(): SpendingState.Ready? =
        awaitReadyTransactions() ?: run {
            fail(HttpStatusCode.ServiceUnavailable, "Transactions are still loading. Retry in a few seconds.")
            null
        }

    /** The root redirect, the interactive Scalar docs, the OpenAPI schema and the health check. */
    private fun Route.documentationRoutes() {
        get("/") { call.respondRedirect("/docs", permanent = false) }
        get("/docs") { call.respondText(ScalarDocs.HTML, ContentType.Text.Html) }
        get("/api/v1/openapi.json") { call.respondText(OpenApiGenerator.generateJson(), ContentType.Application.Json) }
        get("/health") {
            call.respondJson(
                JSONObject().put("status", "ok").put("service", "Artha Local API").put("timestamp", Instant.now().toString()),
            )
        }
    }

    private fun Route.transactionRoutes() {
        get("/api/v1/transactions") { call.listTransactions() }
        get("/api/v1/transactions/{key}") { call.getTransaction() }
        patch("/api/v1/transactions/{key}") { call.updateTransaction() }
        post("/api/v1/transactions/batch") { call.updateTransactions() }
    }

    private suspend fun ApplicationCall.listTransactions() {
        val ready = ready() ?: return
        val snapshot = analysisStore.snapshot.value
        val query = validated { TransactionQuery.parse(request.queryParameters) } ?: return

        val matches = query.select(ready.transactions, snapshot)
        val page = matches.drop(query.offset).take(query.limit)
        respondJson(
            JSONObject()
                .put("total", matches.size)
                .put("limit", query.limit)
                .put("offset", query.offset)
                .put("hasMore", query.offset + page.size < matches.size)
                .put("items", JSONArray().apply { page.forEach { put(TransactionSummaryDto.fromDomain(it, snapshot).toJson()) } }),
        )
    }

    private suspend fun ApplicationCall.getTransaction() {
        val key = pathParam("key", "Missing transaction key") ?: return
        val ready = ready() ?: return
        val tx = ready.find(key) ?: return fail(HttpStatusCode.NotFound, "Transaction not found: $key")

        val includeRaw = request.queryParameters["include"].equals("raw", ignoreCase = true)
        respondJson(TransactionDetailDto.fromDomain(tx, analysisStore.snapshot.value).toJson(includeRaw))
    }

    private suspend fun ApplicationCall.updateTransaction() {
        val key = pathParam("key", "Missing transaction key") ?: return
        val body = jsonBody() ?: return fail(HttpStatusCode.BadRequest, "Invalid JSON request body")
        val req = UpdateTransactionRequest.fromJson(body)
        val ready = ready() ?: return
        val tx = ready.find(key) ?: return fail(HttpStatusCode.NotFound, "Transaction not found: $key")

        val change = when (val check = checkUpdate(tx, req.category, req.type, req.userNote)) {
            is UpdateCheck.Invalid -> return fail(HttpStatusCode.BadRequest, check.message)
            is UpdateCheck.Valid -> check
        }
        applyUpdate(tx, change, req.userNote, req.reason)
        respondJson(TransactionDetailDto.fromDomain(tx, analysisStore.snapshot.value).toJson())
    }

    private suspend fun ApplicationCall.updateTransactions() {
        val body = jsonBody() ?: return fail(HttpStatusCode.BadRequest, "Invalid JSON request body")
        val req = BatchUpdateRequest.fromJson(body)
        if (req.updates.isEmpty()) {
            return fail(HttpStatusCode.BadRequest, "'updates' must contain at least one item with a 'key'")
        }
        if (req.updates.size > MAX_BATCH) {
            return fail(HttpStatusCode.BadRequest, "At most $MAX_BATCH updates per request")
        }
        val ready = ready() ?: return
        val byKey = ready.transactions.associateBy { it.key }

        var failure = 0
        val updatedKeys = mutableListOf<String>()
        val errors = mutableMapOf<String, String>()
        for (item in req.updates) {
            val tx = byKey[item.key] ?: ready.transactions.firstOrNull { it.canonicalKey == item.key }
            if (tx == null) {
                failure++
                errors[item.key] = "Transaction not found"
                continue
            }
            when (val check = checkUpdate(tx, item.category, item.type, item.userNote)) {
                is UpdateCheck.Invalid -> {
                    failure++
                    errors[item.key] = check.message
                }
                is UpdateCheck.Valid -> try {
                    applyUpdate(tx, check, item.userNote, item.reason)
                    updatedKeys.add(tx.key)
                } catch (e: Exception) {
                    failure++
                    errors[item.key] = e.message ?: "Failed to update"
                }
            }
        }

        respondJson(
            BatchUpdateResponse(
                totalReceived = req.updates.size,
                successCount = updatedKeys.size,
                failureCount = failure,
                updatedKeys = updatedKeys,
                errors = errors,
            ).toJson(),
        )
    }

    /** Validates a requested type, category and note change for [tx]; a category is matched to a known one by name. */
    private fun checkUpdate(tx: UnifiedTransaction, category: String?, type: String?, userNote: String?): UpdateCheck {
        if (category == null && userNote == null && type == null) {
            return UpdateCheck.Invalid("Provide 'category', 'type' and/or 'userNote'")
        }
        val newType = type?.let { parseType(it) ?: return UpdateCheck.Invalid("'type' must be expense, income or transfer") }
        if (category == null) return UpdateCheck.Valid(newType, null)

        val effectiveType = newType ?: tx.type
        if (effectiveType == TransactionType.TRANSFER) return UpdateCheck.Invalid("Transfers have no category")
        val resolved = resolveCategory(category, knownCategories(effectiveType))
            ?: return UpdateCheck.Invalid(unknownCategoryMessage(category, effectiveType))
        return UpdateCheck.Valid(newType, resolved)
    }

    private fun applyUpdate(tx: UnifiedTransaction, change: UpdateCheck.Valid, userNote: String?, reason: String?) {
        change.newType?.let { analysisStore.setTypeOverride(tx.key, it) }
        userNote?.let { analysisStore.setUserNote(tx.key, it) }
        change.category?.let { analysisStore.setClassification(tx.key, it, reason.orEmpty(), manual = true) }
    }

    private fun Route.summaryRoutes() {
        get("/api/v1/summary") { call.monthSummary() }
    }

    private suspend fun ApplicationCall.monthSummary() {
        val ready = ready() ?: return
        val month = request.queryParameters["month"]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: YearMonth.now(AccountingTime.zone)
        val type = request.queryParameters["type"]?.takeIf { it.isNotBlank() }
            ?.let { parseType(it)?.takeIf { t -> t != TransactionType.TRANSFER } ?: return badParam("type", "expense or income") }
            ?: TransactionType.EXPENSE

        val analysis = BudgetAnalyzer.analyzeMonth(month, ready.transactions, type)
        respondJson(MonthSummaryDto.fromDomain(analysis).toJson())
    }

    private fun Route.categoryRoutes() {
        get("/api/v1/categories") { call.listCategories() }
        post("/api/v1/categories") { call.addCategory() }
        delete("/api/v1/categories/{name}") { call.removeCategory() }
    }

    private suspend fun ApplicationCall.listCategories() {
        val ready = ready() ?: return
        val snapshot = analysisStore.snapshot.value
        val type = categoryType(request.queryParameters["type"]) ?: return badParam("type", "expense or income")

        val counts = ready.transactions.mapNotNull { tx -> tx.category?.takeIf { tx.type == type } }.groupingBy { it }.eachCount()
        val listed = snapshot.categoriesFor(type)
        val listedSet = listed.toSet()
        val names = (listed + counts.keys).distinct().sorted()
        respondJson(
            JSONArray().apply {
                for (name in names) {
                    put(CategoryDto(name, isCustom = name in listedSet, icon = snapshot.categoryIcons[name], count = counts[name] ?: 0).toJson())
                }
            },
        )
    }

    private suspend fun ApplicationCall.addCategory() {
        val body = jsonBody()
        val name = body?.optString("name")?.trim()
        if (name.isNullOrBlank()) return fail(HttpStatusCode.BadRequest, "Category name is required")

        val type = categoryType(body.optString("type").takeIf { it.isNotBlank() })
            ?: return fail(HttpStatusCode.BadRequest, "'type' must be expense or income")
        try {
            analysisStore.addCustomCategory(name, type)
            respondJson(JSONObject().put("name", name).put("created", true), HttpStatusCode.Created)
        } catch (e: Exception) {
            fail(HttpStatusCode.Conflict, e.message ?: "Could not add category")
        }
    }

    private suspend fun ApplicationCall.removeCategory() {
        val name = pathParam("name", "Category name is required") ?: return
        val type = categoryType(request.queryParameters["type"]) ?: return badParam("type", "expense or income")
        analysisStore.removeCustomCategory(name, type)
        respondJson(JSONObject().put("name", name).put("deleted", true))
    }

    private fun Route.knowledgeRoutes() {
        get("/api/v1/knowledge") {
            call.respondJson(JSONObject().put("knowledge", analysisStore.snapshot.value.knowledge))
        }
        put("/api/v1/knowledge") {
            val knowledge = call.jsonBody()?.takeIf { it.has("knowledge") }?.optString("knowledge")
                ?: return@put call.fail(HttpStatusCode.BadRequest, "knowledge property is required")
            analysisStore.setKnowledge(knowledge)
            call.respondJson(JSONObject().put("knowledge", knowledge).put("updated", true))
        }
    }

    private fun Route.syncRoutes() {
        post("/api/v1/actions/sync") {
            val req = SyncRequest.fromJson(call.jsonBody())
            onTriggerSync(req.provider)
            call.respondJson(JSONObject().put("status", "sync_triggered").put("provider", req.provider?.name ?: "ALL"))
        }
    }
}
