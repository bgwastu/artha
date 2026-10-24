package net.wastu.artha.api.service

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
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
import net.wastu.artha.api.spec.AgentTools
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
import java.time.Instant
import java.time.YearMonth

class ApiServerEngine(
    private val spendingRepository: SpendingRepository,
    private val analysisStore: AnalysisStore,
    private val port: Int = 8080,
    private val onTriggerSync: (Provider?) -> Unit = {},
) {
    private var engine: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = engine != null

    private suspend fun awaitReadyTransactions(): SpendingState.Ready {
        val cached = spendingRepository.lastReady.value
        if (cached != null) return cached
        return withTimeoutOrNull(5000L) {
            spendingRepository.state.filterIsInstance<SpendingState.Ready>().first()
        } ?: SpendingState.Ready(emptyList(), emptyMap())
    }

    fun start() {
        if (engine != null) return

        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Patch)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
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

                // AI Agent tool definitions
                get("/api/v1/agent/tools") {
                    val tools = AgentTools.generateJson()
                    call.respondText(tools, ContentType.Application.Json)
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
                    val ready = awaitReadyTransactions()
                    val snapshot = analysisStore.snapshot.value
                    val params = call.request.queryParameters

                    val limit = (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    val monthParam = params["month"]?.takeIf { it.isNotBlank() }
                    val fromParam = params["from"]?.takeIf { it.isNotBlank() }
                    val toParam = params["to"]?.takeIf { it.isNotBlank() }
                    val providerParam = params["provider"]?.takeIf { it.isNotBlank() }
                    val directionParam = params["direction"]?.takeIf { it.isNotBlank() }
                    val categoryParam = params["category"]?.takeIf { it.isNotBlank() }
                    val searchParam = params["search"]?.takeIf { it.isNotBlank() }
                    val unreviewedOnly = params["unreviewedOnly"]?.toBooleanStrictOrNull() == true
                    val minAmountMinor = params["minAmountMinor"]?.toLongOrNull()
                    val maxAmountMinor = params["maxAmountMinor"]?.toLongOrNull()

                    var stream = ready.transactions.asSequence()

                    monthParam?.let { m ->
                        val ym = runCatching { YearMonth.parse(m) }.getOrNull()
                        if (ym != null) {
                            val zone = AccountingTime.zone
                            stream = stream.filter { YearMonth.from(it.occurredAt.atZone(zone)) == ym }
                        }
                    }

                    fromParam?.let { f ->
                        val inst = runCatching { Instant.parse(f) }.getOrNull()
                        if (inst != null) stream = stream.filter { it.occurredAt >= inst }
                    }

                    toParam?.let { t ->
                        val inst = runCatching { Instant.parse(t) }.getOrNull()
                        if (inst != null) stream = stream.filter { it.occurredAt < inst }
                    }

                    providerParam?.let { p ->
                        val prov = runCatching { Provider.valueOf(p.uppercase()) }.getOrNull()
                        if (prov != null) stream = stream.filter { it.provider == prov }
                    }

                    directionParam?.let { d ->
                        val dir = runCatching { Direction.valueOf(d.uppercase()) }.getOrNull()
                        if (dir != null) stream = stream.filter { it.direction == dir }
                    }

                    categoryParam?.let { c ->
                        stream = stream.filter { it.category.equals(c, ignoreCase = true) }
                    }

                    if (unreviewedOnly) {
                        stream = stream.filter {
                            it.category == null ||
                                it.category == CategoryConstants.UNCATEGORIZED ||
                                (!it.classificationIsManual && it.key !in snapshot.manualCategories)
                        }
                    }

                    searchParam?.let { q ->
                        val lower = q.trim().lowercase()
                        stream = stream.filter {
                            it.description.lowercase().contains(lower) ||
                                (it.userNote?.lowercase()?.contains(lower) == true) ||
                                (MerchantMemory.displayMerchant(it)?.lowercase()?.contains(lower) == true)
                        }
                    }

                    minAmountMinor?.let { min ->
                        stream = stream.filter { it.money.amountMinor >= min }
                    }

                    maxAmountMinor?.let { max ->
                        stream = stream.filter { it.money.amountMinor <= max }
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
                        call.respondText(JSONObject().put("error", "Missing transaction key").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@get
                    }

                    val ready = awaitReadyTransactions()
                    val snapshot = analysisStore.snapshot.value
                    val tx = ready.transactions.firstOrNull { it.key == key || it.canonicalKey == key }
                    if (tx == null) {
                        call.respondText(JSONObject().put("error", "Transaction not found: $key").toString(), ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@get
                    }

                    val detail = TransactionDetailDto.fromDomain(tx, snapshot)
                    call.respondText(detail.toJson().toString(), ContentType.Application.Json)
                }

                // PATCH /api/v1/transactions/{key}
                patch("/api/v1/transactions/{key}") {
                    val key = call.parameters["key"]?.trim()
                    if (key.isNullOrBlank()) {
                        call.respondText(JSONObject().put("error", "Missing transaction key").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@patch
                    }

                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respondText(JSONObject().put("error", "Invalid JSON request body").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@patch
                    }

                    val req = UpdateTransactionRequest.fromJson(body)
                    val ready = awaitReadyTransactions()
                    val tx = ready.transactions.firstOrNull { it.key == key || it.canonicalKey == key }
                    if (tx == null) {
                        call.respondText(JSONObject().put("error", "Transaction not found: $key").toString(), ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@patch
                    }

                    req.userNote?.let { note ->
                        analysisStore.setUserNote(tx.key, note)
                    }
                    req.category?.let { cat ->
                        analysisStore.setClassification(tx.key, cat, req.reason.orEmpty(), manual = true)
                    }

                    val snapshot = analysisStore.snapshot.value
                    val updatedDetail = TransactionDetailDto.fromDomain(tx, snapshot)
                    call.respondText(updatedDetail.toJson().toString(), ContentType.Application.Json)
                }

                // POST /api/v1/transactions/batch
                post("/api/v1/transactions/batch") {
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respondText(JSONObject().put("error", "Invalid JSON request body").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }

                    val req = BatchUpdateRequest.fromJson(body)
                    val ready = awaitReadyTransactions()
                    val txMap = ready.transactions.associateBy { it.key }

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

                        try {
                            item.userNote?.let { note ->
                                analysisStore.setUserNote(tx.key, note)
                            }
                            item.category?.let { cat ->
                                analysisStore.setClassification(tx.key, cat, item.reason.orEmpty(), manual = true)
                            }
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
                    val ready = awaitReadyTransactions()
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
                    val ready = awaitReadyTransactions()
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
                        call.respondText(JSONObject().put("error", "Category name is required").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }

                    try {
                        analysisStore.addCustomCategory(name)
                        call.respondText(JSONObject().put("name", name).put("created", true).toString(), ContentType.Application.Json, HttpStatusCode.Created)
                    } catch (e: Exception) {
                        call.respondText(JSONObject().put("error", e.message ?: "Could not add category").toString(), ContentType.Application.Json, HttpStatusCode.Conflict)
                    }
                }

                // DELETE /api/v1/categories/{name}
                delete("/api/v1/categories/{name}") {
                    val name = call.parameters["name"]?.trim()
                    if (name.isNullOrBlank()) {
                        call.respondText(JSONObject().put("error", "Category name is required").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
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
                        call.respondText(JSONObject().put("error", "knowledge property is required").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
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
