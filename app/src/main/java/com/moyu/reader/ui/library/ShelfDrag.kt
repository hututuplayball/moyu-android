package com.moyu.reader.ui.library

import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.moyu.reader.ui.LibraryOrder

// Long press, drag and release produce one trailing click; ignore it for a moment.
internal const val TAP_BLOCK_MS = 260L

// Pointer travel is kept outside the drag descriptor so a moving finger only redraws the lifted
// card instead of recomposing the whole shelf.
private const val DRAG_REPEAT_MS = 90L

/**
 * One cell of the shelf grid, in the coordinate space the pointer arrives in once the grid's own
 * content padding is removed.
 */
internal class GridItemHit(
    val key: String,
    val id: String,
    val left: Float,
    val top: Float,
    val width: Int,
    val height: Int,
)

/** Every laid-out cell that can be dragged, keyed the way the grid keys its items. */
internal fun LazyGridState.draggableHits(): List<GridItemHit> = buildList {
    layoutInfo.visibleItemsInfo.forEach { item ->
        val key = item.key.toString()
        val id = when {
            key.startsWith("book:") -> key.removePrefix("book:")
            key.startsWith("group:") -> key.removePrefix("group:")
            key == "ungrouped" -> key
            else -> return@forEach
        }
        add(GridItemHit(key, id, item.offset.x.toFloat(), item.offset.y.toFloat(), item.size.width, item.size.height))
    }
}

/**
 * The cell under [position], or null. Lazy-grid item offsets exclude content padding while pointer
 * coordinates do not, which is why the padding is subtracted here rather than at the call site.
 */
internal fun List<GridItemHit>.hitTest(
    position: Offset,
    sidePadding: Float,
    beforeContentPadding: Float,
    matches: (String) -> Boolean,
): GridItemHit? {
    val content = position - Offset(sidePadding, beforeContentPadding)
    return firstOrNull { hit ->
        matches(hit.key) &&
            content.x >= hit.left && content.x <= hit.left + hit.width &&
            content.y >= hit.top && content.y <= hit.top + hit.height
    }
}

/**
 * The drag state machine of the shelf: which cover (or group card) is lifted, where the finger is,
 * and which cell it is currently hovering.
 *
 * The root grid only carries group cards and the shelf grids only carry books, so one controller
 * and one lifted item cover both gestures; [Drag.groups] is what tells the caller which order to
 * persist. Hit testing is a pure function over [GridItemHit] so the swap rules — including the
 * repeat guard that keeps a fast back-and-forth from stuttering — can be tested without a device.
 */
internal class ShelfDragController(private val clock: () -> Long = { SystemClock.uptimeMillis() }) {
    class Drag(
        val id: String,
        val grab: Offset,
        val width: Int,
        val height: Int,
        val scope: String,
        val groups: Boolean,
        val order: LibraryOrder,
    )

    var drag by mutableStateOf<Drag?>(null)
        private set

    var pointer by mutableStateOf(Offset.Zero)
        private set

    /** Set to [Long.MAX_VALUE] while a drag owns the pointer, so the trailing click is swallowed. */
    private var tapBlockedUntil by mutableLongStateOf(0L)
    private var lastTarget: String? = null
    private var lastMoveAt = 0L

    fun tapAllowed(): Boolean = clock() >= tapBlockedUntil

    fun blockTaps() {
        tapBlockedUntil = clock() + TAP_BLOCK_MS
    }

    fun begin(
        position: Offset,
        hit: GridItemHit?,
        scope: String,
        groups: Boolean,
        source: List<String>,
        order: LibraryOrder,
    ): Boolean {
        if (hit == null || !order.begin(source)) return false
        tapBlockedUntil = Long.MAX_VALUE
        lastTarget = null
        lastMoveAt = 0L
        pointer = position
        drag = Drag(hit.id, position - Offset(hit.left, hit.top), hit.width, hit.height, scope, groups, order)
        return true
    }

    fun moveTo(position: Offset) {
        pointer = position
    }

    /**
     * Offers [targetId] to the lifted item. [apply] performs the actual swap and reports whether it
     * moved anything, which is what the repeat guard keys on.
     */
    fun hover(targetId: String?, apply: (String) -> Boolean) {
        val current = drag ?: return
        if (targetId == null) return
        if (targetId == current.id) {
            // The dragged cover now owns this cell, so the next hover may swap back.
            lastTarget = null
            return
        }
        val now = clock()
        // A cell can still report the previous item for one layout pass after a swap. Only let the
        // same cell move again once the shelf has settled, which keeps quick back-and-forth smooth.
        if (targetId == lastTarget && now - lastMoveAt < DRAG_REPEAT_MS) return
        if (apply(targetId)) {
            lastTarget = targetId
            lastMoveAt = now
        }
    }

    /**
     * Ends the gesture. Returns the lifted item and the order the reader dropped it into, or null
     * when nothing moved. The caller persists the result and knows which list it belongs to from
     * [Drag.groups].
     */
    fun finish(source: List<String>): Pair<Drag, List<String>>? {
        val current = drag ?: return null
        drag = null
        lastTarget = null
        blockTaps()
        val result = current.order.complete(source) ?: return null
        return current to result
    }

    /** Drops the gesture without saving, and swallows the click the release would otherwise send. */
    fun cancel() {
        val active = drag != null
        drag?.order?.cancel()
        drag = null
        pointer = Offset.Zero
        lastTarget = null
        lastMoveAt = 0L
        // Only a real drag leaves a trailing click to swallow.
        if (active) blockTaps()
    }
}
