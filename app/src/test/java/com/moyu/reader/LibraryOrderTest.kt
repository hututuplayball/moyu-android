package com.moyu.reader

import com.moyu.reader.ui.LibraryOrder
import com.moyu.reader.ui.moveBookInOrder
import com.moyu.reader.ui.moveIdToEnd
import com.moyu.reader.ui.reconcileBookOrder
import com.moyu.reader.ui.validGroupName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOrderTest {
    private val source = listOf("a", "b", "c")
    private val moved = listOf("b", "c", "a")

    @Test fun movesBothDirectionsWithoutMutatingSource() {
        assertEquals(moved, moveBookInOrder(source, "a", "c"))
        assertEquals(listOf("c", "a", "b"), moveBookInOrder(source, "c", "a"))
        assertEquals(listOf("a", "b", "c"), source)
        assertEquals(source, moveBookInOrder(source, "a", "a"))
        assertEquals(source, moveBookInOrder(source, "missing", "a"))
        assertEquals(source, moveBookInOrder(source, "a", "missing"))
        assertEquals(emptyList<String>(), moveBookInOrder(emptyList(), "a", "b"))
    }

    @Test fun movesIdToEndWithoutTouchingTheLastItem() {
        assertEquals(listOf("b", "c", "a"), moveIdToEnd(source, "a"))
        assertEquals(listOf("a", "c", "b"), moveIdToEnd(source, "b"))
        assertEquals(source, moveIdToEnd(source, "c"))
        assertEquals(source, moveIdToEnd(source, "missing"))
        assertEquals(listOf("b", "a"), moveIdToEnd(listOf("a", "b"), "a"))
    }

    @Test fun moveToEndThroughTheDraftCommits() {
        val order = LibraryOrder()
        assertTrue(order.begin(source))
        assertTrue(order.moveToEnd("b"))
        assertEquals(listOf("a", "c", "b"), order.complete(source))
    }

    @Test fun reconcilesDeletedAndNewBooksWithoutDuplicates() {
        assertEquals(listOf("c", "a", "d"), reconcileBookOrder(listOf("c", "b", "c", "a"), listOf("a", "c", "d")))
        assertEquals(source, reconcileBookOrder(emptyList(), source))
        assertEquals(emptyList<String>(), reconcileBookOrder(source, emptyList()))
    }

    @Test fun cancelDiscardsOnlyTheDraft() {
        val order = LibraryOrder()
        assertTrue(order.begin(source))
        order.move("a", "c")
        assertEquals(moved, order.visible(source))
        order.cancel()
        order.cancel()
        assertEquals(source, order.visible(source))
        assertFalse(order.busy)
        assertNull(order.complete(source))
    }

    @Test fun unchangedOrReturnedDraftDoesNotCommit() {
        val order = LibraryOrder()
        assertTrue(order.begin(source))
        assertNull(order.complete(source))
        assertTrue(order.begin(source))
        order.move("a", "c")
        order.move("a", "b")
        assertNull(order.complete(source))
        assertFalse(order.busy)
    }

    @Test fun completedPreviewSurvivesAbortAndWaitsForAcknowledgementAndSource() {
        val order = LibraryOrder()
        order.begin(source)
        order.move("a", "c")
        assertEquals(moved, order.complete(source))
        order.cancel()
        assertEquals(moved, order.visible(source))
        assertTrue(order.busy)
        assertNull(order.complete(source))
        assertFalse(order.begin(source))
        assertFalse(order.stage(listOf("c", "b", "a"), source))
        order.acknowledge(true)
        order.reconcile(source)
        assertTrue(order.busy)
        assertEquals(moved, order.visible(source))
        order.reconcile(moved)
        assertFalse(order.busy)
        assertEquals(moved, order.visible(moved))
        assertTrue(order.begin(moved))
    }

    @Test fun sourceEmissionBeforeAcknowledgementDoesNotReleaseBusy() {
        val order = LibraryOrder()
        assertTrue(order.stage(moved, source))
        order.reconcile(moved)
        assertTrue(order.busy)
        order.acknowledge(true)
        order.reconcile(moved)
        assertFalse(order.busy)
    }

    @Test fun failureDropsPreviewAndAllowsRetryWithoutSourceEmission() {
        val order = LibraryOrder()
        order.stage(moved, source)
        order.cancel()
        order.acknowledge(false)
        assertFalse(order.busy)
        assertEquals(source, order.visible(source))
        assertTrue(order.begin(source))
        order.move("c", "a")
        assertEquals(listOf("c", "a", "b"), order.complete(source))
    }

    @Test fun concurrentSourceChangesWinOverDraft() {
        listOf(source + "d", listOf("a", "c"), listOf("b", "a", "c")).forEach { changed ->
            val order = LibraryOrder()
            order.begin(source)
            order.move("a", "c")
            assertNull(order.complete(changed))
            assertEquals(changed, order.visible(changed))
            assertFalse(order.busy)
        }
    }

    @Test fun acknowledgementComparesOnlySubmittedSlotsAndReleasesPreviewPermanently() {
        val order = LibraryOrder()
        order.stage(listOf("c", "a"), listOf("a", "c"))
        order.acknowledge(true)
        order.reconcile(listOf("a", "unrelated", "c"))
        assertTrue(order.busy)
        order.reconcile(listOf("c", "unrelated", "a", "new"))
        assertFalse(order.busy)
        assertEquals(source, order.visible(source))
    }

    @Test fun pendingPreviewReconcilesMembershipChanges() {
        val order = LibraryOrder()
        order.stage(moved, source)
        assertEquals(listOf("c", "a", "d"), order.visible(listOf("a", "c", "d")))
        order.acknowledge(true)
        order.reconcile(listOf("c", "d", "a"))
        assertFalse(order.busy)
        assertEquals(listOf("c", "d", "a"), order.visible(listOf("c", "d", "a")))
    }

    @Test fun invalidStageDoesNotBecomeBusy() {
        val order = LibraryOrder()
        assertFalse(order.stage(source, source))
        assertFalse(order.stage(listOf("a", "b"), source))
        assertFalse(order.stage(listOf("a", "b", "d"), source))
        assertFalse(order.stage(listOf("a", "a", "b", "c"), source))
        assertFalse(order.busy)
    }

    @Test fun groupNameValidationTrimsAndEnforcesBoundary() {
        assertFalse(validGroupName(""))
        assertFalse(validGroupName(" \n\t "))
        assertTrue(validGroupName("  海边来信  "))
        assertTrue(validGroupName("字".repeat(80)))
        assertTrue(validGroupName(" ${"字".repeat(80)} "))
        assertFalse(validGroupName("字".repeat(81)))
    }
}
