package net.wastu.artha.data.ingestion

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.wastu.artha.core.model.UnifiedTransaction
import org.json.JSONObject

/**
 * Reads Artha's own in-process Gojek capture (`artha_gojek_capture.jsonl`), produced by the
 * [net.wastu.artha.hook.ArthaHookMain] Xposed hook that Artha itself runs inside the Gojek app.
 *
 * The capture can grow to tens of megabytes, so [extract] copies only the matching history
 * lines into Artha's sandbox (see [RootFileReader.copyFilteredToSandbox]) and this reader
 * streams the copy line by line. Each selected line carries a full signed history response
 * in `resp_body`, exactly the shape `GojekProviderParser.paymentPage` supports.
 */
internal class ArthaCaptureReader(private val extract: suspend () -> File?) {

    internal class Result(
        val transactions: List<UnifiedTransaction>,
        /** Human-readable capture notes surfaced through the existing import issues list. */
        val notes: List<String>,
    )

    internal suspend fun read(): Result = withContext(Dispatchers.IO) {
        val checkActive = { coroutineContext.ensureActive() }
        val snapshot = extract()
        if (snapshot == null || !snapshot.exists() || snapshot.length() == 0L) {
            return@withContext Result(emptyList(), listOf("Artha capture missing or empty; open GoPay once in Gojek to seed it"))
        }
        try {
            // Deduplicate by latest capture timestamp per (URL, page identity): background syncs
            // re-fetch page 0 while hook-driven pagination appends deeper pages, so only the
            // freshest response body per page wins. Page identity carries the end-of-window
            // boundary, so a page whose contents shifted (newer transaction inserted) is kept
            // separately and its rows dedupe naturally by providerTransactionId.
            val bestLines = LinkedHashMap<String, Pair<Long, String>>()
            snapshot.useLines { lines ->
                for (raw in lines) {
                    checkActive()
                    val line = raw.trim()
                    if (line.isEmpty() || !line.startsWith("{\"seq\":")) continue
                    val candidate: JSONObject? = try { JSONObject(line) } catch (_: Exception) { null }
                    if (candidate == null) continue
                    val url = candidate.optString("url")
                    val body = candidate.optString("resp_body")
                    if (candidate.optInt("code") != 200) continue
                    if (!url.contains(HISTORY_MARKER) || body.length < 200) continue
                    val ts = candidate.optLong("ts", 0L)
                    val pageKey = url + "|" + pageIdentity(body)
                    val prev = bestLines[pageKey]
                    if (prev == null || prev.first < ts) bestLines[pageKey] = ts to line
                }
            }
            if (bestLines.isEmpty()) {
                return@withContext Result(emptyList(), listOf("Artha capture had no usable GoPay history pages"))
            }
            val notes = LinkedHashSet<String>()
            val rows = ImportRows()
            for ((url, best) in bestLines) {
                checkActive()
                val candidate: JSONObject? = try { JSONObject(best.second) } catch (_: Exception) { null }
                if (candidate == null) continue
                val root: JSONObject? = try { JSONObject(candidate.optString("resp_body")) } catch (_: Exception) { null }
                if (root == null) continue
                val page: GojekProviderParser.PaymentPage? = try {
                    GojekProviderParser.paymentPage(root, checkActive)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (t: Throwable) {
                    notes += when (t) {
                        is ProviderReadException -> t.message ?: "Capture page for $url was unrecognized"
                        else -> "One Artha capture page was unrecognized"
                    }
                    null
                }
                if (page == null) continue
                if (page.malformedRows > 0) notes += "Artha capture contains ${page.malformedRows} unrecognized rows"
                for (item in page.items) {
                    checkActive()
                    val payment: UnifiedTransaction? = try { GojekProviderParser.payment(item) }
                        catch (_: Exception) { notes += "One Artha capture row was not recognized"; null }
                    if (payment != null) rows.transactions += payment
                }
            }
            val parsed = try { rows.finish().sortedByDescending { it.occurredAt } }
            catch (e: PartialImportException) { e.transactions }
            return@withContext Result(parsed, notes.toList() + listOf(
                "Artha capture: ${bestLines.size} history pages, ${snapshot.length()} selection bytes, ${parsed.size} rows"))
        } finally {
            snapshot.delete()
        }
    }

    /** Stable page-window identity: the next-page boundary this window ends at. */
    private fun pageIdentity(body: String): String {
        val root: JSONObject? = try { JSONObject(body) } catch (_: Exception) { null }
        if (root == null) return "unknown"
        val data = root.optJSONObject("data") ?: return "nodata"
        val next = data.optJSONObject("next")
        if (next == null) return "tail"
        return listOfNotNull(
            next.opt("page")?.toString(),
            next.opt("offset_for_oh")?.toString(),
            next.opt("upper_bound")?.toString(),
        ).joinToString(":").ifBlank { "unknown-page" }
    }

    internal companion object {
        val HISTORY_MARKER = "customer.gopayapi.com/v3/users/order-history"
    }
}
