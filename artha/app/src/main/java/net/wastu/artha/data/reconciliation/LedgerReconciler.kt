package net.wastu.artha.data.reconciliation

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import java.time.Duration
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object LedgerReconciler {

    private data class AmountKey(val currency: String, val amount: Long, val provider: Provider, val direction: Direction)
    private data class Evidence(val tx: UnifiedTransaction, val payload: JSONObject) {
        val code = payload.optString("code").ifBlank { payload.optJSONObject("fields")?.optString("f1").orEmpty() }
        val reference = payload.optString("originalChargeId").ifBlank { payload.optString("paymentReference") }
        val order = payload.optString("orderNumber").ifBlank { payload.optString("orderId") }.ifBlank { payload.optString("order_id") }
        val origin = payload.optString("origin")
        val merchant = payload.optString("counterpart").ifBlank { tx.description }.trim().lowercase(Locale.ROOT)
        val internal = code !in net.wastu.artha.core.model.TransactionClassifier.INTEREST_CODES &&
            (code in setOf("SAD01", "SAD02", "SAC01", "SAC02", "TDD02", "TDC02", "TDC03", "TDC08", "TDC12") ||
                payload.optJSONObject("fields")?.optString("f22") == "INTERNAL")
        val refund = tx.isReversal || payload.optBoolean("verifiedRefund") || payload.optString("serviceTier").endsWith("REFUND") || code.endsWith("-R", true) || code == "GPC02" ||
            tx.description.contains("refund", true) || tx.description.contains("reversal", true)
        private val legacyGrabCharge = payload.optJSONObject("item")?.let { item ->
            item.optString("status") == "COMPLETED" && item.optString("currency") == "IDR" &&
                ((item.optString("txType") == "BOOKING_CHARGE" && item.optString("coreTxType") == "BookingCharge") ||
                    (item.optString("txType") == "TIPPING_CHARGE" && item.optString("coreTxType") == "TippingCharge")) &&
                runCatching { Math.multiplyExact(item.getLong("amount"), 100L) == tx.money.amountMinor && java.time.Instant.ofEpochSecond(item.getLong("timestamp")) == tx.occurredAt }.getOrDefault(false)
        } == true
        // Older Grab imports guessed DEBIT. Keep those rows, but require source direction
        // evidence before treating an old wallet row as a confirmed payment.
        private val settled = payload.optString("paymentStatus") == "COMPLETED" &&
            payload.optString("paymentDirection") == tx.direction.name && payload.optString("paymentCurrency") == tx.money.currencyCode &&
            payload.optLong("paymentAmountMinor", -1L) == tx.money.amountMinor &&
            runCatching { java.time.Instant.parse(payload.getString("paymentTimestamp")) == tx.occurredAt }.getOrDefault(false)
        private val legacyGoPay = payload.optJSONObject("item")?.let { item ->
            item.optString("status") == "COMPLETED" && item.optString("payment_type") == tx.direction.name &&
                item.optJSONObject("amount")?.optString("currency") == tx.money.currencyCode &&
                runCatching { item.getJSONObject("amount").get("value").toString().toBigDecimal().movePointRight(2).longValueExact() == tx.money.amountMinor && java.time.OffsetDateTime.parse(item.getString("order_timestamp")).toInstant() == tx.occurredAt }.getOrDefault(false)
        } == true
        private val savedShopeePayment = tx.provider == Provider.SHOPEE && tx.direction == Direction.DEBIT &&
            origin in setOf("shopee-order-api", "shopeefood-order-api", "shopee-digital-api") &&
            payload.optBoolean("paymentDetailVerified") && tx.money.currencyCode == "IDR" && runCatching {
                val paid = payload.get("sourceAmountPaid").toString().toBigDecimal().divide(java.math.BigDecimal(1000)).longValueExact()
                paid == tx.money.amountMinor && when (origin) {
                    "shopee-order-api" -> {
                        val time = payload.get("sourcePaymentTime").toString().toBigDecimal().longValueExact()
                        java.time.Instant.ofEpochSecond(time) == tx.occurredAt
                    }
                    "shopee-digital-api" -> {
                        val time = payload.get("sourcePaidTime").toString().toBigDecimal().longValueExact()
                        java.time.Instant.ofEpochSecond(time) == tx.occurredAt
                    }
                    else -> {
                        val time = payload.get("sourcePaymentTime").toString().toBigDecimal().longValueExact()
                        java.time.Instant.ofEpochMilli(time) == tx.occurredAt
                    }
                }
            }.getOrDefault(false)
        val confirmed = tx.provider == Provider.JAGO || savedShopeePayment ||
            tx.provider == Provider.SHOPEE && tx.direction == Direction.CREDIT && origin == "shopee-refund-api" && settled &&
                payload.optString("paymentEvidence") == "settled-provider-refund" && payload.optLong("sourceRefundStatus") == 5L &&
                payload.optBoolean("verifiedRefund") || tx.provider == Provider.GOJEK && origin == "gopay-order-history" &&
            (settled && payload.optString("paymentEvidence") in setOf("settled-wallet-history", "gopay-screen") || legacyGoPay) ||
            tx.provider == Provider.GRAB && origin == "grab-wallet-api" &&
            (settled && payload.optString("paymentEvidence") == "settled-charge-history" || legacyGrabCharge && tx.direction == Direction.DEBIT)
        val funding = payload.optBoolean("isWalletFunding") || payload.optBoolean("isPocketTransfer") || internal ||
            (tx.provider == Provider.JAGO && (
                code in setOf("WTD02", "WTD03", "WTD06", "WTD-AJ-EWALLET") ||
                payload.optJSONObject("fields")?.optString("f16")?.contains("C058") == true ||
                payload.optJSONObject("fields")?.optJSONObject("f16")?.optString("code") == "C058" ||
                ((code in setOf("OTD10", "TFD70") || code.startsWith("WTD")) &&
                    listOf("gopay", "ovo", "shopeepay", "dana", "linkaja", "dnid", "top up", "topup").any {
                        it in merchant || it in tx.description.lowercase(Locale.ROOT)
                    })
            )) ||
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

    /** GoPay moves into its own savings pocket (GoPay Tabungan); always the user's own money. */
    private const val GOPAY_SAVINGS_SWEEP = "AUTO_SWEEP_WITHDRAWAL"
    /** GoPay withdrawals to a bank account; own money only when that bank shows the matching deposit. */
    private val GOPAY_BANK_TRANSFERS = setOf("DIRECT_DEBIT_BANK_TRANSFER", "PP_DIRECT_DEBIT_BANK_TRANSFER")
    private const val OWN_TRANSFER_WINDOW_SECONDS = 15 * 60L

    /** The e-wallet a Jago top-up funds: the provider Artha reads it through, or null when Artha can't read it. */
    private fun toppedUpWallet(e: Evidence): Pair<Boolean, Provider?> {
        val text = (e.merchant + " " + e.tx.description).lowercase(Locale.ROOT)
        return when {
            "shopeepay" in text -> true to Provider.SHOPEE
            "gopay" in text -> true to Provider.GOJEK
            "ovo" in text -> true to Provider.GRAB
            listOf("dana", "dnid", "linkaja").any { it in text } -> true to null
            else -> false to null
        }
    }

    fun reconcileLedger(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val unique = transactions.distinctBy { it.key }
        // Structural evidence only; never the account holder's name. Sources with saved data count as connected.
        val connected = unique.mapTo(mutableSetOf()) { it.provider }
        val credits = unique.filter { it.direction == Direction.CREDIT }.groupBy { it.money.currencyCode to it.money.amountMinor }
        fun depositedElsewhere(t: UnifiedTransaction) = credits[t.money.currencyCode to t.money.amountMinor].orEmpty()
            .any { it.provider != t.provider && close(it, t, OWN_TRANSFER_WINDOW_SECONDS) }
        val initial = unique.map { t ->
            val e = evidence(t)
            val tier = e.payload.optString("serviceTier")
            val ownTransfer = t.provider == Provider.GOJEK && t.direction == Direction.DEBIT &&
                (tier == GOPAY_SAVINGS_SWEEP || tier in GOPAY_BANK_TRANSFERS && depositedElsewhere(t))
            val (isTopUp, wallet) = if (t.provider == Provider.JAGO && e.funding && !e.internal &&
                !e.payload.optBoolean("isPocketTransfer")) toppedUpWallet(e) else false to null
            // A top-up into a wallet Artha can't read is where the spending ends for Artha, so it counts.
            val untrackedTopUp = isTopUp && (wallet == null || wallet !in connected)
            val payload = when {
                ownTransfer -> e.payload.put("isOwnTransfer", true).toString()
                untrackedTopUp -> e.payload.put("untrackedWalletTopUp", true).toString()
                e.funding -> e.payload.put("isWalletFunding", true).toString()
                else -> t.rawPayload
            }
            t.copy(isConfirmed = e.confirmed, rawPayload = payload)
        }
        val deduped = reconcileWalletReceipts(reconcileOrderPayments(reconcilePocketTransfers(initial)))
        val bank = deduped.filter { it.provider == Provider.JAGO }
        val bankIdentities = bank.map { it.providerTransactionId.substringAfter(":") to it.money }.toSet()
        val orders = deduped.filter { it.provider != Provider.JAGO }.filterNot { o ->
            evidence(o).origin == "jago-linked-local-hive" && (o.providerTransactionId.substringAfter(":") to o.money) in bankIdentities
        }
        val bankIndex = bank.map(::evidence).groupBy { it.tx.money.currencyCode to it.tx.money.amountMinor }
        val choices = orders.associate { o ->
            val orderEv = evidence(o)
            val orderId = orderEv.order.ifBlank { o.providerTransactionId.removePrefix("gopay:") }
            val candidates = bankIndex[o.money.currencyCode to o.money.amountMinor].orEmpty().filter { e ->
                val b = e.tx
                o.direction == b.direction && close(o, b, 7 * 86400L) && isMatchingProvider(b, paymentProvider(o)) &&
                    compatibleAccount(o, b) && !e.funding
            }
            val exactRefMatch = if (orderId.isNotBlank()) {
                candidates.filter { e ->
                    val jagoF6 = e.payload.optJSONObject("fields")?.optString("f6").orEmpty()
                    val jagoF18 = e.payload.optJSONObject("fields")?.optString("f18").orEmpty()
                    val refs = listOf(e.reference, jagoF6, jagoF18).map { it.removeSuffix("-1").trim() }.filter { it.isNotBlank() }
                    orderId in refs || o.providerTransactionId in refs
                }
            } else emptyList()
            o.key to (if (exactRefMatch.isNotEmpty()) exactRefMatch else candidates).map { it.tx }
        }
        val reverseCount = choices.values.flatten().groupingBy { it.key }.eachCount()
        val assigned = mutableMapOf<String, UnifiedTransaction>()
        for (o in orders) choices[o.key]?.singleOrNull()?.takeIf { reverseCount[it.key] == 1 }?.let { assigned[o.key] = it }
        // Contested candidates (repeated amounts) resolve one-to-one by nearest time, within a day.
        val taken = assigned.values.mapTo(mutableSetOf()) { it.key }
        val orderByKey = orders.associateBy { it.key }
        val contested = choices.filter { (k, v) -> k !in assigned && v.isNotEmpty() }
            .flatMap { (k, v) -> v.filter { it.key !in taken }.map { b -> Triple(orderByKey.getValue(k), b, Duration.between(orderByKey.getValue(k).occurredAt, b.occurredAt).abs()) } }
            .filter { it.third <= Duration.ofDays(1) }
            .sortedWith(compareBy<Triple<UnifiedTransaction, UnifiedTransaction, Duration>> { it.third }.thenBy { it.first.key }.thenBy { it.second.key })
        for ((o, b, _) in contested) {
            if (o.key in assigned || b.key in taken) continue
            assigned[o.key] = b; taken += b.key
        }
        val matched = mutableSetOf<String>()
        val enriched = orders.map { o ->
            val b = assigned[o.key]
            if (b == null) {
                // Plausible partners that remain unassigned could double count this payment.
                if (choices[o.key].orEmpty().isNotEmpty() && o.isConfirmed) o.copy(isConfirmed = false) else o
            } else {
                matched += b.key
                mergeGroupedTransaction(o, b, Provider.JAGO)
            }
        }
        val result = reconcileReversals(reconcilePartialRefunds(enriched + bank.filter { it.key !in matched }))
        return result.sortedWith(compareByDescending<UnifiedTransaction> { it.occurredAt }.thenBy { it.key })
    }

    fun reconcileProviderOrders(primary: List<UnifiedTransaction>, linked: List<UnifiedTransaction>): List<UnifiedTransaction> =
        reconcileLedger(primary + linked)

    private fun reconcileOrderPayments(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val byOrder = facts.filter { it.order.isNotBlank() }.groupBy { Triple(it.tx.provider,
            it.payload.optString("bookingNumber").ifBlank { it.order }, it.tx.money.currencyCode) }
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

    /** A provider-paid receipt and a wallet statement can describe the same charge. */
    private fun reconcileWalletReceipts(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val wallets = facts.filter { it.confirmed && it.origin in setOf("gopay-order-history", "grab-wallet-api") }
        val receipts = facts.filter { it.tx.provider == Provider.SHOPEE && it.tx.direction == Direction.DEBIT && it.confirmed }
        val index = wallets.groupBy { Triple(it.tx.money.currencyCode, it.tx.money.amountMinor, it.tx.direction) }
            .mapValues { (_, rows) -> rows.sortedBy { it.tx.occurredAt } }
        val choices = receipts.associate { receipt ->
            val candidates = within(index[Triple(receipt.tx.money.currencyCode, receipt.tx.money.amountMinor, receipt.tx.direction)].orEmpty(), receipt.tx.occurredAt, 86400)
                .filter { wallet -> compatibleAccount(receipt.tx, wallet.tx) &&
                    (receipt.tx.cardLast4 == null || wallet.tx.cardLast4 != null) && (
                    isMatchingProvider(wallet.tx, receipt.tx.provider) ||
                    listOf(wallet.tx.provider.displayName, if (wallet.tx.provider == Provider.GOJEK) "GoPay" else "OVO")
                        .any { receipt.payload.optString("sourcePaymentMethod").contains(it, true) } &&
                        receipt.merchant.isNotBlank() && receipt.merchant == wallet.merchant)
                }.map { it.tx }
            receipt.tx.key to candidates
        }
        val reverse = choices.values.flatten().groupingBy { it.key }.eachCount()
        val consumed = mutableSetOf<String>()
        val updated = receipts.map { receipt ->
            val wallet = choices[receipt.tx.key]?.singleOrNull()?.takeIf { reverse[it.key] == 1 }
            when {
                wallet != null -> { consumed += wallet.key; mergeGroupedTransaction(receipt.tx, wallet, wallet.provider) }
                choices[receipt.tx.key].orEmpty().isNotEmpty() -> receipt.tx.copy(isConfirmed = false)
                else -> receipt.tx
            }
        }
        val replaced = receipts.mapTo(mutableSetOf()) { it.tx.key }
        return updated + transactions.filter { it.key !in consumed && it.key !in replaced }
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
                d.copy(description = "Pocket Transfer: $from to $to", category = null, rawPayload = payload.toString())
            })
        val keys = internal.map { it.tx.key }.toSet()
        return paired + transactions.filter { it.key !in keys }
    }

    fun reconcileReversals(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val refunds = facts.filter { it.tx.direction == Direction.CREDIT && it.refund }
        val debits = facts.filter { it.tx.direction == Direction.DEBIT && !it.internal }
        // A receipt enriched with a bank charge keeps the original bank evidence.
        // Match reversals against that evidence rather than the receipt's merchant/code.
        val debitEvidence = debits.associate { d -> d.tx.key to bankPaymentEvidence(d.tx) }
        val index = debits.groupBy { it.tx.money.currencyCode to it.tx.money.amountMinor }
        val choices = refunds.associate { c -> c.tx.key to index[c.tx.money.currencyCode to c.tx.money.amountMinor].orEmpty().filter { d ->
            val payment = debitEvidence[d.tx.key] ?: d
            val age = Duration.between(payment.tx.occurredAt, c.tx.occurredAt)
            c.tx.isConfirmed && c.reference.isBlank() && !d.tx.isReversal && c.tx.provider == payment.tx.provider &&
                age >= Duration.ZERO && age <= Duration.ofDays(14) && compatibleAccount(c.tx, payment.tx) &&
                c.code.endsWith("-R", true) && c.code.dropLast(2).equals(payment.code, true) && reversalIdentityMatches(c, payment)
        } }
        val reverseCount = choices.values.flatten().groupingBy { it.tx.key }.eachCount()
        val used = mutableSetOf<String>()
        val replacements = refunds.mapNotNull { c ->
            val d = choices[c.tx.key]?.singleOrNull()?.takeIf { reverseCount[it.tx.key] == 1 } ?: return@mapNotNull null
            used += c.tx.key
            val payload = JSONObject(d.payload.toString()).put("isReversal", true).put("originalChargeId", d.tx.providerTransactionId)
            val groups = payload.optJSONArray("groupedTransactions") ?: JSONArray().put(buildGroupEntry(d.tx, "Original Charge"))
            groups.put(buildGroupEntry(c.tx, "Refund"))
            payload.put("groupedTransactions", groups)
            d.tx.key to d.tx.copy(description = "${d.tx.description} (Reversed)", isReversal = true, rawPayload = payload.toString())
        }.toMap()
        return transactions.filter { it.key !in used }.map { replacements[it.key] ?: it }
    }

    private fun reversalIdentityMatches(credit: Evidence, debit: Evidence): Boolean {
        if (credit.tx.provider == Provider.JAGO && debit.tx.provider == Provider.JAGO &&
            credit.origin == "jago-local-hive" && debit.origin == "jago-local-hive" &&
            (debit.code == "PSD02" || debit.code.startsWith("VISA-ECOM-EDC-"))) {
            // Native card reversals share f6 with their charge; counterpart text can
            // instead contain a card mask on one side and merchant text on the other.
            fun reference(row: Evidence) = (row.payload.optJSONObject("fields")?.opt("f6") as? String)
                ?.trim()?.takeIf { it.isNotBlank() && !it.all { char -> char == '0' } }
            val creditReference = reference(credit)
            val debitReference = reference(debit)
            if (creditReference != null || debitReference != null) return creditReference != null && creditReference == debitReference
        }
        return credit.merchant == debit.merchant
    }

    private fun paymentProvider(tx: UnifiedTransaction): Provider = tx.canonicalKey?.substringBefore(':')
        ?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: tx.provider

    private fun bankPaymentEvidence(tx: UnifiedTransaction): Evidence? {
        if (tx.provider == Provider.JAGO) return evidence(tx)
        if (paymentProvider(tx) != Provider.JAGO) return null
        val groups = evidence(tx).payload.optJSONArray("groupedTransactions") ?: return null
        val group = (0 until groups.length()).mapNotNull(groups::optJSONObject).singleOrNull {
            it.optString("provider") == Provider.JAGO.name && "JAGO:${it.optString("providerTransactionId")}" == tx.key
        } ?: return null
        return runCatching { evidence(tx.copy(provider = Provider.JAGO, providerTransactionId = group.getString("providerTransactionId"),
            description = group.getString("description"), rawPayload = group.getString("rawPayload"),
            occurredAt = java.time.Instant.ofEpochMilli(group.getLong("occurredAtEpochMillis")))) }.getOrNull()
    }

    private fun reconcilePartialRefunds(transactions: List<UnifiedTransaction>): List<UnifiedTransaction> {
        val facts = transactions.map(::evidence)
        val debits = facts.filter { it.tx.direction == Direction.DEBIT && !it.tx.isReversal }.associateBy { it.tx.key }
        val refunds = facts.filter { it.tx.direction == Direction.CREDIT && it.refund && it.reference.isNotBlank() }
        // Group aliases retain original provider identities after bank/order enrichment.
        val references = mutableMapOf<Triple<Provider, String, String>, MutableList<Evidence>>()
        fun register(provider: Provider, id: String, debit: Evidence) {
            for (alias in setOf(id, id.substringAfter(":"))) {
                references.getOrPut(Triple(provider, alias, debit.tx.money.currencyCode)) { mutableListOf() }.add(debit)
            }
        }
        for (debit in debits.values) {
            register(debit.tx.provider, debit.tx.providerTransactionId, debit)
            val groups = debit.payload.optJSONArray("groupedTransactions")
            for (i in 0 until (groups?.length() ?: 0)) {
                val group = groups?.optJSONObject(i) ?: continue
                val provider = runCatching { Provider.valueOf(group.getString("provider")) }.getOrNull() ?: continue
                group.optString("providerTransactionId").takeIf(String::isNotBlank)?.let { register(provider, it, debit) }
            }
        }
        val grouped = refunds.mapNotNull { credit ->
            if (!credit.tx.isConfirmed && !credit.payload.optBoolean("verifiedRefund")) return@mapNotNull null
            val debit = references[Triple(credit.tx.provider, credit.reference, credit.tx.money.currencyCode)]
                .orEmpty().distinctBy { it.tx.key }.singleOrNull { d -> compatibleAccount(d.tx, credit.tx) && credit.tx.occurredAt >= d.tx.occurredAt }
                ?: return@mapNotNull null
            debit.tx.key to credit.tx
        }.groupBy({ it.first }, { it.second })
        val used = mutableSetOf<String>()
        val adjusted = transactions.map { debit ->
            val credits = grouped[debit.key].orEmpty()
            val refunded = credits.fold(java.math.BigInteger.ZERO) { sum, credit -> sum + java.math.BigInteger.valueOf(credit.money.amountMinor) }
            if (credits.isEmpty() || refunded > java.math.BigInteger.valueOf(debit.money.amountMinor)) debit else {
                used += credits.map { it.key }
                val payload = evidence(debit).payload.put("grossPaidAmountMinor", debit.money.amountMinor).put("refundedAmountMinor", refunded.toLong())
                val groups = payload.optJSONArray("groupedTransactions") ?: JSONArray().put(buildGroupEntry(debit, "Original Charge"))
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
                Provider.SHOPEE -> if (pPayload.optString("origin") == "shopee-digital-api") "Digital Purchase" else "Marketplace Order"
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
            val secondaryRole = when {
                secondaryProvider == Provider.JAGO -> if (secondary.cardLast4 != null) "Card Payment" else "Bank Payment"
                evidence(secondary).origin in setOf("gopay-order-history", "grab-wallet-api") -> "Wallet Payment"
                else -> "Linked Record"
            }
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
                val card = (tx.cardLast4 ?: p?.optString("cardLast4"))?.takeUnless { it == "null" || it.isBlank() }?.let { "Card $it" }
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
            Provider.GOJEK -> listOf("gojek", "goride", "gocar", "gofood", "gopay", "gobills").any { it in text }
            Provider.GRAB -> listOf("grab", "ovo").any { it in text }
            Provider.JAGO -> false
        }
    }
}
