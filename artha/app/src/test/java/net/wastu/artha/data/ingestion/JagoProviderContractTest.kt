package net.wastu.artha.data.ingestion

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.ledger.TransactionEntity
import net.wastu.artha.data.providers.AccountStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fictional fixtures follow the retained native Hive cache's row and container types. */
class JagoProviderContractTest {
    @Test fun cachedBankRowPreservesSourceIdentityMoneyDirectionDateNotesAndFields() {
        val source = fields().apply { put("f50", mapOf("fictional" to true)) }
        val row = parse(source).single()
        assertEquals(Provider.JAGO, row.provider)
        assertEquals("jago:fictional-bank-01:$SOURCE_DATE", row.providerTransactionId)
        assertEquals(Direction.DEBIT, row.direction)
        assertEquals(2_500_000L, row.money.amountMinor)
        assertEquals("IDR", row.money.currencyCode)
        assertEquals(SOURCE_INSTANT, row.occurredAt)
        assertEquals(POCKET_ID, row.pocket?.providerPocketId)
        assertEquals("Main Pocket", row.pocket?.name)
        assertTrue(row.isConfirmed)
        val payload = JSONObject(row.rawPayload)
        assertEquals("jago-local-hive", payload.getString("origin"))
        assertEquals("Fictional note", payload.getString("notes"))
        assertEquals("Fictional English description", payload.getString("localized"))
        assertEquals(SOURCE_DATE, payload.getJSONObject("fields").getString("f5"))
        assertTrue(payload.getJSONObject("fields").getJSONObject("f50").getBoolean("fictional"))
        assertFalse(payload.has("paymentStatus"))
    }

    @Test fun sourceIdPrecedenceAndRawOffsetDateRemainIdentityAuthority() {
        val alternateDate = "2026-01-01T12:04:05.123+09:00"
        val row = parse(fields().apply { put("f5", alternateDate); put("f13", 0L) }).single()
        assertEquals("jago:fictional-bank-01:$alternateDate", row.providerTransactionId)
        assertEquals(SOURCE_INSTANT, row.occurredAt)
        assertEquals("fictional-fallback-01", JSONObject(row.rawPayload).getJSONObject("fields").getString("f6"))
    }

    @Test fun existingFallbackIdContractDoesNotCanonicalizeTheRawDate() {
        val row = parse(fields().apply { put("f47", "") }).single()
        assertEquals("jago:fictional-fallback-01:$SOURCE_DATE", row.providerTransactionId)
        assertEquals(SOURCE_INSTANT, row.occurredAt)
    }

    @Test fun malformedFirstObservationCannotShadowTheValidDuplicate() {
        val malformed = fields().apply { put("f3", BigDecimal("25.001")) }
        val partial = partial { parse(malformed, fields(), fields()) }
        assertEquals(1, partial.transactions.size)
        assertEquals("jago:fictional-bank-01:$SOURCE_DATE", partial.transactions.single().providerTransactionId)
        assertEquals(2_500_000L, partial.transactions.single().money.amountMinor)
        assertTrue(partial.message.orEmpty().contains("1 malformed bank rows"))
    }

    @Test fun malformedDuplicateStillReportsFailureAfterAValidObservation() {
        val partial = partial { parse(fields(), fields().apply { put("f10", "UNKNOWN") }) }
        assertEquals(1, partial.transactions.size)
        assertEquals(Direction.DEBIT, partial.transactions.single().direction)
    }

    @Test fun zeroValueObservationDoesNotReserveAnIdentity() {
        assertEquals(1, parse(fields().apply { put("f3", 0L) }, fields()).size)
        assertTrue(parse(fields().apply { put("f3", 0L) }).isEmpty())
    }

    @Test fun amountMagnitudeAndExplicitDirectionRemainIndependent() {
        val debit = parse(fields().apply { put("f3", -25000L) }).single()
        val credit = parse(fields().apply { put("f3", 25000L); put("f10", "CREDIT") }).single()
        assertEquals(debit.money, credit.money)
        assertEquals(Direction.DEBIT, debit.direction)
        assertEquals(Direction.CREDIT, credit.direction)
        assertEquals(125L, parse(fields().apply { put("f3", 1.25) }).single().money.amountMinor)
    }

    @Test fun fractionalOverflowAndUnsupportedAmountsNeverRoundOrCoerce() {
        for (amount in listOf(BigDecimal("12.345"), Long.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, 90_071_992_547_409.94, 167_772.16f, "25000", null)) {
            val malformed = fields("fictional-invalid").apply { put("f3", amount) }
            val partial = partial { parse(malformed, fields()) }
            assertEquals(1, partial.transactions.size)
            assertEquals(2_500_000L, partial.transactions.single().money.amountMinor)
        }
    }

    @Test fun currencyUsesTheRowFirstAndOnlyAnExplicitPocketFallback() {
        val explicit = parse(fields().apply { put("f41", "USD") }).single()
        assertEquals("USD", explicit.money.currencyCode)
        for (value in listOf(null, "")) {
            assertEquals("IDR", parse(fields().apply { put("f41", value) }).single().money.currencyCode)
        }
        val noPocketCurrency = pocket().apply { remove("f44") }
        assertEquals("IDR", parse(fields(), sourcePocket = noPocketCurrency).single().money.currencyCode)
        failure { parse(fields().apply { put("f41", "") }, sourcePocket = noPocketCurrency) }
    }

    @Test fun unsupportedCurrencyTypeAndScaleCannotFallBackOrChangeUnits() {
        for (value in listOf("JPY", "XXX", "idr", 123)) {
            val partial = partial { parse(fields("fictional-invalid").apply { put("f41", value) }, fields()) }
            assertEquals(1, partial.transactions.size)
            assertEquals("IDR", partial.transactions.single().money.currencyCode)
        }
    }

    @Test fun unverifiedNumericTimestampsCannotReplaceThePaymentDate() {
        val millis = SOURCE_INSTANT.toEpochMilli()
        for (value in listOf(millis, millis.toDouble(), BigDecimal("$millis.0"), mapOf("millis" to millis))) {
            val rejected = fields("fictional-invalid").apply { put("f5", "fictional-source-date"); put("f13", value) }
            val partial = partial { parse(rejected, fields()) }
            assertEquals(listOf(SOURCE_INSTANT), partial.transactions.map { it.occurredAt })
            assertEquals("jago:fictional-bank-01:$SOURCE_DATE", partial.transactions.single().providerTransactionId)
        }
    }

    @Test fun malformedTimestampFieldsNeverInventPaymentTime() {
        for (value in listOf(1.5, BigDecimal("9223372036854775808"), Double.NaN,
            "1767225845123", mapOf("millis" to BigDecimal("1.001")), mapOf("millis" to "1"), emptyMap<String, Any>(), null)) {
            val malformed = fields("fictional-invalid").apply { put("f5", ""); put("f13", value) }
            val partial = partial { parse(malformed, fields()) }
            assertEquals(listOf(SOURCE_INSTANT), partial.transactions.map { it.occurredAt })
        }
    }

    @Test fun validOffsetDateHasAuthorityOverTheNumericFallback() {
        val row = parse(fields().apply { put("f13", BigDecimal("1.5")) }).single()
        assertEquals(SOURCE_INSTANT, row.occurredAt)
        assertEquals("jago:fictional-bank-01:$SOURCE_DATE", row.providerTransactionId)
    }

    @Test fun finerSourceTimeFailsExplicitlyWithoutTruncationOrFallback() {
        val malformed = fields("fictional-invalid").apply { put("f5", "2026-01-01T10:04:05.123456+07:00") }
        val partial = partial { parse(malformed, fields()) }
        assertEquals(listOf(SOURCE_INSTANT), partial.transactions.map { it.occurredAt })
        failure { parse(malformed) }
    }

    @Test fun roomRoundTripPreservesExactBankTimeIdentityCardAndQrisFacts() {
        val card = fields().apply { put("f1", "PSD02"); put("f2", "Fictional card •••• 4242") }
        val qris = fields("fictional-bank-02").apply { put("f1", "QRD01") }
        for (row in parse(card, qris)) {
            val stored = TransactionEntity.fromDomain(row, Instant.EPOCH).toDomain()
            assertEquals(row.providerTransactionId, stored.providerTransactionId)
            assertEquals(row.occurredAt, stored.occurredAt)
            assertEquals(row.money, stored.money)
            assertEquals(row.direction, stored.direction)
            assertEquals(row.rawPayload, stored.rawPayload)
            assertEquals(row.cardLast4, stored.cardLast4)
            assertEquals(row.isQris, stored.isQris)
            assertTrue(stored.isConfirmed)
        }
    }

    @Test fun malformedRowsAndListsRetainValidRowsInOtherPocketContainers() {
        for (bad in listOf("broken", emptyMap<String, Any>(), mapOf("f2" to "unsupported"))) {
            val history = mapOf(POCKET_ID to mapOf("f2" to listOf(fields(), "malformed row")),
                "fictional-other-pocket" to bad)
            val partial = partial { JagoCachedHistory.parse(mapOf(POCKET_ID to pocket(), "fictional-other-pocket" to pocket()), history) }
            assertEquals(1, partial.transactions.size)
            assertTrue(partial.message.orEmpty().contains("1 malformed bank rows and 1 unsupported cached containers"))
        }
    }

    @Test fun missingAndMalformedPocketsCannotMasqueradeAsMainPocket() {
        for (bad in listOf(null, "broken", mapOf("f4" to 123), mapOf("f4" to ""), emptyMap<String, Any>())) {
            val pockets = mapOf(POCKET_ID to pocket(), "fictional-other-pocket" to bad)
            val history = mapOf(POCKET_ID to mapOf("f2" to listOf(fields())),
                "fictional-other-pocket" to mapOf("f2" to listOf(fields("fictional-bank-02"))))
            val rows = JagoCachedHistory.parse(pockets, history)
            assertEquals(2, rows.size)
            val orphan = rows.single { it.providerTransactionId.startsWith("jago:fictional-bank-02:") }
            assertNull(orphan.pocket)
            assertEquals(2_500_000L, orphan.money.amountMinor)
            assertEquals(SOURCE_INSTANT, orphan.occurredAt)
            assertFalse(JSONObject(orphan.rawPayload).has("pocketName"))
            assertEquals("fictional-other-pocket", JSONObject(orphan.rawPayload).getString("pocketId"))
        }
    }

    @Test fun orphanDuplicateBeforeNamedPocketPreservesTheNamedOriginalPayload() {
        val original = parse(fields()).single()
        val orphan = fields().apply { put("f13", 0L) }
        val history = linkedMapOf("fictional-aggregate" to mapOf("f2" to listOf(orphan)),
            POCKET_ID to mapOf("f2" to listOf(fields())))
        val rows = JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()), history)
        assertEquals(listOf(original), rows)
        assertEquals(original.rawPayload, rows.single().rawPayload)
    }

    @Test fun orphanWithoutCurrencyRejectsOnlyThatRowAndNeverDefaultsToRupiah() {
        val malformed = fields("fictional-invalid").apply { remove("f41") }
        val history = mapOf("fictional-aggregate" to mapOf("f2" to listOf(malformed, fields())))
        val retained = partial { JagoCachedHistory.parse(emptyMap(), history) }.transactions.single()
        assertEquals("jago:fictional-bank-01:$SOURCE_DATE", retained.providerTransactionId)
        assertNull(retained.pocket)
        assertEquals("IDR", retained.money.currencyCode)
        failure { JagoCachedHistory.parse(emptyMap(), mapOf("fictional-aggregate" to mapOf("f2" to listOf(malformed)))) }
    }

    @Test fun unnamedPocketCurrencyRetainsProofWithoutInventingPocketEnrichment() {
        val unnamed = mapOf("f4" to "", "f44" to "IDR")
        val row = parse(fields().apply { remove("f41") }, sourcePocket = unnamed).single()
        assertEquals("IDR", row.money.currencyCode)
        assertNull(row.pocket)
        assertFalse(JSONObject(row.rawPayload).has("pocketName"))
        assertEquals(SOURCE_DATE, JSONObject(row.rawPayload).getJSONObject("fields").getString("f5"))
    }

    @Test fun preferredMalformedObservationStillCannotShadowAValidOrphan() {
        val malformed = fields().apply { put("f3", BigDecimal("25.001")) }
        val history = linkedMapOf("fictional-aggregate" to mapOf("f2" to listOf(fields())),
            POCKET_ID to mapOf("f2" to listOf(malformed)))
        val retained = partial { JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()), history) }.transactions.single()
        assertEquals("jago:fictional-bank-01:$SOURCE_DATE", retained.providerTransactionId)
        assertEquals(2_500_000L, retained.money.amountMinor)
        assertNull(retained.pocket)
    }

    @Test fun absentOrUnsupportedContainersFailTypedInsteadOfReportingEmptySuccess() {
        assertNull(JagoCachedHistory.parse(emptyMap(), mapOf(POCKET_ID to mapOf("f2" to listOf(fields())))).single().pocket)
        failure { JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()), emptyMap()) }
        failure { JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()), mapOf(POCKET_ID to mapOf("f2" to "unsupported"))) }
        assertTrue(JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()), mapOf(POCKET_ID to mapOf("f2" to emptyList<Any>()))).isEmpty())
        assertTrue(JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()),
            mapOf("fictional-retired-pocket" to mapOf("f2" to emptyList<Any>()))).isEmpty())
    }

    @Test fun cancellationDuringRowParsingPropagatesInsteadOfBecomingPartialData() {
        val job = Job()
        var checkpoints = 0
        try {
            JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()), mapOf(POCKET_ID to mapOf("f2" to listOf(fields(), fields())))) {
                if (++checkpoints == 4) job.cancel()
                job.ensureActive()
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(4, checkpoints)
        }
    }

    @Test fun sortingAlsoChecksCancellationAndCleanRowsAreOrderedBySourceTime() {
        val later = fields("fictional-bank-02").apply { put("f5", "2026-01-02T10:04:05.123+07:00") }
        assertEquals("jago:fictional-bank-02:2026-01-02T10:04:05.123+07:00", parse(fields(), later).first().providerTransactionId)
        var checkpoints = 0
        try {
            JagoCachedHistory.parse(mapOf(POCKET_ID to pocket()), mapOf(POCKET_ID to mapOf("f2" to listOf(fields(), later)))) {
                if (++checkpoints == 7) throw CancellationException("Fictional sorting cancellation")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(7, checkpoints)
        }
    }

    private fun fields(id: String = "fictional-bank-01"): MutableMap<String, Any?> = mutableMapOf(
        "f1" to "OTD10", "f2" to "Fictional counterpart", "f3" to 25000L, "f4" to "Fictional note",
        "f5" to SOURCE_DATE, "f6" to "fictional-fallback-01", "f10" to "DEBIT", "f13" to SOURCE_INSTANT.toEpochMilli(),
        "f14" to "{\"en\":\"Fictional English description\",\"id\":\"Fictional Indonesian description\"}",
        "f41" to "IDR", "f47" to id,
    )
    private fun pocket(): MutableMap<String, Any?> = mutableMapOf("f4" to "MAIN_ACCOUNT", "f44" to "IDR")
    private fun parse(vararg rows: Any?, sourcePocket: Map<String, Any?> = pocket()): List<UnifiedTransaction> =
        JagoCachedHistory.parse(mapOf(POCKET_ID to sourcePocket), mapOf(POCKET_ID to mapOf("f2" to rows.toList())))
    private fun partial(block: () -> Unit): PartialImportException {
        try { block() } catch (error: PartialImportException) { return error }
        throw AssertionError("Expected partial cached history")
    }
    private fun failure(block: () -> Unit): ProviderReadException {
        try { block() } catch (error: ProviderReadException) {
            assertEquals(AccountStatus.ERROR, error.status)
            assertFalse(error.message.orEmpty().contains("fictional-bank"))
            return error
        }
        throw AssertionError("Expected typed cached history failure")
    }
    companion object {
        private const val POCKET_ID = "fictional-pocket-01"
        private const val SOURCE_DATE = "2026-01-01T10:04:05.123+07:00"
        private val SOURCE_INSTANT = Instant.parse("2026-01-01T03:04:05.123Z")
    }
}
