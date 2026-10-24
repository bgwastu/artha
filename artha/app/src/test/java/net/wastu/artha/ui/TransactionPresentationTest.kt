package net.wastu.artha.ui

import java.time.LocalDate
import java.time.Instant
import net.wastu.artha.core.model.AccountingTime
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.ui.screens.TransactionFilters
import net.wastu.artha.ui.screens.TransactionPeriod
import net.wastu.artha.ui.screens.transactionSearchScope
import org.junit.Assert.*
import org.junit.Test

class TransactionPresentationTest {
    @Test fun humanLineUsesAsciiDashAndCollapsesDuplicateSegments() {
        val shopee = transaction("food").copy(
            provider = Provider.SHOPEE,
            description = "Mondja · Nasi Goreng (+1 more)",
            rawPayload = """{"shopName":"Mondja","items":[{"name":"Nasi Goreng"},{"name":"Es Teh"}]}""",
        )
        assertEquals("Mondja - Nasi Goreng (+1 more)", transactionHumanLine(shopee))
        val ride = transaction("ride").copy(
            provider = Provider.GRAB,
            description = "GrabCar: Senayan to Kuningan",
            rawPayload = """{"serviceTier":"GrabCar","pickupLocation":"Senayan","dropoffLocation":"Kuningan"}""",
        )
        assertEquals("GrabCar - Senayan to Kuningan", transactionHumanLine(ride))
        val reversed = transaction("rev").copy(description = "Warung Sari (Reversed)")
        assertEquals("Warung Sari", transactionHumanLine(reversed))
    }

    @org.junit.Test fun reversedPaymentShowsZeroButOriginalChargeRemainsAvailable() {
        val tx = net.wastu.artha.core.model.UnifiedTransaction(net.wastu.artha.core.model.Provider.JAGO, "fixture",
            net.wastu.artha.core.model.Direction.DEBIT, net.wastu.artha.core.model.Money(125000),
            java.time.Instant.EPOCH, "Fictional reversed payment", "{}", isReversal = true)
        assertEquals("Rp0", paymentRowAmount(tx))
        assertEquals(tx.money, originalPaymentMoney(tx))
        val partiallyRefunded = tx.copy(money = net.wastu.artha.core.model.Money(25000), isReversal = false, rawPayload = """{"grossPaidAmountMinor":125000}""")
        assertEquals(tx.money, originalPaymentMoney(partiallyRefunded))
    }

    @Test fun reversingCreditsKeepTheirRecordedIncomingAmountInListsAndWidgets() {
        val ordinary = transaction("fixture")
        val credit = ordinary.copy(direction = Direction.CREDIT, isReversal = true)
        assertEquals("+${formatMoney(credit.money)}", paymentRowAmount(credit))
        assertEquals("Incoming reversal", net.wastu.artha.ui.screens.transactionAccountingLabel(credit))
        assertFalse(net.wastu.artha.data.analysis.BudgetAnalyzer.isSpending(credit))
        assertEquals(ordinary.money, credit.money)
        assertEquals("Rp0", paymentRowAmount(ordinary.copy(isReversal = true)))
        assertEquals(formatMoney(ordinary.money), paymentRowAmount(ordinary))
    }

    @Test fun walletTopUpCodesReadAsWalletFundingNotAGenericExclusion() {
        val topUp = transaction("wtd").copy(rawPayload = """{"code":"WTD02"}""")
        assertFalse(net.wastu.artha.data.analysis.BudgetAnalyzer.isSpending(topUp))
        assertEquals("Wallet funding", net.wastu.artha.ui.screens.transactionAccountingLabel(topUp))
        val pocketMove = transaction("sad").copy(rawPayload = """{"code":"SAD01"}""")
        assertEquals("Transfer", net.wastu.artha.ui.screens.transactionAccountingLabel(pocketMove))
    }

    @Test fun browsingUsesConfirmationSourceAndDirectionFilters() {
        val records = listOf(
            transaction("payment"), transaction("order", confirmed = false),
            transaction("incoming", direction = Direction.CREDIT), transaction("other-source", provider = Provider.GRAB),
        )
        assertEquals(listOf("payment", "incoming", "other-source"), records.filter(TransactionFilters()::matches).map { it.providerTransactionId })
        val selected = TransactionFilters(provider = Provider.JAGO, direction = Direction.DEBIT)
        assertEquals(listOf("payment"), records.filter(selected::matches).map { it.providerTransactionId })
        assertFalse(records[1].isConfirmed)
    }

    @Test fun allSavedSearchExpandsBeyondCurrentDateAndSourceFiltersWithoutChangingRecords() {
        val saved = listOf(transaction("current"), transaction("unmatched", confirmed = false),
            transaction("outside-current-view", provider = Provider.GRAB))
        assertEquals(listOf("current", "outside-current-view"), transactionSearchScope(saved).map { it.providerTransactionId })
        assertFalse(transactionSearchScope(saved).any { !it.isConfirmed })
        assertEquals(3, saved.size)
        assertFalse(saved[1].isConfirmed)
    }

    @Test fun explicitSearchFiltersCombineSourceAndDirectionWithoutChangingSavedRecords() {
        val saved = listOf(transaction("bank-out"), transaction("bank-in", direction = Direction.CREDIT),
            transaction("wallet-in", provider = Provider.GRAB, direction = Direction.CREDIT),
            transaction("unverified", direction = Direction.CREDIT, confirmed = false))
        assertEquals(listOf("bank-in"), transactionSearchScope(saved,
            TransactionFilters(provider = Provider.JAGO, direction = Direction.CREDIT)).map { it.providerTransactionId })
        assertEquals(listOf("bank-out", "bank-in", "wallet-in"), transactionSearchScope(saved).map { it.providerTransactionId })
        assertFalse(saved.last().isConfirmed)
    }

    @Test fun searchDatesUseAccountingDaysWithInclusiveFinalDay() {
        val day = LocalDate.of(2026, 1, 12)
        val start = day.atStartOfDay(AccountingTime.zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(AccountingTime.zone).toInstant()
        val saved = listOf(transaction("before").copy(occurredAt = start.minusMillis(1)),
            transaction("start").copy(occurredAt = start), transaction("late").copy(occurredAt = end.minusMillis(1)),
            transaction("after").copy(occurredAt = end))
        val range = start.toEpochMilli() to start.toEpochMilli()
        assertEquals(listOf("start", "late"), transactionSearchScope(saved, range = range).map { it.providerTransactionId })
        assertEquals(4, transactionSearchScope(saved).size)
    }

    @Test fun previousMonthUsesItsActualLastDayAcrossLeapYearAndYearBoundary() {
        assertRange(TransactionPeriod.LAST_MONTH.range(LocalDate.of(2024, 3, 15)), LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29))
        assertRange(TransactionPeriod.LAST_MONTH.range(LocalDate.of(2026, 1, 3)), LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31))
    }

    @Test fun rollingMonthIncludesThirtyAccountingDaysAndCurrentMonthStopsAtToday() {
        val today = LocalDate.of(2026, 1, 12)
        assertRange(TransactionPeriod.LAST_30_DAYS.range(today), LocalDate.of(2025, 12, 14), today)
        assertRange(TransactionPeriod.THIS_MONTH.range(today), LocalDate.of(2026, 1, 1), today)
        val end = TransactionPeriod.THIS_MONTH.range(today)!!.second!!
        assertTrue(today.atTime(23, 59).atZone(AccountingTime.zone).toInstant().toEpochMilli() < AccountingTime.dayAfter(end))
        assertEquals(today.plusDays(1).atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli(), AccountingTime.dayAfter(end))
        assertNull(TransactionPeriod.ALL.range(today))
    }

    private fun assertRange(range: Pair<Long?, Long?>?, first: LocalDate, last: LocalDate) {
        fun day(value: Long?) = Instant.ofEpochMilli(value!!).atZone(AccountingTime.zone).toLocalDate()
        assertEquals(first, day(range!!.first))
        assertEquals(last, day(range.second))
    }

    @Test fun qrisRowPrefersTheSellerDescriptorOverTheAcquirerBank() {
        val jagoQris = transaction("qris").copy(
            provider = Provider.JAGO,
            description = "Circle K - Jak0922 - Qris",
            rawPayload = """{"isQris":true,"paymentMethod":"QRIS","counterpart":"Bank Mandiri",
                "localized":"CIRCLE K - JAK0922 - QRIS"}""",
        )
        assertEquals("Circle K - Jak0922", transactionHumanLine(jagoQris))
    }

    @Test fun qrisRowWithAcquirerOnlyDescriptorStillShowsTheBank() {
        val jagoQris = transaction("qris-bank").copy(
            provider = Provider.JAGO,
            description = "Bank Mandiri",
            rawPayload = """{"isQris":true,"paymentMethod":"QRIS","counterpart":"Bank Mandiri",
                "localized":"Bank Mandiri - QRIS"}""",
        )
        assertEquals("Bank Mandiri", transactionHumanLine(jagoQris))
    }

    @Test fun qrisAllCapsSellerDescriptorGetsTitleCaseWithoutGate() {
        val jagoQris = transaction("qris-caps").copy(
            provider = Provider.JAGO,
            description = "WEI QIANG",
            rawPayload = """{"isQris":true,"paymentMethod":"QRIS","counterpart":"BCA","localized":"WEI QIANG"}""",
        )
        assertEquals("Wei Qiang", transactionHumanLine(jagoQris))
    }

    @Test fun qrisMixedCaseSellerDescriptorKeepsStoredCase() {
        val jagoQris = transaction("qris-dolia").copy(
            provider = Provider.JAGO,
            description = "Dunia Kedol",
            rawPayload = """{"isQris":true,"paymentMethod":"QRIS","counterpart":"GoPay","localized":"Dunia Kedol"}""",
        )
        assertEquals("Dunia Kedol", transactionHumanLine(jagoQris))
    }

    private fun transaction(id: String, provider: Provider = Provider.JAGO, direction: Direction = Direction.DEBIT, confirmed: Boolean = true) =
        UnifiedTransaction(provider, id, direction, Money(10000), Instant.parse("2026-01-12T02:00:00Z"), "Fixture", "{}", isConfirmed = confirmed)
}