package net.wastu.artha.data.reconciliation

import java.time.Instant
import net.wastu.artha.core.model.*
import net.wastu.artha.data.analysis.BudgetAnalyzer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BankCardReversalTest {
    private fun payment(id: String, credit: Boolean, reference: String?, counterpart: String) = UnifiedTransaction(
        Provider.JAGO, id, if (credit) Direction.CREDIT else Direction.DEBIT, Money(1_000_000),
        Instant.parse("2026-10-01T00:00:00Z").plusSeconds(if (credit) 120 else 0), "Example subscription",
        JSONObject().put("origin", "jago-local-hive").put("code", if (credit) "PSD02-R" else "PSD02")
            .put("counterpart", counterpart).put("fields", JSONObject().apply { if (reference != null) put("f6", reference) }).toString(),
        pocket = Pocket("fixture-pocket", "Fictional pocket"),
    )

    @Test fun bankReferencePairsReversalWhenCounterpartTextChanges() {
        val debit = payment("charge", false, "shared-reference", "Card ending 1234")
        val credit = payment("credit", true, "shared-reference", "Example subscription")
        val result = LedgerReconciler.reconcileLedger(listOf(debit, credit)).single()
        assertTrue(result.isReversal)
        assertFalse(BudgetAnalyzer.isSpending(result))
        assertEquals(debit.money, result.money)
        assertEquals(debit.key, result.key)
        assertEquals(2, JSONObject(result.rawPayload).getJSONArray("groupedTransactions").length())
        assertEquals(listOf(result), LedgerReconciler.reconcileLedger(listOf(result)))
    }

    @Test fun conflictingOrMissingReferenceCannotFallBackToSameMerchant() {
        val debit = payment("charge", false, "original-reference", "Same merchant")
        for (reference in listOf("other-reference", null)) {
            val result = LedgerReconciler.reconcileLedger(listOf(debit, payment("credit", true, reference, "Same merchant")))
            assertEquals(2, result.size)
            assertFalse(result.first { it.direction == Direction.DEBIT }.isReversal)
        }
    }

    @Test fun convertedForeignCardReversalsUseTheSameNativeReference() {
        for (code in listOf("VISA-ECOM-EDC-USD-D-IDR", "VISA-ECOM-EDC-SGD-D-IDR")) {
            fun withCode(tx: UnifiedTransaction, value: String) = tx.copy(rawPayload = JSONObject(tx.rawPayload).put("code", value).toString())
            val debit = withCode(payment("charge", false, "shared-reference", "Card ending 1234"), code)
            val credit = withCode(payment("credit", true, "shared-reference", "Example subscription"), "$code-R")
            val row = LedgerReconciler.reconcileLedger(listOf(debit, credit)).single()
            assertTrue(row.isReversal)
            assertFalse(BudgetAnalyzer.isSpending(row))
            assertEquals(debit.money, row.money)
        }
    }

    @Test fun ambiguousChargesAndCreditsStaySeparate() {
        val debit = payment("charge", false, "shared-reference", "Card ending 1234")
        val credit = payment("credit", true, "shared-reference", "Example subscription")
        for (facts in listOf(listOf(debit, debit.copy(providerTransactionId = "another-charge"), credit),
            listOf(debit, credit, credit.copy(providerTransactionId = "another-credit")))) {
            assertEquals(3, LedgerReconciler.reconcileLedger(facts).size)
        }
    }

    @Test fun referenceCannotOverrideAmountCurrencyAccountOrTime() {
        val debit = payment("charge", false, "shared-reference", "Card ending 1234")
        val credit = payment("credit", true, "shared-reference", "Example subscription")
        for (changed in listOf(credit.copy(money = Money(900_000)), credit.copy(money = Money(1_000_000, "USD")),
            credit.copy(pocket = Pocket("other-pocket", "Other")), credit.copy(occurredAt = debit.occurredAt.minusSeconds(1)),
            credit.copy(occurredAt = debit.occurredAt.plusSeconds(15 * 86400)))) {
            assertEquals(2, LedgerReconciler.reconcileLedger(listOf(debit, changed)).size)
        }
    }

    @Test fun bankReferencePairsReversalWithoutTopLevelCode() {
        // Cached Jago rows may miss the top-level `code`; their transaction code lives in f1.
        fun hive(id: String, credit: Boolean, code: String, f6: String, counterpart: String) = UnifiedTransaction(
            Provider.JAGO, id, if (credit) Direction.CREDIT else Direction.DEBIT, Money(1_000_000),
            Instant.parse("2026-10-22T03:07:00Z").plusSeconds(if (credit) 65 else 0), "Shopee",
            JSONObject().put("origin", "jago-local-hive")
                .put("fields", JSONObject().put("f1", code).put("f6", f6)
                    .put("f16", if (credit) JSONObject().put("code", "C054") else JSONObject())
                    .put("f10", if (credit) "CREDIT" else "DEBIT")).toString(),
            pocket = Pocket("fixture-pocket", "Main Pocket"),
        )
        val row = LedgerReconciler.reconcileLedger(listOf(
            hive("jago:charge:2026-10-22T03:07:30.561Z", false, "PSD02", "629403258855", "Card ending 8384"),
            hive("jago:credit:2026-10-22T03:08:35.683Z", true, "PSD02-R", "629403258855", "Jago"),
        )).single()
        assertTrue(row.isReversal)
        assertEquals(2, JSONObject(row.rawPayload).getJSONArray("groupedTransactions").length())
    }
}
