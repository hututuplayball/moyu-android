package com.moyu.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.page.PageSource
import com.moyu.reader.pdf.PageSize
import com.moyu.reader.ui.PdfPage
import com.moyu.reader.ui.PrefetchPages
import com.moyu.reader.ui.ReaderViewport
import com.moyu.reader.ui.TextPageLayer
import com.moyu.reader.ui.TextPagePlaceholder

/**
 * The reading surface: one viewport that turns pages either way, with the pages themselves drawn by
 * the source that owns them.
 *
 * Flowing text is a single native layer behind the scrolling pages, so pinch and pan stay on one
 * canvas; PDF pages and comic scans are ordinary composables whose rasters come from the page
 * cache. Both keep the same page index, so switching modes never loses the place.
 */
@Composable
internal fun ReaderCanvas(
    session: PageSource,
    state: ReaderPageState,
    textSource: TextPageSource?,
    restoredPage: Int,
    restoredSize: PageSize,
    keepPages: IntRange,
    visiblePages: IntRange,
    scrolling: Boolean,
    pdfTextView: Boolean,
    pageBackground: Color,
    background: Color,
    prefetchRadius: Int,
    rightToLeft: Boolean,
    highlight: TextPageSource.Highlight?,
    onGesture: () -> Unit,
    onTap: (Offset) -> Unit,
) {
    val viewport = state.viewport
    val mode = state.mode
    Box(Modifier.fillMaxSize().background(background)) {
        ReaderViewport(viewport, onTap = onTap, onGesture = onGesture) { baseSize, bounds, zoom, expanded ->
            PrefetchPages(session, state.currentPage, state.pageCount, baseSize, mode, scrolling, zoom, prefetchRadius)
            // Flowing text is a single vector drawing surface behind the scrolling pages: the list
            // and the pager only reserve each page's rectangle, and one native layer paints them.
            // It has to stay first in this Box: a native view takes the touch hit, so drawing it
            // under the scrolling surface is what leaves every swipe to the list and the pager.
            Box(expanded.fillMaxSize()) {
                if (textSource != null) {
                    TextPageLayer(
                        source = textSource,
                        vertical = mode == "vertical",
                        listState = state.list,
                        pagerState = state.pager,
                        onPageReady = viewport::pageReady,
                        modifier = Modifier.fillMaxSize(),
                        highlight = highlight,
                    )
                }
                if (mode == "vertical") {
                    // PDF and comic pages are whole sheets: a gap would show up as a seam between
                    // them, so they sit flush. Text pages keep a small break so the paper edges
                    // still read as separate pages.
                    LazyColumn(state = state.list, modifier = Modifier.fillMaxSize(),
                        verticalArrangement = if (textSource == null) Arrangement.Top else Arrangement.spacedBy(4.dp * zoom)) {
                        items(state.pageCount, key = { it }) { page ->
                            if (textSource != null) {
                                TextPagePlaceholder(textSource, page, Modifier.fillMaxWidth())
                            } else {
                                PdfPage(session, page, Modifier.fillMaxWidth(),
                                    initialSize = if (!pdfTextView && page == restoredPage) restoredSize else null,
                                    baseViewport = baseSize, viewportBounds = bounds, zoom = zoom,
                                    keep = page in keepPages, scrolling = scrolling, visible = page in visiblePages,
                                    pageBackground = pageBackground, onPageReady = viewport::pageReady)
                            }
                        }
                    }
                } else {
                    // Keep one page on each side composed: it already owns its bitmap, so the page a
                    // swipe reveals is drawn from the first frame. A page composed only mid-swipe has
                    // to show a spinner, then a half-width preview, then the full page — the blink
                    // that turning back used to cause.
                    HorizontalPager(state = state.pager, modifier = Modifier.fillMaxSize(), reverseLayout = rightToLeft,
                        userScrollEnabled = zoom <= 1.01f, beyondViewportPageCount = 1) { page ->
                        if (textSource != null) {
                            TextPagePlaceholder(textSource, page, Modifier.fillMaxSize(), fit = true)
                        } else {
                            PdfPage(session, page, Modifier.fillMaxSize(), fit = true,
                                baseViewport = baseSize, viewportBounds = bounds, zoom = zoom,
                                // While zoomed no page turn is possible, so only the page on screen keeps a bitmap.
                                keep = zoom <= 1.01f || page == state.pager.currentPage,
                                defer = page != state.pager.currentPage,
                                scrolling = scrolling, pageBackground = pageBackground, onPageReady = viewport::pageReady)
                        }
                    }
                }
            }
        }
    }
}
