package net.wastu.artha.ui

import net.wastu.artha.ui.screens.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {
    private val session = ReviewSession(listOf("a", "b", "c", "d"))

    @Test
    fun applyAdvancesAndRecordsDecision() {
        val next = session.apply("Food")
        assertEquals("b", next.current)
        assertEquals("Food", next.decisions["a"])
        assertEquals(2, next.position)
        assertTrue(next.canGoBack)
    }

    @Test
    fun backReturnsToPreviousItemWithItsCategoryPreselected() {
        val back = session.apply("Food").back()
        assertEquals("a", back.current)
        assertEquals("Food", back.selection("AI guess"))
        assertFalse(back.canGoBack)
    }

    @Test
    fun applyingAgainOverwritesTheEarlierDecision() {
        val redone = session.apply("Food").back().apply("Transport")
        assertEquals("Transport", redone.decisions["a"])
        assertEquals("b", redone.current)
    }

    @Test
    fun similarItemsAreDecidedTogetherAndSkippedOver() {
        val next = session.apply("Food", listOf("c", "a", "missing"))
        assertEquals("b", next.current)
        assertEquals("Food", next.decisions["c"])
        assertNull(next.decisions["missing"])
        assertEquals("d", next.skip().current)
    }

    @Test
    fun skipLeavesNoDecisionAndCanBeSteppedBack() {
        val skipped = session.skip()
        assertEquals("b", skipped.current)
        assertTrue(skipped.decisions.isEmpty())
        assertEquals("a", skipped.back().current)
    }

    @Test
    fun finishesAfterLastItem() {
        val done = ReviewSession(listOf("a")).apply("Food")
        assertTrue(done.finished)
        assertNull(done.current)
        assertEquals(1, done.position)
    }

    @Test
    fun undoRevertsTheWholeGroupAndReportsKeysToClear() {
        val (undone, cleared) = session.apply("Food", listOf("c")).undo()
        assertEquals(listOf("a", "c"), cleared)
        assertEquals("a", undone.current)
        assertTrue(undone.decisions.isEmpty())
        assertFalse(undone.canGoBack)
    }

    @Test
    fun survivesJsonRoundTrip() {
        val original = session.apply("Food", listOf("c")).skip()
        assertEquals(original, ReviewSession.fromJson(original.toJson()))
    }

    @Test
    fun corruptJsonRestoresNothing() {
        assertNull(ReviewSession.fromJson("nope"))
    }
}
