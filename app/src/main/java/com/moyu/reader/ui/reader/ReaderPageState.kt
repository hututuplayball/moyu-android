package com.moyu.reader.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.page.PageSource
import com.moyu.reader.ui.ReaderViewportState
import com.moyu.reader.ui.normalizedPageOffset
import kotlinx.coroutines.flow.distinctUntilChanged

// Load the next chapter while this many pages are still ahead: a fast fling must not reach the
// end of the loaded window before the chapter arrives, or it stops dead at the last page.
private const val NEXT_CHAPTER_PAGES_LEFT = 6

// Keep this many pages of the previous chapter loaded before the reader settles near the start.
// Prepending shifts page indices, so it must not run while a fling is still moving.
private const val PREVIOUS_CHAPTER_PAGES_LEFT = 6

/**
 * Where the reader is in the book, in the units the screen actually moves in: which page, how far
 * into it, and which direction the pages are laid out in.
 *
 * A text book's pages are a window on a chapter, so [pageCount] changes under the reader as
 * chapters load; every consumer reads it through this state instead of capturing a stale count.
 */
internal class ReaderPageState(
    private val source: PageSource,
    initialMode: String,
    initialPage: Int,
    initialScroll: Int,
) {
    var mode by mutableStateOf(initialMode)
        private set
    var currentPage by mutableIntStateOf(initialPage)
    var offset by mutableIntStateOf(initialScroll)
    var pageCount by mutableIntStateOf(source.pageCount)
        private set
    /** Bumped whenever the page list is replaced, so cached labels and bitmaps are dropped. */
    var pagesVersion by mutableIntStateOf(0)
        private set
    var pendingJump by mutableStateOf<Int?>(null)
    var pendingJumpScroll by mutableIntStateOf(0)
    // Where a reading-mode switch is heading, kept until the new layout has actually landed so an
    // exit in between still saves the page the reader switched to, not the old one.
    var modeSwitch by mutableStateOf<Pair<Int, Int>?>(null)

    lateinit var list: LazyListState
    lateinit var pager: PagerState
    lateinit var viewport: ReaderViewportState

    val vertical: Boolean get() = mode == "vertical"

    fun attach(list: LazyListState, pager: PagerState, viewport: ReaderViewportState) {
        this.list = list
        this.pager = pager
        this.viewport = viewport
    }

    /** Switches page direction; the viewport is told separately so it can gate position reports. */
    fun changeMode(value: String) {
        mode = value
    }

    /** A chapter window grew or shrank; the count and every cache key that depends on it follow. */
    fun pagesChanged() {
        pagesVersion++
        pageCount = source.pageCount
    }

    /**
     * The position a save should record right now, or null while a restore is still landing.
     *
     * A comic's last page may be shorter than the viewport, so it can never become the first
     * visible item; scrolled to the bottom is the real "last page" and has to be reported instead
     * of the page before it, or the label, progress and saved position all stop one page short.
     */
    fun positionToSave(atComicEnd: Boolean): Pair<Int, Int>? {
        if (pageCount <= 0) return null
        val jump = pendingJump
        val switched = modeSwitch
        val atEnd = vertical && atComicEnd
        val page = when {
            switched != null -> switched.first
            jump != null -> jump
            vertical -> if (atEnd) pageCount - 1 else list.firstVisibleItemIndex
            // targetPage is where the pager was heading when the user left, so a quick exit
            // during the snap animation does not record the previous page.
            else -> pager.targetPage
        }
        val scroll = when {
            switched != null -> if (vertical) switched.second else 0
            !vertical -> 0
            // A chapter prepend has already shifted the page index but not yet settled.
            jump != null -> pendingJumpScroll
            atEnd -> 0
            else -> normalizedPageOffset(list.firstVisibleItemScrollOffset, viewport.measuredZoom)
        }
        return page.coerceIn(0, pageCount - 1) to scroll
    }

    /** The first visible line right now, for the toolbar and for starting the reading aloud. */
    fun lineAnchorNow(): Int {
        val text = source as? TextPageSource ?: return 0
        val page = if (vertical) list.firstVisibleItemIndex else pager.currentPage
        return lineAnchor(
            text,
            page,
            normalizedPageOffset(list.firstVisibleItemScrollOffset, viewport.measuredZoom),
            vertical,
            viewport.viewport.width,
        )
    }
}

@Composable
internal fun rememberReaderPageState(
    session: PageSource,
    storedMode: String,
    storedPage: Int,
    restorePage: Int?,
    initialScroll: Int,
): ReaderPageState {
    val initialPage = (restorePage ?: storedPage).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
    val state = remember(session) {
        ReaderPageState(session, storedMode, initialPage, initialScroll)
    }
    val list = key(session) { rememberLazyListState(initialPage, initialScroll) }
    val pager = key(session) {
        rememberPagerState(initialPage = initialPage, pageCount = { state.pageCount })
    }
    val viewport = remember(session, list, pager) {
        ReaderViewportState(list, pager, state.mode == "vertical")
    }
    state.attach(list, pager, viewport)
    return state
}

/**
 * Keeps [ReaderPageState] in step with the two scroll surfaces, and grows the chapter window before
 * the reader can reach its edge.
 *
 * [report] is the caller's single place that turns a page position into stored progress; it differs
 * between PDF pages, comics and flowing text, so it stays with the reader.
 */
@Composable
internal fun ReaderPageEffects(
    state: ReaderPageState,
    session: PageSource,
    restorePage: Int?,
    initialScroll: Int,
    atComicEnd: () -> Boolean,
    onRestoreConsumed: () -> Unit,
    report: (page: Int, scroll: Int, mode: String) -> Unit,
) {
    val list = state.list
    val pager = state.pager
    val viewport = state.viewport
    val mode = state.mode
    val pageCount = state.pageCount

    LaunchedEffect(restorePage, mode, initialScroll) {
        val target = restorePage ?: return@LaunchedEffect
        val restoredOffset = if (mode == "vertical") initialScroll.coerceAtLeast(0) else 0
        // Never suspend here. A user scroll cancels a programmatic scrollToItem/scrollToPage,
        // which used to cancel this effect before onRestoreConsumed(); restorePage then stayed
        // non-null forever, disabling both position reports and next-chapter loading.
        state.currentPage = target
        state.offset = restoredOffset
        viewport.resetForJump(target, restoredOffset)
        onRestoreConsumed()
    }
    // Keyed by mode as well: switching direction changes which scroll state owns the position.
    LaunchedEffect(viewport, restorePage, mode, pageCount) {
        // No page report may race the restore jump: otherwise page zero can overwrite the anchor.
        if (restorePage != null) return@LaunchedEffect
        if (mode == "vertical") {
            // The list was created with the restored page/offset and resetForJump has already
            // requested it. Do not call a suspend scroll here either: a user drag would cancel
            // the effect before the snapshotFlow below could ever start.
            snapshotFlow {
                when {
                    state.pendingJump != null || viewport.hasPendingAnchor -> null
                    // Scrolled to the bottom of a comic: the (short) last page cannot become the
                    // first visible item, so this is the only way to report it as the current page.
                    atComicEnd() -> (pageCount - 1) to 0
                    else -> list.firstVisibleItemIndex to
                        normalizedPageOffset(list.firstVisibleItemScrollOffset, viewport.measuredZoom)
                }
            }.distinctUntilChanged().collect { position ->
                if (position != null) {
                    val (page, scroll) = position
                    state.modeSwitch = null
                    state.currentPage = page
                    state.offset = scroll
                    report(page, scroll, mode)
                }
            }
        } else {
            snapshotFlow {
                if (state.pendingJump != null || viewport.hasPendingAnchor) null
                else pager.settledPage
            }.distinctUntilChanged().collect { page ->
                if (page != null) {
                    state.modeSwitch = null
                    state.currentPage = page
                    state.offset = 0
                    report(page, 0, mode)
                }
            }
        }
    }
    LaunchedEffect(session, pageCount, mode, restorePage) {
        // Chapter windows are grown just before the reader reaches an edge, so a page turn never
        // runs into a "no page" wall. The page is observed instead of being one of the effect keys:
        // a fling changes it every frame, and re-keying the effect cancelled the chapter load
        // before it could finish. The window then ran out under the fling and stopped dead at the
        // last loaded page.
        if (restorePage != null) return@LaunchedEffect
        val text = session as? TextPageSource ?: return@LaunchedEffect
        snapshotFlow {
            state.currentPage to (if (mode == "vertical") list.isScrollInProgress else pager.isScrollInProgress)
        }.distinctUntilChanged().collect { (page, scrolling) ->
            if (page >= pageCount - NEXT_CHAPTER_PAGES_LEFT && text.hasNextChapter()) {
                // An empty chapter can paginate to zero pages. Keep advancing through such chapters
                // instead of letting one zero-page slice permanently stop the window at its edge.
                var added = 0
                while (added == 0 && text.hasNextChapter()) {
                    added = text.loadNextChapter()
                }
                if (added > 0) state.pagesChanged()
            }
            // Prepending shifts every page index, so the visible page must be requested again:
            // that cancels a running fling. Wait for the reader to settle, otherwise a backward
            // fling is cut dead in its tracks.
            if (!scrolling && page <= PREVIOUS_CHAPTER_PAGES_LEFT && text.hasPreviousChapter()) {
                val keepScroll = state.offset
                var added = 0
                while (added == 0 && text.hasPreviousChapter()) {
                    added = text.loadPreviousChapter()
                }
                if (added > 0) {
                    state.pagesChanged()
                    state.currentPage += added
                    state.pendingJump = state.currentPage
                    // Page indices shifted, the line on screen did not: carry its pixel offset.
                    state.pendingJumpScroll = keepScroll
                }
            }
        }
    }
    LaunchedEffect(state.pendingJump, pageCount, mode) {
        val target = state.pendingJump ?: return@LaunchedEffect
        if (target !in 0 until pageCount) return@LaunchedEffect
        val targetScroll = if (mode == "vertical") state.pendingJumpScroll else 0
        state.currentPage = target
        state.offset = targetScroll
        // resetForJump only requests a scroll; it never suspends and can therefore never be
        // cancelled by a user drag before pendingJump is cleared.
        viewport.resetForJump(target, targetScroll)
        state.pendingJump = null
        state.pendingJumpScroll = 0
    }
}
