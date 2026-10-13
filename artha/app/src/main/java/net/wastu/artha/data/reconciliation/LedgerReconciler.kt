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

    /**
     * Master deep interface: reconciles the entire ledger in one unified pass:
     * 1. Pairs internal Jago pocket moves (SAD/SAC) and Term Deposits into single [TRANSFER] transactions.
     * 2. Pairs authorization reversals (PSD02-R, GPC02, refunds) with original debits into [REVERSED] transactions.
     * 3. Fuses fulfilled marketplace orders (Shopee, Tokopedia) and rides (Gojek, Grab) with Jago funding legs.
     */
    fun reconcileLedger(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val afterPocketTransfers = reconcilePocketTransfers(transactions)
        val afterReversals = reconcileReversals(afterPocketTransfers)
        // 1. Identify real marketplace/food orders vs synthetic Jago-linked copies
        val deduplicated = afterReversals.filter { item ->
            !(item.provider != Provider.JAGO && item.rawPayload.contains("\"origin\":\"jago-linked-local-hive\""))
        }

        val nonJago = deduplicated.filter { it.provider != Provider.JAGO }
        val jago = deduplicated.filter { it.provider == Provider.JAGO }
        if (nonJago.isEmpty() || jago.isEmpty()) return deduplicated

        val matchedJagoIds = mutableSetOf<String>()
        val enrichedNonJago = nonJago.map { item ->
            var match = jago.firstOrNull { j ->
                j.providerTransactionId !in matchedJagoIds &&
                    j.money.amountMinor == item.money.amountMinor &&
                    j.direction == item.direction &&
                    Duration.between(item.occurredAt, j.occurredAt).abs().toDays() <= 7 &&
                    isMatchingProvider(j, item.provider)
            }
            if (match == null && item.rawPayload.contains("shopeefood-order-api")) {
                match = jago.firstOrNull { j ->
                    j.providerTransactionId !in matchedJagoIds &&
                        j.direction == Direction.DEBIT &&
                        Duration.between(item.occurredAt, j.occurredAt).abs().toMinutes() <= 15 &&
                        kotlin.math.abs(j.money.amountMinor - item.money.amountMinor) <= 1_500_000L
                }
            }
            if (match != null) {
                matchedJagoIds.add(match.providerTransactionId)
                mergeGroupedTransaction(item, match, Provider.JAGO)
            } else {
                item
            }
        }
        val remainingJago = jago.filter { it.providerTransactionId !in matchedJagoIds }
        return (enrichedNonJago + remainingJago).sortedByDescending { it.occurredAt }
    }

    /**
     * Ingestion-level reconciliation: fuses direct marketplace orders or rides with linked bank debits.
     */
    fun reconcileProviderOrders(
        primary: List<UnifiedTransaction>,
        linked: List<UnifiedTransaction>,
    ): List<UnifiedTransaction> {
        val matchedLinkedIds = mutableSetOf<String>()
        val enrichedPrimary = primary.map { p ->
            var match = linked.firstOrNull { l ->
                l.providerTransactionId !in matchedLinkedIds &&
                    l.money.amountMinor == p.money.amountMinor &&
                    Duration.between(p.occurredAt, l.occurredAt).abs().toDays() <= 7
            }
            if (match == null && p.rawPayload.contains("shopeefood-order-api")) {
                match = linked.firstOrNull { l ->
                    l.providerTransactionId !in matchedLinkedIds &&
                        Duration.between(p.occurredAt, l.occurredAt).abs().toMinutes() <= 15 &&
                        kotlin.math.abs(l.money.amountMinor - p.money.amountMinor) <= 1_500_000L
                }
            }
            if (match != null) {
                matchedLinkedIds.add(match.providerTransactionId)
                mergeGroupedTransaction(p, match, Provider.JAGO)
            } else {
                p
            }
        }
        val remainingLinked = linked.filter { it.providerTransactionId !in matchedLinkedIds }
        val seen = mutableSetOf<String>()
        return (enrichedPrimary + remainingLinked)
            .filter { seen.add(it.providerTransactionId) }
            .sortedByDescending { it.occurredAt }
    }

    /**
     * Pairs matching Jago pocket debits and credits into single [TRANSFER] transactions.
     */
    fun reconcilePocketTransfers(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val pocketCodes = setOf("SAD01", "SAD02", "SAC01", "SAC02", "TDD02", "TDC02", "TDC03", "TDC08", "TDC12")
        val jagoDebits = transactions.filter { it.provider == Provider.JAGO && it.direction == Direction.DEBIT }
        val jagoCredits = transactions.filter { it.provider == Provider.JAGO && it.direction == Direction.CREDIT }
        if (jagoDebits.isEmpty() || jagoCredits.isEmpty()) return transactions

        val matchedDebits = mutableSetOf<String>()
        val matchedCredits = mutableSetOf<String>()
        val fusedTransfers = mutableListOf<UnifiedTransaction>()

        for (d in jagoDebits) {
            val pD = runCatching { JSONObject(d.rawPayload) }.getOrNull()
            val codeD = pD?.optString("code").orEmpty()
            val isInternalD = codeD in pocketCodes || pD?.optJSONObject("fields")?.optString("f22") == "INTERNAL" ||
                d.description.startsWith("Pocket:") || d.description.startsWith("Term Deposit")
            if (!isInternalD) continue

            for (c in jagoCredits) {
                if (c.providerTransactionId in matchedCredits) continue
                val pC = runCatching { JSONObject(c.rawPayload) }.getOrNull()
                val codeC = pC?.optString("code").orEmpty()
                val isInternalC = codeC in pocketCodes || pC?.optJSONObject("fields")?.optString("f22") == "INTERNAL" ||
                    c.description.startsWith("Pocket:") || c.description.startsWith("Term Deposit")
                if (!isInternalC) continue

                val timeDiff = kotlin.math.abs(Duration.between(d.occurredAt, c.occurredAt).toMillis())
                if (d.money.amountMinor == c.money.amountMinor && timeDiff <= 3600_000L) {
                    matchedDebits.add(d.providerTransactionId)
                    matchedCredits.add(c.providerTransactionId)

                    val srcPocket = d.pocket?.name ?: "Pocket"
                    val dstPocket = c.pocket?.name?.takeIf { it != srcPocket }
                        ?: pD?.optString("localized")?.takeIf { it.isNotBlank() }
                        ?: "Target Pocket"
                    val cleanDst = if (dstPocket.isBlank() || dstPocket.equals("MAIN_ACCOUNT", ignoreCase = true)) "Main Pocket" else dstPocket.trim()

                    val fromGroup = buildGroupEntry(d, "From $srcPocket")
                    val toGroup = buildGroupEntry(c, "To $cleanDst")
                    val mergedGrouped = JSONArray().put(fromGroup).put(toGroup)

                    val mergedPayload = (pD ?: JSONObject())
                        .put("isPocketTransfer", true)
                        .put("groupedTransactions", mergedGrouped)

                    fusedTransfers.add(
                        d.copy(
                            description = "Pocket Transfer: $srcPocket to $cleanDst",
                            category = CategoryConstants.INVESTMENTS_TRANSFERS,
                            aiReasoning = "Internal transfer from $srcPocket to $cleanDst; excluded from monthly spending.",
                            rawPayload = mergedPayload.toString(),
                        )
                    )
                    break
                }
            }
        }

        val remaining = transactions.filter {
            it.providerTransactionId !in matchedDebits && it.providerTransactionId !in matchedCredits
        }
        return (fusedTransfers + remaining).sortedByDescending { it.occurredAt }
    }

    /**
     * Pairs credit authorization holds and reversals with original debits into single [REVERSED] records.
     */
    fun reconcileReversals(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val credits = transactions.filter { it.direction == Direction.CREDIT }
        val debits = transactions.filter { it.direction == Direction.DEBIT }
        if (credits.isEmpty() || debits.isEmpty()) return transactions

        val matchedDebits = mutableSetOf<String>()
        val matchedCredits = mutableSetOf<String>()
        val fusedReversals = mutableListOf<UnifiedTransaction>()

        for (c in credits) {
            val pC = runCatching { JSONObject(c.rawPayload) }.getOrNull()
            val codeC = pC?.optString("code").orEmpty()
            val descC = c.description
            val isReversal = codeC.endsWith("-R", ignoreCase = true) ||
                codeC.equals("GPC02", ignoreCase = true) ||
                descC.contains("refund", ignoreCase = true) ||
                descC.contains("reversal", ignoreCase = true)
            if (!isReversal) continue

            for (d in debits) {
                if (d.providerTransactionId in matchedDebits) continue
                val timeDiff = Duration.between(d.occurredAt, c.occurredAt).toMillis()
                if (d.money.amountMinor == c.money.amountMinor && timeDiff in 0..(14L * 86400 * 1000)) {
                    matchedDebits.add(d.providerTransactionId)
                    matchedCredits.add(c.providerTransactionId)

                    val primaryGroup = buildGroupEntry(c, "Reversal / Refund")
                    val secondaryGroup = buildGroupEntry(d, "Original Charge")
                    val mergedGrouped = JSONArray().put(primaryGroup).put(secondaryGroup)

                    val mergedPayload = (pC ?: JSONObject())
                        .put("isReversal", true)
                        .put("originalChargeId", d.providerTransactionId)
                        .put("groupedTransactions", mergedGrouped)

                    val fusedTitle = if (d.description.contains("Reversed", ignoreCase = true)) d.description else "${d.description} (Reversed)"
                    fusedReversals.add(
                        c.copy(
                            description = fusedTitle,
                            cardLast4 = c.cardLast4 ?: d.cardLast4,
                            pocket = c.pocket ?: d.pocket,
                            rawPayload = mergedPayload.toString(),
                        )
                    )
                    break
                }
            }
        }

        val remaining = transactions.filter {
            it.providerTransactionId !in matchedDebits && it.providerTransactionId !in matchedCredits
        }
        return (fusedReversals + remaining).sortedByDescending { it.occurredAt }
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

        val sId = secondary.providerTransactionId.substringAfter(":")
        var alreadyPresent = false
        for (i in 0 until grouped.length()) {
            val gObj = grouped.optJSONObject(i) ?: continue
            val gProv = gObj.optString("provider")
            val id = gObj.optString("providerTransactionId").substringAfter(":")
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
