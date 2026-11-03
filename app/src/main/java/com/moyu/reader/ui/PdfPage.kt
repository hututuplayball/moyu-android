package com.moyu.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.moyu.reader.page.PageSource
import com.moyu.reader.pdf.PageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

private data class PageRender(val bitmap: Bitmap? = null, val error: Boolean = false)
private data class TileFrame(val viewport: TileViewport, val pageSize: IntSize, val bitmap: Bitmap)

/** A neighbour is only needed for the next turn: let the page being read paint its bitmap first. */
private const val NEIGHBOUR_DELAY_MS = 120L

// MeasureScope.layout supports 24-bit dimensions, unlike the much smaller packed Constraints pair.
internal const val MAX_PAGE_LAYOUT_DIMENSION = 0xFFFFFF

/** One render width for the page and for its prefetch, so the cache key is byte-for-byte identical. */
internal fun pageRenderWidth(size: PageSize, viewport: IntSize, fit: Boolean): Int {
    val scale = if (fit) {
        min(viewport.width.toDouble() / size.width, viewport.height.toDouble() / size.height)
    } else {
        viewport.width.toDouble() / size.width
    }
    return (size.width.toDouble() * scale).roundToInt().coerceAtLeast(1)
}

internal fun pageDisplaySize(page: PageSize, width: Int, height: Int, fit: Boolean): IntSize? {
    if (page.width <= 0 || page.height <= 0) return null
    val scale = if (fit) min(width.toDouble() / page.width, height.toDouble() / page.height)
        else width.toDouble() / page.width
    val w = (page.width * scale).coerceAtLeast(1.0)
    val h = (page.height * scale).coerceAtLeast(1.0)
    if (!w.isFinite() || !h.isFinite() || w > MAX_PAGE_LAYOUT_DIMENSION || h > MAX_PAGE_LAYOUT_DIMENSION) return null
    return IntSize(w.roundToInt(), h.roundToInt())
}

/** The page is a drawing surface, not a giant fixed-size child or raster layer. */
@Composable
internal fun PdfPageLayout(
    displaySize: IntSize,
    baseViewport: IntSize,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Layout(content = content, modifier = modifier) { measurables, _ ->
        // Only loading/retry controls are measured. Never pack the logical strip height into Constraints.
        val controlWidth = minOf(displaySize.width, baseViewport.width.coerceAtLeast(1), 4096)
        val controlHeight = minOf(displaySize.height, baseViewport.height.coerceAtLeast(1), 4096)
        val controls = measurables.map { it.measure(Constraints(maxWidth = controlWidth, maxHeight = controlHeight)) }
        layout(displaySize.width, displaySize.height) {
            controls.forEach { it.place((displaySize.width - it.width) / 2, (controlHeight - it.height) / 2) }
        }
    }
}

@Composable
fun PdfPage(
    session: PageSource,
    index: Int,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    initialSize: PageSize? = null,
    baseViewport: IntSize,
    viewportBounds: Rect,
    zoom: Float,
    /** False once the page is far enough from the viewport: its bitmap is dropped, not kept for the book. */
    keep: Boolean = true,
    /** True while the shelf is moving; full pages then wait until the fling ends. */
    scrolling: Boolean = false,
    /** Off-screen neighbours pre-render only once the shelf is still. */
    visible: Boolean = true,
    /** Always composed next to the reader, but it yields the renderer to the page being read. */
    defer: Boolean = false,
    /** Text pages carry their reading theme; comics keep the classic white sheet. */
    pageBackground: Color = Color.White,
    onPageReady: (Int, Rect?) -> Unit,
) {
    var retry by remember(session, index) { mutableIntStateOf(0) }
    var size by remember(session, index, retry) { mutableStateOf(session.cachedPageSize(index) ?: initialSize) }
    var sizeError by remember(session, index, retry) { mutableStateOf(false) }
    LaunchedEffect(session, index, retry) {
        try { size = session.pageSize(index) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { sizeError = true }
    }
    val ready by rememberUpdatedState(onPageReady)
    DisposableEffect(session, index) { onDispose { ready(index, null) } }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val page = size ?: PageSize(600, 850)
        // This key never follows a pinch frame. Page sources also cap pathological long-page base bitmaps.
        val renderWidth = pageRenderWidth(page, baseViewport, fit)
        val displaySize = pageDisplaySize(page, constraints.maxWidth, constraints.maxHeight, fit)
        // Text chapter windows mutate their page list in place; this key drops every bitmap
        // captured from the previous window before it can be shown over the new pages.
        val renderVersion = session.renderVersion
        // A page prefetched at the exact viewport width is published before the spinner ever shows.
        var render by remember(session, index, renderWidth, retry, keep, pageBackground, renderVersion) {
            mutableStateOf(
                if (keep) session.cachedRender(index, renderWidth)?.let { PageRender(it) } else null,
            )
        }
        // A fast fling would upload a full-page texture for every page it passes. A half-scale
        // preview is readable while still costing a quarter of that memory.
        var preview by remember(session, index, pageBackground, renderVersion) { mutableStateOf<Bitmap?>(null) }
        val previewWidth = (renderWidth / 2).coerceAtLeast(180)
        // Flowing text draws cheaply, so it never needs the half-scale motion preview that
        // elsewhere keeps a fast fling from uploading a full-page texture for every page.
        val sharp = session.sharpWhileScrolling
        LaunchedEffect(session, index, renderWidth, retry, size, displaySize == null, keep, scrolling, visible, defer, pageBackground, renderVersion) {
            if (!keep) {
                // LazyColumn can hold a page just outside the viewport; do not let that keep a full bitmap alive.
                render = null
                preview = null
                return@LaunchedEffect
            }
            if (size != null && baseViewport.width > 0 && displaySize != null) {
                if (scrolling) {
                    // A prefetched page must appear sharp during the swipe, not only after it settles.
                    val cached = if (visible && render?.bitmap == null) session.cachedRender(index, renderWidth) else null
                    if (cached != null) {
                        render = PageRender(cached)
                        preview = null
                    } else if (visible && render?.bitmap == null) {
                        if (sharp) {
                            render = try { PageRender(session.render(index, renderWidth)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { PageRender(error = true) }
                        } else if (preview == null) {
                            delay(180) // Pages that fly past the viewport never get here.
                            preview = try { session.render(index, previewWidth, cache = false) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        }
                    }
                } else {
                    // A neighbour is ready for the next turn either way; the page on screen goes first.
                    if (defer && !sharp) delay(NEIGHBOUR_DELAY_MS)
                    render = try { PageRender(session.render(index, renderWidth)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { PageRender(error = true) }
                    preview = null
                }
            }
        }
        var pageBounds by remember(session, index) { mutableStateOf(Rect.Zero) }
        val shown = render?.bitmap ?: preview
        LaunchedEffect(pageBounds, shown, displaySize) {
            ready(index, pageBounds.takeIf { displaySize != null && shown != null && it.width > 0 && it.height > 0 })
        }
        val request = if (keep && displaySize != null && zoom > 1.01f && render?.bitmap != null) visiblePageTile(
            pageBounds.left - viewportBounds.left, pageBounds.top - viewportBounds.top,
            pageBounds.width, pageBounds.height, viewportBounds.width.roundToInt(), viewportBounds.height.roundToInt(),
        ) else null
        // Only a currently composed page's visible rectangle owns a tile, with no accumulating tile cache.
        val tilePageSize = IntSize(pageBounds.width.roundToInt(), pageBounds.height.roundToInt())
        var tile by remember(session, index, request, tilePageSize, retry, pageBackground, renderVersion) { mutableStateOf<TileFrame?>(null) }
        LaunchedEffect(session, index, request, tilePageSize, retry, pageBackground, renderVersion) {
            if (request == null) {
                // Zoom ended or the page left the window; a tile is only useful at its current viewport.
                tile = null
                return@LaunchedEffect
            }
            delay(100)
            try {
                tile = TileFrame(request, tilePageSize, session.renderTile(index, request.fullWidth, request.left, request.top, request.width, request.height))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { tile = null } // The base page remains readable if refinement fails.
        }
        // Unsupported dimensions are an explicit error, never a truncated/squashed page or a reduced zoom.
        PdfPageLayout(
            displaySize ?: IntSize(constraints.maxWidth, baseViewport.height.coerceAtLeast(1)), baseViewport,
            Modifier.background(pageBackground)
                .semantics { contentDescription = "第 ${index + 1} 页"; role = Role.Image }
                .onGloballyPositioned {
                    // boundsInRoot is clipped by LazyColumn; retain the actual translated page rectangle instead.
                    pageBounds = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                }
                .drawBehind {
                    if (displaySize == null) return@drawBehind
                    shown?.let { bitmap ->
                        drawImage(bitmap.asImageBitmap(), srcSize = IntSize(bitmap.width, bitmap.height),
                            dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()))
                    }
                    val frame = tile
                    // Layout can advance ahead of onGloballyPositioned: never flash a tile at the old scale.
                    if (frame != null && frame.viewport == request &&
                        this.size.width.roundToInt() == frame.pageSize.width && this.size.height.roundToInt() == frame.pageSize.height) {
                        drawImage(frame.bitmap.asImageBitmap(), srcSize = IntSize(frame.bitmap.width, frame.bitmap.height),
                            dstOffset = IntOffset(
                                (frame.viewport.screenLeft + viewportBounds.left - pageBounds.left).roundToInt(),
                                (frame.viewport.screenTop + viewportBounds.top - pageBounds.top).roundToInt(),
                            ), dstSize = IntSize(frame.viewport.screenWidth, frame.viewport.screenHeight))
                    }
                },
        ) {
            val onError = if (pageBackground.luminance() > .5f) Color.DarkGray else Color.LightGray
            when {
                displaySize == null -> Text("页面尺寸异常，暂时无法显示", color = onError)
                shown != null -> Unit
                sizeError || render?.error == true -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这页暂时无法显示", color = onError)
                    TextButton(onClick = { retry++ }) { Text("重试", color = onError) }
                }
                else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}
