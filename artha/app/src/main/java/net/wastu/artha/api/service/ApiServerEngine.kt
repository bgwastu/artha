package net.wastu.artha.api.service

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
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
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.data.analysis.AnalysisStore
import net.wastu.artha.data.analysis.BudgetAnalyzer
import net.wastu.artha.data.analysis.CategoryConstants
import net.wastu.artha.data.analysis.MerchantMemory
import net.wastu.artha.data.analysis.SpendingRepository
import net.wastu.artha.data.analysis.SpendingState
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth

private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) {
    val code = status.description.lowercase().replace(' ', '_')
    val body = JSONObject().put("error", JSONObject().put("code", code).put("message", message))
    respondText(body.toString(), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.badParam(name: String, expected: String) =
    fail(HttpStatusCode.BadRequest, "Invalid '$name': expected $expected")

/** Matches [name] to a known category ignoring case, so agents cannot create stray duplicates by typo. */
private fun resolveCategory(name: String, known: Collection<String>): String? =
    known.firstOrNull { it.equals(name.trim(), ignoreCase = true) }

class ApiServerEngine(
    private val spendingRepository: SpendingRepository,
    private val analysisStore: AnalysisStore,
    private val accessToken: String,
    private val port: Int = 8080,
    private val onTriggerSync: (Provider?) -> Unit = {},
) {
    private companion object {
        const val KEY_COOKIE = "artha_key"
        const val MAX_BATCH = 200
    }

    private fun cookieKey(header: String?): String? =
        header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$KEY_COOKIE=") }?.substringAfter('=')

    private fun knownCategories(ready: SpendingState.Ready): Set<String> =
        analysisStore.snapshot.value.customCategories.toSet() +
            ready.transactions.mapNotNull { it.category } +
            setOf(CategoryConstants.UNCATEGORIZED, CategoryConstants.INVESTMENTS_TRANSFERS)

    private fun unknownCategoryMessage(name: String) =
        "Unknown category '$name'. List valid ones with GET /api/v1/categories or create it with POST /api/v1/categories."

    private var engine: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = engine != null

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

    fun start() {
        if (engine != null) return

        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader("X-Api-Key")
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Patch)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
            }

            // Every request needs the access token (Bearer, X-Api-Key, ?key= or the cookie set from ?key=).
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.httpMethod == HttpMethod.Options) return@intercept
                val request = call.request
                val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
                val queryKey = request.queryParameters["key"]
                val provided = bearer ?: request.headers["X-Api-Key"] ?: queryKey ?: cookieKey(request.headers[HttpHeaders.Cookie])
                if (provided == null || !MessageDigest.isEqual(provided.toByteArray(), accessToken.toByteArray())) {
                    call.fail(HttpStatusCode.Unauthorized, "Missing or invalid access key")
                    finish()
                } else if (queryKey != null) {
                    call.response.header(HttpHeaders.SetCookie, "$KEY_COOKIE=$accessToken; Path=/; HttpOnly; SameSite=Lax")
                }
            }

            routing {
                // Root redirects to interactive Scalar docs
                get("/") {
                    call.respondRedirect("/docs", permanent = false)
                }

                // Interactive documentation (Scalar UI)
                get("/docs") {
                    call.respondText(ScalarDocs.HTML, ContentType.Text.Html)
                }

                // OpenAPI 3.1 schema
                get("/api/v1/openapi.json") {
                    val spec = OpenApiGenerator.generateJson()
                    call.respondText(spec, ContentType.Application.Json)
                }

                // Health check
                get("/health") {
                    val json = JSONObject().apply {
                        put("status", "ok")
                        put("service", "Artha Local API")
                        put("timestamp", Instant.now().toString())
                    }
                    call.respondText(json.toString(), ContentType.Application.Json)
                }

                // GET /api/v1/transactions
                get("/api/v1/transactions") {
                    val ready = call.ready() ?: return@get
                    val snapshot = analysisStore.snapshot.value
                    val params = call.request.queryParameters

                    val limit = (params["limit"]?.let { it.toIntOrNull() ?: return@get call.badParam("limit", "an integer") } ?: 50).coerceIn(1, 200)
                    val offset = (params["offset"]?.let { it.toIntOrNull() ?: return@get call.badParam("offset", "an integer") } ?: 0).coerceAtLeast(0)
                    fun text(name: String) = params[name]?.takeIf { it.isNotBlank() }
                    val month = text("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() ?: return@get call.badParam("month", "YYYY-MM") }
                    val from = text("from")?.let { runCatching { Instant.parse(it) }.getOrNull() ?: return@get call.badParam("from", "an ISO-8601 timestamp such as 2026-04-01T00:00:00Z") }
                    val to = text("to")?.let { runCatching { Instant.parse(it) }.getOrNull() ?: return@get call.badParam("to", "an ISO-8601 timestamp such as 2026-05-01T00:00:00Z") }
                    val provider = text("provider")?.let { p ->
                        Provider.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
                            ?: return@get call.badParam("provider", Provider.entries.joinToString(", ") { it.name })
                    }
                    val direction = text("direction")?.let { d ->
                        Direction.entries.firstOrNull { it.name.equals(d, ignoreCase = true) }
                            ?: return@get call.badParam("direction", Direction.entries.joinToString(" or ") { it.name })
                    }
                    val category = text("category")
                    val search = text("search")?.lowercase()
                    val unreviewedOnly = params["unreviewedOnly"]?.let { it.toBooleanStrictOrNull() ?: return@get call.badParam("unreviewedOnly", "true or false") } == true
                    val minAmountMinor = text("minAmountMinor")?.let { it.toLongOrNull() ?: return@get call.badParam("minAmountMinor", "an integer") }
                    val maxAmountMinor = text("maxAmountMinor")?.let { it.toLongOrNull() ?: return@get call.badParam("maxAmountMinor", "an integer") }
                    val sort = text("sort")?.also {
                        if (it !in setOf("date_desc", "date_asc", "amount_desc", "amount_asc")) {
                            return@get call.badParam("sort", "date_desc, date_asc, amount_desc or amount_asc")
                        }
                    }

                    var stream = ready.transactions.asSequence()
                    if (month != null) {
                        val zone = AccountingTime.zone
                        stream = stream.filter { YearMonth.from(it.occurredAt.atZone(zone)) == month }
                    }
                    if (from != null) stream = stream.filter { it.occurredAt >= from }
                    if (to != null) stream = stream.filter { it.occurredAt < to }
                    if (provider != null) stream = stream.filter { it.provider == provider }
                    if (direction != null) stream = stream.filter { it.direction == direction }
                    if (category != null) stream = stream.filter { it.category.equals(category, ignoreCase = true) }
                    if (unreviewedOnly) {
                        stream = stream.filter {
                            it.category == null ||
                                it.category == CategoryConstants.UNCATEGORIZED ||
                                (!it.classificationIsManual && it.key !in snapshot.manualCategories)
                        }
                    }
                    if (search != null) {
                        val q = search.trim()
                        stream = stream.filter {
                            it.description.lowercase().contains(q) ||
                                (it.userNote?.lowercase()?.contains(q) == true) ||
                                (MerchantMemory.displayMerchant(it)?.lowercase()?.contains(q) == true)
                        }
                    }
                    if (minAmountMinor != null) stream = stream.filter { it.money.amountMinor >= minAmountMinor }
                    if (maxAmountMinor != null) stream = stream.filter { it.money.amountMinor <= maxAmountMinor }
                    stream = when (sort) {
                        "date_desc" -> stream.sortedByDescending { it.occurredAt }
                        "date_asc" -> stream.sortedBy { it.occurredAt }
                        "amount_desc" -> stream.sortedByDescending { it.money.amountMinor }
                        "amount_asc" -> stream.sortedBy { it.money.amountMinor }
                        else -> stream
                    }

                    val allFiltered = stream.toList()
                    val total = allFiltered.size
                    val pageItems = allFiltered.drop(offset).take(limit)

                    val respJson = JSONObject().apply {
                        put("total", total)
                        put("limit", limit)
                        put("offset", offset)
                        put("hasMore", offset + pageItems.size < total)
                        val itemsArr = JSONArray()
                        for (tx in pageItems) {
                            itemsArr.put(TransactionSummaryDto.fromDomain(tx, snapshot).toJson())
                        }
                        put("items", itemsArr)
                    }

                    call.respondText(respJson.toString(), ContentType.Application.Json)
                }

                // GET /api/v1/transactions/{key}
                get("/api/v1/transactions/{key}") {
                    val key = call.parameters["key"]?.trim()
                    if (key.isNullOrBlank()) {
                        call.fail(HttpStatusCode.BadRequest, "Missing transaction key")
                        return@get
                    }

                    val ready = call.ready() ?: return@get
                    val snapshot = analysisStore.snapshot.value
                    val tx = ready.transactions.firstOrNull { it.key == key || it.canonicalKey == key }
                    if (tx == null) {
                        call.fail(HttpStatusCode.NotFound, "Transaction not found: $key")
                        return@get
                    }

                    val includeRaw = call.request.queryParameters["include"].equals("raw", ignoreCase = true)
                    val detail = TransactionDetailDto.fromDomain(tx, snapshot)
                    call.respondText(detail.toJson(includeRaw).toString(), ContentType.Application.Json)
                }

                // PATCH /api/v1/transactions/{key}
                patch("/api/v1/transactions/{key}") {
                    val key = call.parameters["key"]?.trim()
                    if (key.isNullOrBlank()) {
                        call.fail(HttpStatusCode.BadRequest, "Missing transaction key")
                        return@patch
                    }

                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.fail(HttpStatusCode.BadRequest, "Invalid JSON request body")
                        return@patch
                    }

                    val req = UpdateTransactionRequest.fromJson(body)
                    val ready = call.ready() ?: return@patch
                    val tx = ready.transactions.firstOrNull { it.key == key || it.canonicalKey == key }
                    if (tx == null) {
                        call.fail(HttpStatusCode.NotFound, "Transaction not found: $key")
                        return@patch
                    }

                    if (req.category == null && req.userNote == null) {
                        return@patch call.fail(HttpStatusCode.BadRequest, "Provide 'category' and/or 'userNote'")
                    }
                    val category = req.category?.let { name ->
                        resolveCategory(name, knownCategories(ready))
                            ?: return@patch call.fail(HttpStatusCode.BadRequest, unknownCategoryMessage(name))
                    }

                    req.userNote?.let { note -> analysisStore.setUserNote(tx.key, note) }
                    category?.let { analysisStore.setClassification(tx.key, it, req.reason.orEmpty(), manual = true) }

                    val snapshot = analysisStore.snapshot.value
                    val updatedDetail = TransactionDetailDto.fromDomain(tx, snapshot)
                    call.respondText(updatedDetail.toJson().toString(), ContentType.Application.Json)
                }

                // POST /api/v1/transactions/batch
                post("/api/v1/transactions/batch") {
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.fail(HttpStatusCode.BadRequest, "Invalid JSON request body")
                        return@post
                    }

                    val req = BatchUpdateRequest.fromJson(body)
                    if (req.updates.isEmpty()) {
                        return@post call.fail(HttpStatusCode.BadRequest, "'updates' must contain at least one item with a 'key'")
                    }
                    if (req.updates.size > MAX_BATCH) {
                        return@post call.fail(HttpStatusCode.BadRequest, "At most $MAX_BATCH updates per request")
                    }
                    val ready = call.ready() ?: return@post
                    val txMap = ready.transactions.associateBy { it.key }
                    val known = knownCategories(ready)

                    var success = 0
                    var failure = 0
                    val updatedKeys = mutableListOf<String>()
                    val errors = mutableMapOf<String, String>()

                    for (item in req.updates) {
                        val tx = txMap[item.key] ?: ready.transactions.firstOrNull { it.canonicalKey == item.key }
                        if (tx == null) {
                            failure++
                            errors[item.key] = "Transaction not found"
                            continue
                        }
                        if (item.category == null && item.userNote == null) {
                            failure++
                            errors[item.key] = "Provide 'category' and/or 'userNote'"
                            continue
                        }
                        var category: String? = null
                        if (item.category != null) {
                            category = resolveCategory(item.category, known)
                            if (category == null) {
                                failure++
                                errors[item.key] = unknownCategoryMessage(item.category)
                                continue
                            }
                        }

                        try {
                            item.userNote?.let { note -> analysisStore.setUserNote(tx.key, note) }
                            category?.let { analysisStore.setClassification(tx.key, it, item.reason.orEmpty(), manual = true) }
                            success++
                            updatedKeys.add(tx.key)
                        } catch (e: Exception) {
                            failure++
                            errors[item.key] = e.message ?: "Failed to update"
                        }
                    }

                    val resp = BatchUpdateResponse(
                        totalReceived = req.updates.size,
                        successCount = success,
                        failureCount = failure,
                        updatedKeys = updatedKeys,
                        errors = errors,
                    )
                    call.respondText(resp.toJson().toString(), ContentType.Application.Json)
                }

                // GET /api/v1/summary
                get("/api/v1/summary") {
                    val ready = call.ready() ?: return@get
                    val monthParam = call.request.queryParameters["month"]
                    val month = if (!monthParam.isNullOrBlank()) {
                        runCatching { YearMonth.parse(monthParam) }.getOrNull() ?: YearMonth.now(AccountingTime.zone)
                    } else {
                        YearMonth.now(AccountingTime.zone)
                    }

                    val analysis = BudgetAnalyzer.analyzeMonth(month, ready.transactions)
                    val dto = MonthSummaryDto.fromDomain(analysis)
                    call.respondText(dto.toJson().toString(), ContentType.Application.Json)
                }

                // GET /api/v1/categories
                get("/api/v1/categories") {
                    val ready = call.ready() ?: return@get
                    val snapshot = analysisStore.snapshot.value
                    val counts = ready.transactions.groupingBy { it.category ?: CategoryConstants.UNCATEGORIZED }.eachCount()

                    val customSet = snapshot.customCategories.toSet()
                    val allNames = (snapshot.customCategories + counts.keys).distinct().sorted()

                    val arr = JSONArray()
                    for (name in allNames) {
                        val dto = CategoryDto(
                            name = name,
                            isCustom = name in customSet,
                            icon = snapshot.categoryIcons[name],
                            count = counts[name] ?: 0,
                        )
                        arr.put(dto.toJson())
                    }
                    call.respondText(arr.toString(), ContentType.Application.Json)
                }

                // POST /api/v1/categories
                post("/api/v1/categories") {
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    val name = body?.optString("name")?.trim()
                    if (name.isNullOrBlank()) {
                        call.fail(HttpStatusCode.BadRequest, "Category name is required")
                        return@post
                    }

                    try {
                        analysisStore.addCustomCategory(name)
                        call.respondText(JSONObject().put("name", name).put("created", true).toString(), ContentType.Application.Json, HttpStatusCode.Created)
                    } catch (e: Exception) {
                        call.fail(HttpStatusCode.Conflict, e.message ?: "Could not add category")
                    }
                }

                // DELETE /api/v1/categories/{name}
                delete("/api/v1/categories/{name}") {
                    val name = call.parameters["name"]?.trim()
                    if (name.isNullOrBlank()) {
                        call.fail(HttpStatusCode.BadRequest, "Category name is required")
                        return@delete
                    }

                    analysisStore.removeCustomCategory(name)
                    call.respondText(JSONObject().put("name", name).put("deleted", true).toString(), ContentType.Application.Json)
                }

                // GET /api/v1/knowledge
                get("/api/v1/knowledge") {
                    val snapshot = analysisStore.snapshot.value
                    val json = JSONObject().put("knowledge", snapshot.knowledge)
                    call.respondText(json.toString(), ContentType.Application.Json)
                }

                // PUT /api/v1/knowledge
                put("/api/v1/knowledge") {
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    val knowledge = body?.optString("knowledge")
                    if (knowledge == null) {
                        call.fail(HttpStatusCode.BadRequest, "knowledge property is required")
                        return@put
                    }

                    analysisStore.setKnowledge(knowledge)
                    call.respondText(JSONObject().put("knowledge", knowledge).put("updated", true).toString(), ContentType.Application.Json)
                }

                // POST /api/v1/actions/sync
                post("/api/v1/actions/sync") {
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    val req = SyncRequest.fromJson(body)
                    onTriggerSync(req.provider)
                    val json = JSONObject().apply {
                        put("status", "sync_triggered")
                        put("provider", req.provider?.name ?: "ALL")
                    }
                    call.respondText(json.toString(), ContentType.Application.Json)
                }

            }
        }

        server.start(wait = false)
        engine = server
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
    }
}
