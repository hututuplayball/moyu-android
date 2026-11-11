package com.moyu.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntSize
import com.moyu.reader.page.PageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Pages to warm around [currentPage], nearest first. A swipe cancels the rest of the pass, so the
 * one or two turns a gesture can reach must be requested before the pages three turns away.
 * Concentric inside each distance: the previous page is the one a backward turn needs.
 */
internal fun prefetchPages(currentPage: Int, pageCount: Int, radius: Int): List<Int> {
    val pages = ArrayList<Int>(radius * 2)
    if (pageCount <= 0 || radius <= 0) return pages
    for (distance in 1..radius) {
        for (offset in intArrayOf(-distance, distance)) {
            val page = currentPage + offset
            if (page in 0 until pageCount) pages.add(page)
        }
    }
    return pages
}

/**
 * [prefetchPages] farthest first, so a cache touch pass ends on the neighbours. The render cache
 * evicts its least recently used entry, and this order is what keeps it from evicting the page a
 * backward turn needs while it makes room for a page three turns ahead.
 */
internal fun touchPages(currentPage: Int, pageCount: Int, radius: Int): List<Int> =
    prefetchPages(currentPage, pageCount, radius).asReversed()

/**
 * Warms the pages before and after the reader at the exact width [PdfPage] will request. It only
 * runs once the reader is still, so the visible page and the swipe in progress keep the renderer
 * to themselves. Nearest pages go first; the cache is deliberately larger than the radius.
 */
@Composable
internal fun PrefetchPages(
    session: PageSource,
    currentPage: Int,
    pageCount: Int,
    viewport: IntSize,
    mode: String,
    scrolling: Boolean,
    zoom: Float,
    radius: Int,
) {
    LaunchedEffect(session, currentPage, pageCount, viewport.width, viewport.height, mode, scrolling, zoom, radius) {
        if (scrolling || zoom > 1.01f || viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        // Flowing text draws sharp pages in PdfPage itself. Warming more pages than the two
        // composed neighbours would only evict them from the small full-page bitmap cache.
        if (session.sharpWhileScrolling) return@LaunchedEffect
        // Give the page the reader is looking at time to finish before spending IO on its neighbours.
        delay(220)
        val fit = mode == "horizontal"
        val widths = HashMap<Int, Int>()
        for (page in prefetchPages(currentPage, pageCount, radius)) {
            warmPage(session, page, viewport, fit, widths)
        }
        // The cache is large enough for the whole radius, but a page rendered later in a pass pushes
        // out whichever entry is oldest — and that used to be the page just left, so turning back
        // had to render it again mid-swipe. Touching the far pages first and the neighbours last
        // makes the neighbourhood the newest entries, so the far pages are evicted instead.
        for (page in touchPages(currentPage, pageCount, radius)) {
            val width = widths[page] ?: continue
            // Only a lookup on a hit; a page that failed to warm is left alone.
            session.cachedRender(page, width)
        }
    }
}

/** Renders [page] at the width the reader will ask for, so the cache key matches byte for byte. */
private suspend fun warmPage(
    session: PageSource,
    page: Int,
    viewport: IntSize,
    fit: Boolean,
    widths: MutableMap<Int, Int>,
) {
    if (page !in 0 until session.pageCount) return
    val size = try {
        session.pageSize(page)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return
    }
    val width = pageRenderWidth(size, viewport, fit)
    widths[page] = width
    try {
        session.render(page, width)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A damaged neighbour must not stop the other side from warming up.
    }
}
