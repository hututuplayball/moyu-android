package com.moyu.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.pdf.PageSize
import kotlinx.coroutines.flow.distinctUntilChanged

/** One page as it sits in the reader's viewport: the rectangle the text layer must paint. */
internal data class TextPageSlot(
    val index: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * Reserves the exact rectangle a text page occupies in the scrolling surface. The pages are
 * painted by [TextPageLayer], not here: one page is a vector drawing, so it needs no view, no
 * bitmap and no layer of its own.
 */
@Composable
internal fun TextPagePlaceholder(
    source: TextPageSource,
    index: Int,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val page = source.cachedPageSize(index) ?: PageSize(600, 850)
        val displaySize = pageDisplaySize(page, constraints.maxWidth, constraints.maxHeight, fit)
        if (displaySize != null) {
            Layout(
                content = {},
                modifier = Modifier.semantics {
                    contentDescription = "第 ${index + 1} 页"
                    role = Role.Image
                },
            ) { _, _ -> layout(displaySize.width, displaySize.height) {} }
        }
    }
}

/**
 * Paints every text page that is currently in the viewport onto one native view.
 *
 * Pages are vector drawings, so a single drawing surface covers the whole reader: no page bitmap,
 * no per-page view, and nothing allocated for a page that is off screen. The scrolling surface
 * itself stays Compose; this layer only mirrors the exact rectangles it laid out, and repaints
 * when one of them moves.
 */
@Composable
internal fun TextPageLayer(
    source: TextPageSource,
    vertical: Boolean,
    listState: LazyListState,
    pagerState: PagerState,
    onPageReady: (Int, Rect?) -> Unit,
    modifier: Modifier = Modifier,
    /** The sentence being read aloud, drawn as a soft marker behind the text. */
    highlight: TextPageSource.Highlight? = null,
) {
    val ready by rememberUpdatedState(onPageReady)
    // Rebuilt page windows and new themes bump this; everything the layer already drew is stale.
    val version = source.renderVersion
    // These outlive the page source: a typesetting change swaps sources, but the drawing surface
    // and its place in the window stay the same. Re-keying them would leave the layer unable to
    // reach its own view (the factory only runs once) and freeze whatever it last painted.
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val boundsNow by rememberUpdatedState(bounds)
    val view = remember { mutableStateOf<TextPagesView?>(null) }
    DisposableEffect(source) {
        onDispose {
            // A stale rectangle would make taps open the wrong place; drop every page this layer
            // may have reported.
            for (page in 0 until source.pageCount) ready(page, null)
        }
    }
    AndroidView(
        factory = { context -> TextPagesView(context).also { view.value = it } },
        update = { layer ->
            layer.bind(source, version)
            layer.setHighlight(highlight)
        },
        modifier = modifier.onGloballyPositioned {
            bounds = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
        },
    )
    LaunchedEffect(source, vertical, listState, pagerState, version, bounds) {
        val shown = HashSet<Int>()
        snapshotFlow {
            if (vertical) {
                val viewport = listState.layoutInfo.viewportSize
                listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    slotFor(source, item.index, IntOffset(0, item.offset), IntSize(viewport.width, item.size), fit = false)
                }
            } else {
                val viewport = pagerState.layoutInfo.viewportSize
                val pageSize = IntSize(pagerState.layoutInfo.pageSize, viewport.height)
                pagerState.layoutInfo.visiblePagesInfo.mapNotNull { page ->
                    slotFor(source, page.index, IntOffset(page.offset, 0), pageSize, fit = true)
                }
            }
        }.distinctUntilChanged().collect { slots ->
            view.value?.setPages(slots)
            val area = boundsNow
            if (area.width <= 0f || area.height <= 0f) return@collect
            val visible = HashSet<Int>()
            for (slot in slots) {
                visible += slot.index
                ready(
                    slot.index,
                    Rect(
                        area.left + slot.left, area.top + slot.top,
                        area.left + slot.left + slot.width, area.top + slot.top + slot.height,
                    ),
                )
            }
            (shown - visible).forEach { ready(it, null) }
            shown.clear()
            shown += visible
        }
    }
}

/** The page rectangle inside a laid-out item, mirroring the placeholder's own centring. */
private fun slotFor(
    source: TextPageSource,
    index: Int,
    base: IntOffset,
    size: IntSize,
    fit: Boolean,
): TextPageSlot? {
    val page = source.cachedPageSize(index) ?: return null
    val display = pageDisplaySize(page, size.width, size.height, fit) ?: return null
    return TextPageSlot(
        index = index,
        left = base.x + (size.width - display.width) / 2,
        top = base.y + (size.height - display.height) / 2,
        width = display.width,
        height = display.height,
    )
}

/**
 * Draws the pages of a text book straight into the window canvas. A page is laid out once and
 * painted as vector text, so scrolling only moves it and never needs a bitmap.
 */
internal class TextPagesView(context: Context) : View(context) {
    private var source: TextPageSource? = null
    private var version = -1
    private var slots: List<TextPageSlot> = emptyList()
    private var highlight: TextPageSource.Highlight? = null

    init {
        // The reader owns taps, swipes and accessibility; this surface only draws.
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(source: TextPageSource, version: Int) {
        if (this.source === source && this.version == version) return
        this.source = source
        this.version = version
        invalidate()
    }

    fun setPages(value: List<TextPageSlot>) {
        if (slots == value) return
        slots = value
        invalidate()
    }

    fun setHighlight(value: TextPageSource.Highlight?) {
        if (highlight == value) return
        highlight = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val source = source ?: return
        for (slot in slots) {
            canvas.save()
            canvas.translate(slot.left.toFloat(), slot.top.toFloat())
            // Clip to the page so its background never spills into the gap between pages.
            canvas.clipRect(0f, 0f, slot.width.toFloat(), slot.height.toFloat())
            source.drawOn(canvas, slot.index, slot.width, highlight)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}
