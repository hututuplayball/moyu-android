package com.moyu.reader.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.moyu.reader.pdf.PageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Translation is measured from the top/left, not from the center of the content. */
fun boundedTranslation(value: Float, viewport: Int, scale: Float): Float =
    value.coerceIn(min(0f, viewport * (1f - scale)), 0f)

/** A fitted horizontal page may have letterboxing: don't let panning strand it offscreen. */
fun boundedFittedTranslation(value: Float, fittedSize: Float, viewport: Int, scale: Float): Float {
    if (scale <= 1f) return 0f
    val fitted = fittedSize.coerceIn(0f, viewport.toFloat())
    val pageSize = fitted * scale
    val inset = (viewport - fitted) * scale / 2f
    return if (pageSize <= viewport) (viewport - pageSize) / 2f - inset
    else value.coerceIn(viewport - inset - pageSize, -inset)
}

fun anchoredTranslation(old: Float, centroid: Float, ratio: Float, delta: Float): Float =
    old * ratio + centroid * (1f - ratio) + delta

/** A negative result is intentional: LazyList normalizes it into the preceding item. */
fun anchoredScrollOffset(old: Float, centroid: Float, ratio: Float, delta: Float): Float =
    (old + centroid) * ratio - centroid - delta

fun normalizedPageOffset(actual: Int, zoom: Float): Int =
    (actual / zoom).roundToInt().coerceAtLeast(0)

data class TileViewport(
    val fullWidth: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val screenLeft: Int,
    val screenTop: Int,
    val screenWidth: Int,
    val screenHeight: Int,
)

/** Page rectangle and viewport use the same coordinates. No whole zoomed page is allocated. */
fun visiblePageTile(pageLeft: Float, pageTop: Float, pageWidth: Float, pageHeight: Float, viewportWidth: Int, viewportHeight: Int): TileViewport? {
    if (pageWidth <= 0 || pageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return null
    val x = max(0f, pageLeft)
    val y = max(0f, pageTop)
    val width = min(viewportWidth.toFloat(), pageLeft + pageWidth) - x
    val height = min(viewportHeight.toFloat(), pageTop + pageHeight) - y
    if (width <= 0 || height <= 0) return null
    val quality = min(1f, min(2048f / max(width, height), sqrt(1_500_000f / (width * height))))
    // Derive all raster coordinates from the SAME integer fullWidth used by PdfRenderer.
    val fullWidth = floor(pageWidth * quality).toInt().coerceAtLeast(1)
    val rasterScale = fullWidth / pageWidth
    val left = floor((x - pageLeft) * rasterScale).toInt().coerceIn(0, fullWidth - 1)
    val top = floor((y - pageTop) * rasterScale).toInt().coerceAtLeast(0)
    val rasterWidth = floor(width * rasterScale).toInt().coerceIn(1, min(2048, fullWidth - left))
    val rasterHeight = floor(height * rasterScale).toInt().coerceIn(1, min(2048, 1_500_000 / rasterWidth))
    return TileViewport(
        fullWidth, left, top, rasterWidth, rasterHeight,
        (pageLeft + left / rasterScale).roundToInt(), (pageTop + top / rasterScale).roundToInt(),
        (rasterWidth / rasterScale).roundToInt().coerceAtLeast(1),
        (rasterHeight / rasterScale).roundToInt().coerceAtLeast(1),
    )
}

/** Even a reset from 5x can normalize backwards by less than one BASE viewport height. */
internal suspend fun preloadZoomPredecessors(
    firstPage: Int,
    viewport: IntSize,
    cachedSize: (Int) -> PageSize?,
    loadSize: suspend (Int) -> PageSize,
) {
    var remaining = viewport.height.toDouble()
    var page = firstPage - 1
    while (page >= 0 && remaining > 0) {
        val size = cachedSize(page) ?: loadSize(page)
        // Match page layout rounding. Ignoring inter-page gaps only overestimates what we need.
        remaining -= (viewport.width.toDouble() * size.height / size.width).roundToInt().coerceAtLeast(1)
        page--
    }
}

/** A keyed, live gate: a first-visible change must not reuse the previous page's readiness. */
@Composable
internal fun PreloadZoomMetadata(
    state: ReaderViewportState,
    cacheKey: Any,
    cachedSize: (Int) -> PageSize?,
    loadSize: suspend (Int) -> PageSize,
) {
    var ready by remember(state, cacheKey) { mutableStateOf<Pair<Int, IntSize>?>(null) }
    val cached by rememberUpdatedState(cachedSize)
    val load by rememberUpdatedState(loadSize)
    state.canZoom = { !state.vertical || ready == (state.metadataPage to state.viewport) }
    val key = state.metadataPage to state.viewport
    LaunchedEffect(state, cacheKey, key) {
        if (!state.vertical || key.second.width <= 0 || key.second.height <= 0) return@LaunchedEffect
        try {
            preloadZoomPredecessors(key.first, key.second, cached, load)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Only zoom waits for metadata; reading and page retry remain available. */ }
        // A damaged neighbour must not disable zoom for the rest of the book.
        ready = key
        state.completePendingReset()
    }
}

@Stable
internal class ReaderViewportState(val list: LazyListState, val pager: PagerState, val vertical: Boolean) {
    // Tests without PDF metadata remain ready by default. Production supplies a live keyed predicate.
    var canZoom: () -> Boolean = { true }
    var layoutDirection = LayoutDirection.Ltr
    private var ownedPagerPage: Int? = null
    private var pendingReset: Offset? = null
    var zoom by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(IntSize.Zero)
        private set
    var bounds by mutableStateOf(Rect.Zero)
    var measuredZoom by mutableFloatStateOf(1f)
        private set
    // Multiple pointer samples may arrive before remeasurement. Compose their anchors, not stale layout offsets.
    private var pendingAnchor by mutableStateOf<Pair<Int, Float>?>(null)
    val hasPendingAnchor: Boolean get() = pendingAnchor != null
    val metadataPage: Int get() = pendingAnchor?.first ?: list.firstVisibleItemIndex
    private val readyPages = mutableStateMapOf<Int, Rect>()
    var fling: Job? = null

    fun pageReady(index: Int, bounds: Rect?) {
        if (bounds == null) readyPages.remove(index) else readyPages[index] = bounds
    }

    fun pageFraction(at: Offset): Float? {
        val point = at + bounds.topLeft
        val page = readyPages.values.firstOrNull { it.contains(point) } ?: return null
        return (point.x - page.left) / page.width
    }

    fun canStart(at: Offset): Boolean = if (vertical) pageFraction(at) != null
        else readyPages.containsKey(pager.currentPage) // Include letterboxing, but not loading/error pages.

    private fun clampPan(value: Offset, scale: Float, size: IntSize = viewport): Offset {
        if (vertical) return Offset(boundedTranslation(value.x, size.width, scale), 0f)
        val page = readyPages[pager.currentPage]
        if (page != null && page.width > 0 && page.height > 0) {
            // Only the aspect ratio matters; it is stable even before the latest pinch is laid out.
            val fit = min(size.width / page.width, size.height / page.height)
            return Offset(boundedFittedTranslation(value.x, page.width * fit, size.width, scale),
                boundedFittedTranslation(value.y, page.height * fit, size.height, scale))
        }
        return Offset(boundedTranslation(value.x, size.width, scale), boundedTranslation(value.y, size.height, scale))
    }

    private fun anchor(ratio: Float, centroid: Float, delta: Float) {
        val (index, offset) = pendingAnchor ?: (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset.toFloat())
        val next = anchoredScrollOffset(offset, centroid, ratio, delta)
        pendingAnchor = index to next
        list.requestScrollToItem(index, next.roundToInt())
    }

    fun takeZoomOwnership() {
        if (vertical || ownedPagerPage != null) return
        val page = pager.currentPage
        val info = pager.layoutInfo
        val reversed = info.reverseLayout != (layoutDirection == LayoutDirection.Rtl)
        // Pager offset is in its logical scroll direction; pan is in physical screen coordinates.
        val shift = pager.currentPageOffsetFraction * (info.pageSize + info.pageSpacing) * if (reversed) 1f else -1f
        pan += Offset(shift, 0f)
        ownedPagerPage = page
        pager.requestScrollToPage(page, 0f)
    }

    fun finishZoomGesture() {
        if (!vertical && zoom <= 1f && ownedPagerPage != null) {
            pager.requestScrollToPage(ownedPagerPage!!, 0f)
            ownedPagerPage = null
            pan = Offset.Zero
        }
    }

    fun transform(centroid: Offset, delta: Offset, factor: Float) {
        if (!canZoom()) return
        takeZoomOwnership()
        val next = (zoom * factor).coerceIn(1f, 5f)
        val ratio = next / zoom
        if (vertical) anchor(ratio, centroid.y, delta.y)
        pan = clampPan(Offset(
            anchoredTranslation(pan.x, centroid.x, ratio, delta.x),
            anchoredTranslation(pan.y, centroid.y, ratio, delta.y),
        ), next)
        zoom = next
        if (next == 1f) finishZoomGesture()
    }

    fun drag(delta: Offset) {
        pan = clampPan(pan + delta, zoom)
        if (vertical) {
            if (hasPendingAnchor) anchor(1f, 0f, delta.y) else list.dispatchRawDelta(-delta.y)
        }
    }

    fun toggleZoom(at: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        fling?.cancel()
        if (!canZoom()) {
            // Back/menu reset is not lost while dimensions for a newly scrolled-to page arrive.
            if (zoom > 1.01f) pendingReset = at
            return
        }
        pendingReset = null
        transform(at, Offset.Zero, (if (zoom > 1.01f) 1f else 2.5f) / zoom)
    }

    fun completePendingReset() {
        if (canZoom()) pendingReset?.let { toggleZoom(it) }
    }

    /** Replace, rather than follow, any queued pinch anchor. Used by jumps, mode changes and restore. */
    fun resetForJump(page: Int, scrollOffset: Int = 0) {
        fling?.cancel()
        pendingReset = null
        ownedPagerPage = null
        zoom = 1f
        pan = Offset.Zero
        val offset = scrollOffset.coerceAtLeast(0)
        // This also gates position reports until the requested page has been measured. Without
        // the gate a horizontal pager could report its old settled page before the jump lands.
        pendingAnchor = page to offset.toFloat()
        list.requestScrollToItem(page, offset)
        if (!vertical) pager.requestScrollToPage(page, 0f)
    }

    fun resize(size: IntSize) {
        if (size == viewport || size.width == 0 || size.height == 0) return
        if (viewport.width > 0) {
            if (vertical) anchor(size.width.toFloat() / viewport.width, 0f, 0f)
            pan = clampPan(Offset(pan.x * size.width / viewport.width, pan.y * size.height / viewport.height), zoom, size)
        }
        viewport = size
    }

    fun measured(scale: Float) {
        measuredZoom = scale
        // Also observes the request in the parent measure scope, including pan-only pinch frames.
        if (pendingAnchor != null) pendingAnchor = null
    }
}

/** The content is really remeasured at zoomed width; only its viewport is clipped, never a giant raster layer. */
@Composable
internal fun ReaderViewport(
    state: ReaderViewportState,
    onTap: (Offset) -> Unit,
    onGesture: () -> Unit = {},
    content: @Composable (IntSize, Rect, Float, Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val flingBehavior = ScrollableDefaults.flingBehavior()
    state.layoutDirection = LocalLayoutDirection.current
    val tap by rememberUpdatedState(onTap)
    val gesture by rememberUpdatedState(onGesture)
    val stop: () -> Unit = {
        state.fling?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (state.vertical) state.list.stopScroll(MutatePriority.UserInput)
            else state.pager.stopScroll(MutatePriority.UserInput)
        }
    }
    val stopCurrent by rememberUpdatedState(stop)
    val flingCurrent by rememberUpdatedState<(Float) -> Unit>({ velocity ->
        if (state.vertical) {
            state.fling = scope.launch {
                state.list.scroll {
                    with(flingBehavior) { performFling(-velocity) }
                }
            }
        }
    })
    Box(Modifier.fillMaxSize().clipToBounds().testTag("reader-viewport")
        .onGloballyPositioned {
            state.bounds = Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
        }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val allowed = state.canStart(down.position)
                if (allowed) stopCurrent()
                var owned = false
                var count = 1
                var tracked = down.id
                var distance = Offset.Zero
                var singleMoved = false
                val velocity = VelocityTracker()
                velocity.addPosition(down.uptimeMillis, down.position)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val active = event.changes.filter { it.pressed }
                    if (allowed) {
                        if (active.size >= 2 && state.canZoom()) {
                            if (!owned) { stopCurrent(); state.takeZoomOwnership(); gesture() }
                            owned = true
                            singleMoved = false
                            velocity.resetTracking()
                            if (count >= 2) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val factor = event.calculateZoom()
                                if (centroid.x.isFinite() && centroid.y.isFinite() && factor.isFinite()) {
                                    state.transform(centroid, event.calculatePan(), factor)
                                }
                            }
                        } else if (active.size == 1) {
                            val pointer = active.single()
                            if (count != 1 || tracked != pointer.id) {
                                // A lifted finger is not a pan or a fling sample.
                                distance = Offset.Zero
                                singleMoved = false
                                velocity.resetTracking()
                                tracked = pointer.id
                            } else {
                                var delta = pointer.position - pointer.previousPosition
                                distance += delta
                                if (!owned && state.zoom > 1.01f && distance.getDistance() > viewConfiguration.touchSlop) {
                                    owned = true
                                    stopCurrent()
                                    gesture()
                                    delta = distance * (1f - viewConfiguration.touchSlop / distance.getDistance())
                                }
                                if (owned && delta != Offset.Zero) {
                                    state.drag(delta)
                                    singleMoved = true
                                }
                            }
                            velocity.addPosition(pointer.uptimeMillis, pointer.position)
                        } else if (active.isEmpty() && owned && singleMoved && count == 1) {
                            val up = event.changes.firstOrNull { it.id == tracked }
                            if (up != null) velocity.addPosition(up.uptimeMillis, up.position)
                            flingCurrent(velocity.calculateVelocity().y.coerceIn(-viewConfiguration.maximumFlingVelocity, viewConfiguration.maximumFlingVelocity))
                        }
                        if (owned) event.changes.forEach { it.consume() }
                    }
                    count = active.size
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(state) {
            // No page-level detector competes for the second tap; descendants such as Retry retain priority.
            detectTapGestures(onTap = { if (state.canStart(it)) tap(it) }, onDoubleTap = {
                if (state.canStart(it)) { stopCurrent(); gesture(); state.toggleZoom(it) }
            })
        },
    ) {
        val expanded = Modifier.layout { measurable, constraints ->
            val base = IntSize(constraints.maxWidth, constraints.maxHeight)
            state.resize(base)
            val scale = state.zoom
            val width = (base.width * scale).roundToInt().coerceAtLeast(1)
            val height = if (state.vertical) base.height else (base.height * scale).roundToInt().coerceAtLeast(1)
            val child = measurable.measure(Constraints.fixed(width, height))
            state.measured(scale)
            layout(base.width, base.height) { child.place(state.pan.x.roundToInt(), state.pan.y.roundToInt()) }
        }
        content(state.viewport, state.bounds, state.zoom, expanded)
    }
}
