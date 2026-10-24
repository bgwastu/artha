package net.wastu.artha.ui

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.CategoryProgress
import net.wastu.artha.data.analysis.PaymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryState
import net.wastu.artha.data.analysis.paymentCategoryStateLabel
import net.wastu.artha.ui.screens.PeerPrompt
import net.wastu.artha.ui.screens.TransactionDetailsState
import net.wastu.artha.ui.screens.canEditTransaction
import net.wastu.artha.ui.screens.paymentCategoryRowSupporting
import net.wastu.artha.ui.screens.paymentNoteRowSupporting
import net.wastu.artha.ui.widget.widgetCategoryText
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PaymentLockTest {

    private fun samplePayment(
        key: String = "payment-1",
        category: String? = "Groceries",
        direction: Direction = Direction.DEBIT,
        isReversal: Boolean = false,
    ) = UnifiedTransaction(
        provider = Provider.JAGO,
        providerTransactionId = key,
        direction = direction,
        money = Money(25000),
        occurredAt = Instant.parse("2026-10-15T09:30:00Z"),
        description = "Supermarket",
        rawPayload = "{}",
        category = category,
        isReversal = isReversal,
    )

    @Test
    fun paymentCategoryStateDerivation() {
        val progress = CategoryProgress(
            activeKeys = setOf("JAGO:active"),
            queuedKeys = setOf("JAGO:queued"),
        )
        assertEquals(PaymentCategoryState.CATEGORIZING, paymentCategoryState("JAGO:active", progress))
        assertEquals(PaymentCategoryState.QUEUED, paymentCategoryState("JAGO:queued", progress))
        assertEquals(PaymentCategoryState.IDLE, paymentCategoryState("JAGO:other", progress))
    }

    @Test
    fun rowSupportingLabelsAndEditVisibilityHelpers() {
        assertNull(paymentCategoryStateLabel(PaymentCategoryState.IDLE))
        assertEquals("Queued", paymentCategoryStateLabel(PaymentCategoryState.QUEUED))
        assertEquals("Categorizing", paymentCategoryStateLabel(PaymentCategoryState.CATEGORIZING))

        assertNull(paymentCategoryRowSupporting(PaymentCategoryState.IDLE))
        assertEquals("Queued", paymentCategoryRowSupporting(PaymentCategoryState.QUEUED))
        assertEquals("Categorizing", paymentCategoryRowSupporting(PaymentCategoryState.CATEGORIZING))

        assertNull(paymentNoteRowSupporting(PaymentCategoryState.IDLE))
        assertEquals("Locked while categorizing", paymentNoteRowSupporting(PaymentCategoryState.QUEUED))
        assertEquals("Locked while categorizing", paymentNoteRowSupporting(PaymentCategoryState.CATEGORIZING))

        assertTrue(canEditTransaction(PaymentCategoryState.IDLE, hasStore = true))
        assertFalse(canEditTransaction(PaymentCategoryState.IDLE, hasStore = false))
        assertFalse(canEditTransaction(PaymentCategoryState.QUEUED, hasStore = true))
        assertFalse(canEditTransaction(PaymentCategoryState.CATEGORIZING, hasStore = true))
    }

    @Test
    fun widgetCategoryTextPureHelper() {
        val busyKeys = setOf("JAGO:busy")

        val busyPayment = samplePayment("busy", category = "Food")
        assertEquals("Categorizing", widgetCategoryText(busyPayment, busyKeys))

        val idlePayment = samplePayment("idle", category = "Food")
        assertEquals("Food", widgetCategoryText(idlePayment, busyKeys))

        val uncategorized = samplePayment("uncat", category = null)
        assertNull(widgetCategoryText(uncategorized, busyKeys))
        assertEquals("Categorizing", widgetCategoryText(uncategorized, setOf("JAGO:uncat")))

        val credit = samplePayment("credit", category = "Refund", direction = Direction.CREDIT)
        assertNull(widgetCategoryText(credit, busyKeys))
        assertEquals("Categorizing", widgetCategoryText(credit, setOf("JAGO:credit")))

        val reversed = samplePayment("reversed", category = "Food", isReversal = true)
        assertNull(widgetCategoryText(reversed, busyKeys))
        assertEquals("Categorizing", widgetCategoryText(reversed, setOf("JAGO:reversed")))

        assertEquals("Categorizing", widgetCategoryText("JAGO:busy", "Food", busyKeys))
        assertEquals("Food", widgetCategoryText("JAGO:idle", "Food", busyKeys))
        assertNull(widgetCategoryText("JAGO:idle", null, busyKeys))
    }

    @Test
    fun chooseCategoryGuardsWhenBusy() {
        val state = TransactionDetailsState().apply { editor = "category" }
        var persistCalls = 0
        var onDoneCalls = 0

        state.chooseCategory(
            category = "Dining",
            current = "Groceries",
            isBusy = true,
            persist = { _, _ -> persistCalls++ },
            onDone = { onDoneCalls++ },
        )

        assertEquals(0, persistCalls)
        assertEquals(0, onDoneCalls)
        assertFalse(state.saving)
        assertEquals("", state.categoryDraft)
        assertNull(state.saveError)
        assertNull(state.savedFeedback)

        state.chooseCategory(
            category = "Dining",
            current = "Groceries",
            isBusy = false,
            persist = { _, complete ->
                persistCalls++
                complete(Result.success(Unit))
            },
            onDone = { onDoneCalls++ },
        )

        assertEquals(1, persistCalls)
        assertEquals(1, onDoneCalls)
        assertFalse(state.saving)
        assertEquals("Dining", state.categoryDraft)
        assertEquals("Category saved", state.savedFeedback)
    }

    @Test
    fun applyMerchantPromptGuardsWhenBusy() {
        val state = TransactionDetailsState().apply {
            peerPrompt = PeerPrompt("Food", "Starbucks", 3)
        }
        var appliedCount = 0

        state.applyMerchantPrompt(isBusy = true) {
            appliedCount++
        }
        assertEquals(0, appliedCount)

        state.applyMerchantPrompt(isBusy = false) { prompt ->
            appliedCount += prompt.peerCount
        }
        assertEquals(3, appliedCount)
    }

    @Test
    fun onPaymentBusyClosesCategoryEditorWithoutSavingAndShowsFeedback() {
        val state = TransactionDetailsState().apply {
            editor = "category"
            saving = true
            categoryDraft = "Pending Draft"
        }

        state.onPaymentBusy()

        assertNull(state.editor)
        assertFalse(state.saving)
        assertNull(state.saveError)
        assertEquals("Categorizing. Try again in a moment", state.savedFeedback)
    }

    @Test
    fun onPaymentBusyClosesNoteEditorWithoutSavingAndShowsFeedback() {
        val state = TransactionDetailsState().apply {
            editor = "note"
            saving = true
            noteDraft = "Unsaved note"
        }

        state.onPaymentBusy()

        assertNull(state.editor)
        assertFalse(state.saving)
        assertNull(state.saveError)
        assertEquals("Categorizing. Try again in a moment", state.savedFeedback)
    }

    @Test
    fun onPaymentBusyDoesNothingWhenNotInEditor() {
        val state = TransactionDetailsState().apply {
            editor = null
            savedFeedback = null
        }

        state.onPaymentBusy()

        assertNull(state.editor)
        assertNull(state.savedFeedback)
    }
}
