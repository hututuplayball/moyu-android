package com.moyu.reader

import androidx.compose.ui.geometry.Offset
import com.moyu.reader.ui.LibraryOrder
import com.moyu.reader.ui.library.GridItemHit
import com.moyu.reader.ui.library.ShelfDragController
import com.moyu.reader.ui.library.TAP_BLOCK_MS
import com.moyu.reader.ui.library.hitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf's drag rules, tested without a grid.
 *
 * These used to live inside the shelf composable, where the only way to reach them was a Robolectric
 * UI test: the swap, the repeat guard and the trailing click were all invisible to plain JUnit.
 */
class ShelfDragTest {
    private var now = 1_000L
    private val clock: () -> Long = { now }

    private fun cell(key: String, id: String, left: Float, top: Float) =
        GridItemHit(key, id, left, top, width = 200, height = 280)

    @Test
    fun hitTestTakesTheGridsContentPaddingOffThePointer() {
        val hits = listOf(cell("book:a", "a", 0f, 0f))
        // The grid's content starts at (20, 12); this pointer is 10px into the first cell.
        assertNotNull(hits.hitTest(Offset(30f, 22f), sidePadding = 20f, beforeContentPadding = 12f) { it.startsWith("book:") })
        // The same pointer with the padding ignored lands outside the cell.
        assertNull(hits.hitTest(Offset(30f, 22f), sidePadding = 220f, beforeContentPadding = 12f) { it.startsWith("book:") })
    }

    @Test
    fun hitTestOnlyMatchesTheKindOfCellAskedFor() {
        val hits = listOf(cell("group:g", "g", 0f, 0f))
        val point = Offset(10f, 10f)
        assertNull(hits.hitTest(point, 0f, 0f) { it.startsWith("book:") })
        assertNotNull(hits.hitTest(point, 0f, 0f) { it.startsWith("group:") })
        // The ungrouped card is keyed by name, not by the group prefix.
        assertTrue(listOf(cell("ungrouped", "ungrouped", 0f, 0f))
            .hitTest(point, 0f, 0f) { it.startsWith("group:") || it == "ungrouped" } != null)
    }

    @Test
    fun aCellOutsideTheGridIsNotHit() {
        val hits = listOf(cell("book:a", "a", 0f, 0f))
        assertNull(hits.hitTest(Offset(500f, 10f), 0f, 0f) { it.startsWith("book:") })
    }

    @Test
    fun aDragThatMovedNothingReportsNoOrder() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("a", "b")
        assertTrue(controller.begin(Offset(10f, 10f), cell("book:a", "a", 0f, 0f), "scope", false, source, order))
        assertNull(controller.finish(source))
        assertNull(controller.drag)
    }

    @Test
    fun hoveringAnotherCellMovesTheLiftedItemThere() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("a", "b", "c")
        assertTrue(controller.begin(Offset(0f, 0f), cell("book:a", "a", 0f, 0f), "scope", false, source, order))
        val lifted = controller.drag!!
        controller.hover("c") { id -> lifted.order.move(lifted.id, id) }
        assertEquals(listOf("b", "c", "a"), order.visible(source))
        val result = controller.finish(source)
        assertNotNull(result)
        assertEquals(listOf("b", "c", "a"), result!!.second)
        assertFalse(result.first.groups)
    }

    @Test
    fun theSameCellCannotSwapAgainUntilTheShelfSettles() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("a", "b", "c")
        assertTrue(controller.begin(Offset(0f, 0f), cell("book:a", "a", 0f, 0f), "scope", false, source, order))
        val lifted = controller.drag!!
        controller.hover("b") { id -> lifted.order.move(lifted.id, id) }
        assertEquals(listOf("b", "a", "c"), order.visible(source))
        // A cell can still report the previous item for one layout pass after a swap.
        controller.hover("b") { id -> lifted.order.move(lifted.id, id) }
        assertEquals(listOf("b", "a", "c"), order.visible(source))
        // Once the shelf has settled, hovering it again is a real move.
        now += 90
        controller.hover("b") { id -> lifted.order.move(lifted.id, id) }
        assertEquals(listOf("a", "b", "c"), order.visible(source))
    }

    @Test
    fun hoveringTheLiftedItemItselfClearsTheRepeatGuard() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("a", "b")
        assertTrue(controller.begin(Offset(0f, 0f), cell("book:a", "a", 0f, 0f), "scope", false, source, order))
        val lifted = controller.drag!!
        controller.hover("b") { id -> lifted.order.move(lifted.id, id) }
        assertEquals(listOf("b", "a"), order.visible(source))
        // The dragged cover now owns the cell it swapped into, so the guard is released: hovering
        // "b" again swaps straight back, where the plain repeat guard would have kept "b" first.
        controller.hover("a") { id -> lifted.order.move(lifted.id, id) }
        controller.hover("b") { id -> lifted.order.move(lifted.id, id) }
        assertEquals(listOf("a", "b"), order.visible(source))
    }

    @Test
    fun aGroupDragReportsItselfAsAGroup() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("g1", "g2")
        assertTrue(controller.begin(Offset(0f, 0f), cell("group:g1", "g1", 0f, 0f), "scope", true, source, order))
        val lifted = controller.drag!!
        controller.hover("ungrouped") { id ->
            if (id == "ungrouped") lifted.order.moveToEnd(lifted.id) else false
        }
        assertEquals(listOf("g2", "g1"), order.visible(source))
        val result = controller.finish(source)
        assertNotNull(result)
        assertTrue(result!!.first.groups)
    }

    @Test
    fun aDragCannotStartWhileAnOrderIsStillBeingSaved() {
        val order = LibraryOrder()
        val source = listOf("a", "b")
        // A submitted reorder leaves the order busy until the repository acknowledges it.
        order.begin(source)
        order.move("a", "b")
        assertNotNull(order.complete(source))
        assertTrue(order.busy)
        val controller = ShelfDragController(clock)
        assertFalse(controller.begin(Offset(0f, 0f), cell("book:a", "a", 0f, 0f), "scope", false, source, order))
        assertNull(controller.drag)
    }

    @Test
    fun aCancelledDragSwallowsTheClickItsReleaseWouldSend() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("a", "b")
        assertTrue(controller.begin(Offset(0f, 0f), cell("book:a", "a", 0f, 0f), "scope", false, source, order))
        controller.cancel()
        assertNull(controller.drag)
        assertFalse(controller.tapAllowed())
        now += TAP_BLOCK_MS
        assertTrue(controller.tapAllowed())
    }

    @Test
    fun aDragInProgressBlocksTapsForAsLongAsItLasts() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("a", "b")
        assertTrue(controller.begin(Offset(0f, 0f), cell("book:a", "a", 0f, 0f), "scope", false, source, order))
        now += 10_000
        assertFalse(controller.tapAllowed())
        controller.cancel()
        now += TAP_BLOCK_MS
        assertTrue(controller.tapAllowed())
    }

    @Test
    fun theLiftedItemKeepsTheOffsetItWasGrabbedBy() {
        val order = LibraryOrder()
        val controller = ShelfDragController(clock)
        val source = listOf("a", "b")
        val hit = cell("book:a", "a", left = 40f, top = 100f)
        assertTrue(controller.begin(Offset(60f, 130f), hit, "scope", false, source, order))
        val lifted = controller.drag!!
        // The grab point inside the card decides where the card's top-left sits under the finger.
        assertEquals(20f, lifted.grab.x, 0.001f)
        assertEquals(30f, lifted.grab.y, 0.001f)
        assertEquals(200, lifted.width)
        assertEquals(280, lifted.height)
    }
}
