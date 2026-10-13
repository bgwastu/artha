package net.wastu.artha.data.reconciliation

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryConstants
import java.time.Duration
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object LedgerReconciler {

    private data class AmountKey(val currency: String, val amount: Long, val provider: Provider, val direction: Direction)
    private data class Evidence(val tx: UnifiedTransaction, val payload: JSONObject) {
        val code = payload.optString("code")
        val reference = payload.optString("originalChargeId").ifBlank { payload.optString("paymentReference") }
        val order = payload.optString("orderNumber").ifBlank { payload.optString("order_id") }
        val origin = payload.optString("origin")
        val merchant = payload.optString("counterpart").ifBlank { tx.description }.trim().lowercase(Locale.ROOT)
        val internal = code in setOf("SAD01", "SAD02", "SAC01", "SAC02", "TDD02", "TDC02", "TDC03", "TDC08", "TDC12") ||
            payload.optJSONObject("fields")?.optString("f22") == "INTERNAL"
        val refund = code.endsWith("-R", true) || code == "GPC02" ||
            tx.description.contains("refund", true) || tx.description.contains("reversal", true)
        val confirmed = tx.provider == Provider.JAGO || origin == "gopay-order-history" || origin == "grab-wallet-api" || origin == "grab-rides-detail-api"
        val funding = payload.optBoolean("isWalletFunding") || payload.optBoolean("isPocketTransfer") || internal ||
            (tx.provider == Provider.JAGO && code in setOf("OTD10", "TFD70") &&
                listOf("gopay", "ovo", "shopeepay", "top up", "topup").any { it in merchant }) ||
            origin == "grab-wallet-api" && listOf("top up", "topup").any { it in tx.description.lowercase(Locale.ROOT) }
    }
    private fun evidence(t: UnifiedTransaction) = Evidence(t, runCatching { JSONObject(t.rawPayload) }.getOrDefault(JSONObject()))
    private fun amount(e: Evidence) = AmountKey(e.tx.money.currencyCode, e.tx.money.amountMinor, e.tx.provider, e.tx.direction)
    private fun close(a: UnifiedTransaction, b: UnifiedTransaction, seconds: Long) = Duration.between(a.occurredAt, b.occurredAt).abs() <= Duration.ofSeconds(seconds)
    private fun compatibleAccount(a: UnifiedTransaction, b: UnifiedTransaction): Boolean =
        (a.pocket == null || b.pocket == null || a.pocket.providerPocketId == b.pocket.providerPocketId) &&
        (a.cardLast4 == null || b.cardLast4 == null || a.cardLast4 == b.cardLast4)

    private fun within(sorted: List<Evidence>, time: java.time.Instant, seconds: Long): List<Evidence> {
        fun lowerBound(bound: java.time.Instant): Int {
            var low = 0; var high = sorted.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (sorted[middle].tx.occurredAt < bound) low = middle + 1 else high = middle
            }
            return low
        }
        return sorted.subList(lowerBound(time.minusSeconds(seconds)), lowerBound(time.plusSeconds(seconds).plusNanos(1)))
    }

    /** Only unambiguous reciprocal candidates are fused; source rows remain immutable. */
    private fun pair(
        primary: List<Evidence>, secondary: List<Evidence>,
        windowSeconds: Long,
        candidate: (Evidence, Evidence) -> Boolean,
        merge: (UnifiedTransaction, UnifiedTransaction) -> UnifiedTransaction,
    ): List<UnifiedTransaction> {
        val index = secondary.groupBy(::amount).mapValues { (_, values) -> values.sortedBy { it.tx.occurredAt } }
        val choices = primary.associate { p ->
            p.tx.key to index[AmountKey(p.tx.money.currencyCode, p.tx.money.amountMinor, p.tx.provider,
                if (p.tx.direction == Direction.CREDIT) Direction.DEBIT else Direction.CREDIT)]
                .orEmpty().let { within(it, p.tx.occurredAt, windowSeconds) }.filter { candidate(p, it) }.map { it.tx }
        }
        val reverseCount = choices.values.flatten().groupingBy { it.key }.eachCount()
        val used = mutableSetOf<String>()
        val result = primary.map { p ->
            val match = choices[p.tx.key]?.singleOrNull()?.takeIf { reverseCount[it.key] == 1 }
            if (match == null) p.tx else { used += match.key; merge(p.tx, match) }
        }
        return result + secondary.map { it.tx }.filter { it.key !in used }
    }

    fun reconcileLedger(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val initial = transactions.distinctBy { it.key }.map { t ->
            val e = evidence(t)
            t.copy(isConfirmed = e.confirmed, rawPayload = if (e.funding) e.payload.put("isWalletFunding", true).toString() else t.rawPayload)
        }
        val reversed = reconcilePartialRefunds(reconcileReversals(reconcilePocketTransfers(initial)))
        val deduped = reconcileOrderPayments(reversed)
        val bank = deduped.filter { it.provider == Provider.JAGO }
        val bankIdentities = bank.map { it.providerTransactionId.substringAfter(":") to it.money }.toSet()
        val orders = deduped.filter { it.provider != Provider.JAGO }.filterNot { o ->
            evidence(o).origin == "jago-linked-local-hive" && (o.providerTransactionId.substringAfter(":") to o.money) in bankIdentities
        }
        val bankIndex = bank.map(::evidence).groupBy { it.tx.money.currencyCode to it.tx.money.amountMinor }
        val choices = orders.associate { o -> o.key to bankIndex[o.money.currencyCode to o.money.amountMinor].orEmpty().filter { e ->
            val b = e.tx
            o.direction == b.direction && close(o, b, 7 * 86400L) && isMatchingProvider(b, o.provider) &&
                compatibleAccount(o, b) && !e.funding
        }.map { it.tx } }
        val reverseCount = choices.values.flatten().groupingBy { it.key }.eachCount()
        val matched = mutableSetOf<String>()
        val enriched = orders.map { o ->
            val b = choices[o.key]?.singleOrNull()?.takeIf { reverseCount[it.key] == 1 }
            if (b == null) o else {
                matched += b.key
                mergeGroupedTransaction(o, b, Provider.JAGO)
            }
        }
        val result = enriched + bank.filter { it.key !in matched }
        return result.sortedWith(compareByDescending<UnifiedTransaction> { it.occurredAt }.thenBy { it.key })
    }

    fun reconcileProviderOrders(primary: List<UnifiedTransaction>, linked: List<UnifiedTransaction>): List<UnifiedTransaction> =
        reconcileLedger(primary + linked)

    private fun reconcileOrderPayments(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val byOrder = facts.filter { it.order.isNotBlank() }.groupBy { Triple(it.tx.provider, it.order, it.tx.money.currencyCode) }
        val consumed = mutableSetOf<String>()
        val combined = mutableListOf<UnifiedTransaction>()
        for (group in byOrder.values) {
            val payment = group.filter { it.confirmed }.singleOrNull() ?: continue
            val order = group.filter { !it.confirmed && it.tx.direction == payment.tx.direction }.singleOrNull() ?: continue
            if (!close(payment.tx, order.tx, 7 * 86400L)) continue
            consumed += payment.tx.key; consumed += order.tx.key
            combined += mergeGroupedTransaction(order.tx, payment.tx, payment.tx.provider)
        }
        return combined + transactions.filter { it.key !in consumed }
    }

    fun reconcilePocketTransfers(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val internal = facts.filter { it.tx.provider == Provider.JAGO && it.internal }
        val paired = pair(internal.filter { it.tx.direction == Direction.DEBIT }, internal.filter { it.tx.direction == Direction.CREDIT },
            windowSeconds = 60, candidate = { d, c ->
                close(d.tx, c.tx, 60) && d.tx.pocket != null && c.tx.pocket != null &&
                    d.tx.pocket.providerPocketId != c.tx.pocket.providerPocketId &&
                    ((d.code.startsWith("SAD") && c.code.startsWith("SAC")) || d.reference.isNotBlank() && d.reference == c.reference)
            }, merge = { d, c ->
                val from = d.pocket?.name ?: "Pocket"
                val to = c.pocket?.name ?: "Pocket"
                val payload = evidence(d).payload.put("isPocketTransfer", true)
                    .put("groupedTransactions", JSONArray().put(buildGroupEntry(d, "From $from")).put(buildGroupEntry(c, "To $to")))
                d.copy(description = "Pocket Transfer: $from to $to", category = CategoryConstants.INVESTMENTS_TRANSFERS, rawPayload = payload.toString())
            })
        val keys = internal.map { it.tx.key }.toSet()
        return paired + transactions.filter { it.key !in keys }
    }

    fun reconcileReversals(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val refunds = facts.filter { it.tx.direction == Direction.CREDIT && it.refund }
        val debits = facts.filter { it.tx.direction == Direction.DEBIT && !it.internal }
        val paired = pair(refunds, debits, windowSeconds = 14 * 86400L, candidate = { c, d ->
            val age = Duration.between(d.tx.occurredAt, c.tx.occurredAt)
            age >= Duration.ZERO && age <= Duration.ofDays(14) && compatibleAccount(c.tx, d.tx) &&
                (c.reference == d.tx.providerTransactionId || c.reference == d.tx.providerTransactionId.substringAfter(":") ||
                    (c.code.endsWith("-R", true) && c.code.removeSuffix("-R") == d.code && c.merchant == d.merchant))
        }, merge = { c, d ->
            val payload = evidence(d).payload.put("isReversal", true).put("originalChargeId", d.providerTransactionId)
                .put("groupedTransactions", JSONArray().put(buildGroupEntry(d, "Original Charge")).put(buildGroupEntry(c, "Refund")))
            d.copy(description = "${d.description} (Reversed)", isReversal = true, rawPayload = payload.toString())
        })
        val included = (refunds + debits).map { it.tx.key }.toSet()
        return paired + transactions.filter { it.key !in included }
    }

    private fun reconcilePartialRefunds(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val debits = facts.filter { it.tx.direction == Direction.DEBIT && !it.tx.isReversal }.associateBy { it.tx.key }
        val refunds = facts.filter { it.tx.direction == Direction.CREDIT && it.refund && it.reference.isNotBlank() }
        val grouped = refunds.mapNotNull { credit ->
            val debit = debits.values.singleOrNull { d -> d.tx.provider == credit.tx.provider &&
                d.tx.money.currencyCode == credit.tx.money.currencyCode && compatibleAccount(d.tx, credit.tx) &&
                (credit.reference == d.tx.providerTransactionId || credit.reference == d.tx.providerTransactionId.substringAfter(":")) &&
                Duration.between(d.tx.occurredAt, credit.tx.occurredAt).let { it >= Duration.ZERO && it <= Duration.ofDays(14) }
            } ?: return@mapNotNull null
            debit.tx.key to credit.tx
        }.groupBy({ it.first }, { it.second })
        val used = mutableSetOf<String>()
        val adjusted = transactions.map { debit ->
            val credits = grouped[debit.key].orEmpty()
            val refunded = credits.fold(java.math.BigInteger.ZERO) { sum, credit -> sum + java.math.BigInteger.valueOf(credit.money.amountMinor) }
            if (credits.isEmpty() || refunded > java.math.BigInteger.valueOf(debit.money.amountMinor)) debit else {
                used += credits.map { it.key }
                val payload = evidence(debit).payload.put("refundedAmountMinor", refunded.toLong())
                val groups = JSONArray().put(buildGroupEntry(debit, "Original Charge"))
                credits.forEach { groups.put(buildGroupEntry(it, "Partial Refund")) }
                payload.put("groupedTransactions", groups)
                val remaining = debit.money.amountMinor - refunded.toLong()
                debit.copy(money = if (remaining > 0) debit.money.copy(amountMinor = remaining) else debit.money,
                    isReversal = remaining == 0L, description = if (remaining == 0L) "${debit.description} (Reversed)" else debit.description,
                    rawPayload = payload.toString())
            }
        }
        return adjusted.filter { it.key !in used }
    }

    internal fun mergeGroupedTransaction(
        primary: UnifiedTransaction,
        secondary: UnifiedTransaction,
        secondaryProvider: Provider = Provider.JAGO,
    ): UnifiedTransaction {
        val pPayload = runCatching { JSONObject(primary.rawPayload) }.getOrNull() ?: JSONObject()
        val grouped = JSONArray()
        val existing = pPayload.optJSONArray("groupedTransactions")
        if (existing != null && existing.length() > 0) {
            for (i in 0 until existing.length()) {
                existing.optJSONObject(i)?.let { grouped.put(it) }
            }
        } else {
            val primaryRole = when (primary.provider) {
                Provider.SHOPEE, Provider.TOKOPEDIA -> "Marketplace Order"
                Provider.GOJEK, Provider.GRAB -> "Trip / Ride"
                else -> "Transaction"
            }
            grouped.put(buildGroupEntry(primary, primaryRole))
        }

        val sId = secondary.providerTransactionId
        var alreadyPresent = false
        for (i in 0 until grouped.length()) {
            val gObj = grouped.optJSONObject(i) ?: continue
            val gProv = gObj.optString("provider")
            val id = gObj.optString("providerTransactionId")
            if (gProv == secondaryProvider.name && id == sId) {
                alreadyPresent = true
                break
            }
        }
        if (!alreadyPresent) {
            val secondaryRole = if (secondary.cardLast4 != null) "Card Payment" else "Bank Payment"
            grouped.put(buildGroupEntry(secondary, secondaryRole, secondaryProvider))
        }

        val mergedPayload = JSONObject(pPayload.toString())
            .put("groupedTransactions", grouped)
        if (mergedPayload.optString("pocketName").isBlank() && secondary.pocket != null) {
            mergedPayload.put("pocketName", secondary.pocket.name)
        }
        if (mergedPayload.optString("cardLast4").isBlank() && secondary.cardLast4 != null) {
            mergedPayload.put("cardLast4", secondary.cardLast4)
        }

        return primary.copy(
            money = secondary.money,
            occurredAt = secondary.occurredAt,
            canonicalKey = secondary.key,
            isReversal = secondary.isReversal,
            isConfirmed = secondary.isConfirmed,
            userNote = secondary.userNote ?: primary.userNote,
            classificationIsManual = secondary.classificationIsManual || primary.classificationIsManual,
            category = when {
                secondary.classificationIsManual -> secondary.category
                primary.classificationIsManual -> primary.category
                secondary.category == CategoryConstants.UNCATEGORIZED -> primary.category ?: secondary.category
                else -> secondary.category ?: primary.category
            },
            aiReasoning = secondary.aiReasoning ?: primary.aiReasoning,
            cardLast4 = primary.cardLast4 ?: secondary.cardLast4,
            pocket = primary.pocket ?: secondary.pocket,
            isQris = primary.isQris || secondary.isQris,
            rawPayload = mergedPayload.toString(),
        )
    }

    internal fun buildGroupEntry(
        tx: UnifiedTransaction,
        role: String,
        overrideProvider: Provider? = null,
    ): JSONObject {
        val prov = overrideProvider ?: tx.provider
        val p = runCatching { JSONObject(tx.rawPayload) }.getOrNull()
        val subtitle = when {
            prov == Provider.SHOPEE && p?.optString("shopName")?.isNotBlank() == true -> {
                val shop = p.optString("shopName")
                val itemsCount = p.optJSONArray("items")?.length() ?: 0
                if (itemsCount > 1) "$shop ($itemsCount items)" else shop
            }
            prov == Provider.GOJEK && p?.optString("pickupLocation")?.isNotBlank() == true -> {
                val pickup = p.optString("pickupLocation")
                val dropoff = p.optString("dropoffLocation")
                "$pickup to $dropoff"
            }
            else -> {
                val card = (tx.cardLast4 ?: p?.optString("cardLast4"))?.takeUnless { it == "null" || it.isBlank() }?.let { "Visa Debit Card $it" }
                val pocket = tx.pocket?.name ?: p?.optString("pocketName")?.takeUnless { it == "null" || it.isBlank() }
                listOfNotNull(card, pocket).joinToString(" - ").ifBlank { tx.description }
            }
        }
        return JSONObject()
            .put("provider", prov.name)
            .put("role", role)
            .put("providerTransactionId", tx.providerTransactionId)
            .put("description", tx.description)
            .put("subtitle", subtitle)
            .put("direction", tx.direction.name)
            .put("amountMinor", tx.money.amountMinor)
            .put("currencyCode", tx.money.currencyCode)
            .put("occurredAtEpochMillis", tx.occurredAt.toEpochMilli())
            .put("pocketName", tx.pocket?.name)
            .put("cardLast4", tx.cardLast4)
            .put("isQris", tx.isQris)
            .put("rawPayload", tx.rawPayload)
    }

    private fun isMatchingProvider(jagoTx: UnifiedTransaction, targetProvider: Provider): Boolean {
        val text = "${jagoTx.description} ${jagoTx.rawPayload}".lowercase(Locale.ROOT)
        return when (targetProvider) {
            Provider.SHOPEE -> "shopee" in text
            Provider.TOKOPEDIA -> listOf("tokopedia", "tkpd", "toped").any { it in text }
            Provider.GOJEK -> listOf("gojek", "goride", "gocar", "gofood", "gopay", "gobills").any { it in text }
            Provider.GRAB -> listOf("grab", "ovo").any { it in text }
            Provider.JAGO -> false
        }
    }
}
