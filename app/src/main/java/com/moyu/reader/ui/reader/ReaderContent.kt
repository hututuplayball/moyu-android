package com.moyu.reader.ui.reader

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.moyu.reader.book.PdfTextLayout
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextThemes
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.MAX_PREFETCH_RADIUS
import com.moyu.reader.data.MIN_PREFETCH_RADIUS
import com.moyu.reader.data.ReaderPreferences
import com.moyu.reader.data.SpeechPreferences
import com.moyu.reader.page.PageSource
import com.moyu.reader.pdf.PageSize
import com.moyu.reader.speech.SpeechReader
import com.moyu.reader.speech.SpeechService
import com.moyu.reader.ui.BookmarksPanel
import com.moyu.reader.ui.BrightnessPanel
import com.moyu.reader.ui.ChapterPanel
import com.moyu.reader.ui.LocalMoyuReduceMotion
import com.moyu.reader.ui.PageGridPanel
import com.moyu.reader.ui.PaperSlider
import com.moyu.reader.ui.PdfViewState
import com.moyu.reader.ui.ReaderPanel
import com.moyu.reader.ui.ReaderToolRow
import com.moyu.reader.ui.ReadingModePanel
import com.moyu.reader.ui.SpeechBar
import com.moyu.reader.ui.SpeechPanel
import com.moyu.reader.ui.TypesettingPanel
import com.moyu.reader.ui.ViewPanel
import com.moyu.reader.ui.normalizedPageOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A book on screen: the reading surface, the chrome that appears on a tap, and the one place that
 * decides what a page position means for this kind of book.
 *
 * The state itself lives next door: [ReaderPageState] owns pages and chapters, [ReaderSpeech] the
 * reading aloud, [ReaderAnchor] the position formula, [ReaderCanvas] the pages and [ReaderChrome]
 * the controls. What is left here is the wiring that only makes sense with all of them together.
 */
@Composable
internal fun ReaderContent(
    book: BookEntity,
    session: PageSource,
    restoredSize: PageSize,
    bookmarks: List<Int>,
    preferences: ReaderPreferences,
    brightness: Float,
    textSettings: TextReadingSettings?,
    restorePage: Int?,
    pdfTextView: Boolean,
    pdfAnchors: IntArray?,
    viewState: PdfViewState?,
    onSwitchView: (Boolean, Long) -> Unit,
    onRetryTextView: () -> Unit,
    onBack: () -> Unit,
    onPosition: (Int, Int, String) -> Unit,
    onBookmark: (Int) -> Unit,
    onBrightness: (Float) -> Unit,
    onRightToLeft: (Boolean) -> Unit,
    onTextSettings: (TextReadingSettings, Long) -> Unit,
    onTextPosition: (Int, Int) -> Unit,
    speech: ReaderSpeech,
    speechPreferences: SpeechPreferences,
    onSpeech: (String?, String?, Float) -> Unit,
    onSpeechBackground: (Boolean) -> Unit,
    initialScroll: Int,
    onRestoreConsumed: () -> Unit,
    onChapterJump: (Int) -> Unit,
    onSeek: (Int) -> Unit,
    onAnchorJump: (Int) -> Unit,
) {
    val restoredPage = remember(book.id) { book.currentPage }
    val state = rememberReaderPageState(
        session = session,
        storedMode = book.mode.ifBlank { preferences.defaultMode },
        storedPage = book.currentPage,
        restorePage = restorePage,
        initialScroll = initialScroll,
    )
    val listState = state.list
    val pagerState = state.pager
    val viewport = state.viewport
    var chrome by remember(book.id) { mutableStateOf(false) }
    var panel by remember(book.id) { mutableStateOf<ReaderPanel?>(null) }
    var slider by remember { mutableStateOf<Float?>(null) }
    // The slider keeps the requested spot while the target chapter is being loaded, so the thumb
    // and the chapter label never fall back to the previous page mid-jump.
    var pendingPercent by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(session) {
        // A fresh chapter window has already applied the requested anchor; drop the temporary
        // slider label so the panel reads the exact new page position.
        pendingPercent = null
        slider = null
    }
    // Only pages around the viewport may own a rendered bitmap; everything else is released at once.
    val keepPages by remember(viewport) {
        derivedStateOf {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            ((first - 1).coerceAtLeast(0))..(last + 1)
        }
    }
    // Full-resolution pages wait for the fling to end; motion shows a cheap preview instead.
    val visiblePages by remember(viewport) {
        derivedStateOf {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            first..last
        }
    }
    val scrolling by remember(viewport) {
        derivedStateOf {
            if (viewport.vertical) listState.isScrollInProgress else pagerState.isScrollInProgress
        }
    }
    val activity = LocalContext.current as Activity
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val positionCallback by rememberUpdatedState(onPosition)
    val textPositionCallback by rememberUpdatedState(onTextPosition)
    val textSource = session as? TextPageSource
    // Pure text marks are character anchors: window pages disappear when type or chapter changes.
    val pureText = book.isTextBook
    val offsetMarks = pureText && textSource != null
    val motionDuration = if (LocalMoyuReduceMotion.current) 0 else 220
    // How far each side is warmed; the settings screen owns the value so weak phones can ask for less.
    val prefetchRadius = preferences.prefetchRadius.coerceIn(MIN_PREFETCH_RADIUS, MAX_PREFETCH_RADIUS)
    // Text books paint the whole surface (status bar included) with their reading theme.
    val isText = textSettings != null
    val textTheme = textSettings?.theme ?: TextThemes.default
    val readerBackground = if (isText) Color(textTheme.bg) else MaterialTheme.colorScheme.surfaceContainerLow
    val light = if (isText) Color(textTheme.bg).luminance() > .5f else MaterialTheme.colorScheme.background.luminance() > .5f
    val pageBackground = if (isText) Color(textTheme.bg) else Color.White
    val systemBrightness = remember {
        runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            .getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    fun jump(value: Int) {
        val target = value.coerceIn(0, state.pageCount - 1)
        viewport.resetForJump(target)
        if (state.mode != "vertical") scope.launch { pagerState.scrollToPage(target) }
    }
    // Panels close themselves through this wrapper, so no panel has to know about chrome state.
    val jumpAndClose: (Int) -> Unit = { page -> jump(page); panel = null }

    // The text view reports text pages; the PDF view reports its own pages.
    val reportPosition: (Int, Int, String) -> Unit = { page, scroll, mode ->
        val source = textSource
        if (source != null) {
            // The anchor is the first visible line, not the first character of the page. The page
            // pixel is never stored as the position; it is derived from this anchor on restore.
            val anchor = lineAnchor(source, page, scroll, mode == "vertical", viewport.viewport.width)
            val anchors = if (pdfTextView) pdfAnchors else null
            if (anchors != null) {
                positionCallback(PdfTextLayout.pageForOffset(anchors, anchor), 0, mode)
            } else {
                // Pages are window-local; only the mode belongs to the PDF progress row now.
                positionCallback(0, 0, mode)
            }
            textPositionCallback(
                anchor,
                if (source.isLastPageOfBook(page)) 100 else source.progressPercentForOffset(anchor),
            )
        } else {
            positionCallback(page, scroll, mode)
        }
    }
    // A comic's last page may be shorter than the viewport, so it can never become the first
    // visible item. Scrolled to the bottom is then the real "last page".
    val atComicEnd: () -> Boolean = {
        textSource == null && state.pageCount > 0 && !listState.canScrollForward &&
            listState.layoutInfo.visibleItemsInfo.let { it.isNotEmpty() && it.size < state.pageCount }
    }
    // The settle collector runs a frame after the page stops; leaving must save the page that is
    // actually on screen or a quick exit records the previous page.
    val saveVisiblePosition by rememberUpdatedState {
        if (restorePage == null) {
            state.positionToSave(atComicEnd())?.let { (page, scroll) ->
                reportPosition(page, scroll, state.mode)
            }
        }
    }
    LaunchedEffect(state.pendingJump) {
        if (state.pendingJump == null) pendingPercent = null
    }
    LaunchedEffect(viewport, state.mode) {
        snapshotFlow { if (viewport.vertical) listState.isScrollInProgress else pagerState.isScrollInProgress }
            .distinctUntilChanged().collect { moving -> if (moving) chrome = false }
    }
    ReaderPageEffects(
        state = state,
        session = session,
        restorePage = restorePage,
        initialScroll = initialScroll,
        atComicEnd = atComicEnd,
        onRestoreConsumed = onRestoreConsumed,
        report = reportPosition,
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    // The key must include the flag: the observer reads it when the app stops, not when it is set up.
    DisposableEffect(lifecycleOwner, session, speech, speechPreferences.background) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.trimMemory()
                // Background playback keeps going only when the reader asked for it; otherwise
                // the phone would keep talking with no notification behind it.
                if (!speechPreferences.background) speech.reader.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Under real memory pressure the page cache is the first thing worth giving up: it rebuilds
    // from the layout cache, while the alternative is the reader being killed. The activity drops
    // covers and shelf caches at the same level.
    DisposableEffect(session, activity) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) session.trimMemory()
            }
            override fun onLowMemory() = session.trimMemory()
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        val app = activity.applicationContext
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }
    ReaderWindowEffects(
        activity = activity,
        view = view,
        brightness = brightness,
        keepScreenOn = preferences.keepScreenOn,
        chrome = chrome,
        light = light,
    )

    // Read the live line only when the chrome opens or a setting changes, not on every scroll
    // frame. Persistence uses the same formula directly in reportPosition.
    // The frozen anchor shown by the open toolbar. It is captured when the chrome is revealed.
    var chromeAnchor by remember(session, book.id) { mutableIntStateOf(0) }
    // The engine in use: the one the reader chose, else the device default, else the first engine
    // that can read Chinese. A voice belongs to its engine, so it is looked up there.
    val choice = remember(speech.engines, speechPreferences.engine, speechPreferences.voice) {
        chooseSpeech(speech.engines, activity, speechPreferences)
    }
    // Reading aloud needs text to read and an engine that can read Chinese.
    val speechAvailable = speech.engines.isNotEmpty() && textSource != null
    // Starting the reading takes the text from the line on screen to the end of the book.
    val startSpeech: () -> Unit = {
        val source = textSource
        if (source != null) {
            val paragraphs = source.paragraphsFrom(state.lineAnchorNow())
            if (paragraphs.isNotEmpty()) {
                scope.launch {
                    speech.reader.start(paragraphs, choice.engine, choice.voice, speechPreferences.rate)
                }
            }
        }
    }
    val toggleSpeech: () -> Unit = {
        when (speech.reader.status) {
            SpeechReader.Status.Playing -> speech.reader.pause()
            SpeechReader.Status.Paused -> speech.reader.resume()
            else -> startSpeech()
        }
    }
    val stopSpeech: () -> Unit = { speech.reader.stop() }
    // A sleep timer belongs to this reading session; picking a new value restarts the countdown.
    // The remaining seconds are only read inside the speech panel, so the ticking state does not
    // invalidate the whole reader canvas every second.
    LaunchedEffect(speech.sleepMinutes) {
        if (speech.sleepMinutes <= 0) {
            speech.sleepRemaining.intValue = 0
            return@LaunchedEffect
        }
        var seconds = speech.sleepMinutes * 60
        speech.sleepRemaining.intValue = seconds
        while (seconds > 0) {
            delay(1_000L)
            seconds -= 1
            speech.sleepRemaining.intValue = seconds
        }
        speech.reader.stop()
        speech.clearSleep()
    }
    // Background playback runs in a foreground service: it keeps the process alive and shows
    // what is being read. Without that service the system may freeze the app mid-sentence.
    // The service follows the reading itself, while each sentence only re-posts its notification:
    // starting the service per sentence meant a background-start check and a notification rebuild
    // every few seconds, for a notification whose text had merely moved on.
    val readingAloud = speech.reader.status == SpeechReader.Status.Playing ||
        speech.reader.status == SpeechReader.Status.Paused
    LaunchedEffect(speechPreferences.background, readingAloud) {
        if (speechPreferences.background && readingAloud) {
            SpeechService.start(activity.applicationContext, book.title, speech.reader.line?.text)
        } else {
            SpeechService.stop(activity.applicationContext)
        }
    }
    LaunchedEffect(speechPreferences.background, readingAloud, speech.reader.line?.text) {
        val line = speech.reader.line?.text ?: return@LaunchedEffect
        if (speechPreferences.background && readingAloud) SpeechService.update(book.title, line)
    }
    DisposableEffect(speech) {
        // The notification's stop button reaches this reader while it is alive.
        SpeechService.stopReading = { speech.reader.stop() }
        onDispose { SpeechService.stopReading = null }
    }
    // Keep the sentence being read on screen. A page is a screenful, so following page changes is
    // enough; a sentence that ran past the loaded window reopens it at that offset.
    LaunchedEffect(speech.reader.line?.offset, speech.reader.status, session, state.mode, restorePage, viewport.viewport) {
        val line = speech.reader.line ?: return@LaunchedEffect
        if (restorePage != null || speech.reader.status != SpeechReader.Status.Playing) return@LaunchedEffect
        val source = textSource ?: return@LaunchedEffect
        // While zoomed the page is larger than the viewport and panning owns the position.
        if (viewport.zoom > 1.01f) return@LaunchedEffect
        if (source.lineAnchorInWindow(line.offset) == null) {
            onAnchorJump(line.offset)
            return@LaunchedEffect
        }
        val position = source.positionForCharOffset(line.offset, viewport.viewport.width)
        if (state.mode == "vertical") {
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == position.page }
            val height = viewport.viewport.height
            val y = item?.let { it.offset + position.pageOffset }
            // Only pull a page up when its sentence sits below the part of it on screen.
            if (y == null || y < 0 || (height > 0 && y > height * 0.85f)) {
                listState.requestScrollToItem(position.page, 0)
            }
        } else if (pagerState.currentPage != position.page) {
            scope.launch { pagerState.scrollToPage(position.page) }
        }
    }
    // A bookmark whose saved anchor maps to the current line in the current layout.
    var chromeLineMark by remember(session, book.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(chrome, session, state.currentPage, state.mode, state.pagesVersion, bookmarks) {
        if (chrome) {
            val anchor = state.lineAnchorNow()
            chromeAnchor = anchor
            chromeLineMark = textSource?.let { bookmarkAtLine(it, bookmarks, anchor) }
        } else {
            chromeLineMark = null
        }
    }
    // Pages are replaced when a chapter is loaded; the revision must invalidate the cached percent,
    // otherwise a TOC jump that lands on the same page index leaves the old chapter label on screen.
    val textPageFraction = remember(textSource, chromeAnchor, state.currentPage, state.pageCount, state.pagesVersion) {
        val source = textSource
        when {
            source == null -> 0f
            // A line anchor never reaches the book end; the last page is what "read to the end" is.
            source.isLastPageOfBook(state.currentPage) -> 100f
            else -> source.progressFractionForOffset(chromeAnchor)
        }
    }
    // Bookmarks, progress and the page label stay in PDF pages, whichever view is on screen.
    val pdfPage = if (pdfTextView && pdfAnchors != null && textSource != null) {
        PdfTextLayout.pageForOffset(pdfAnchors, chromeAnchor)
    } else state.currentPage
    val jumpPdfPage: (Int) -> Unit = { page ->
        val source = textSource
        if (pdfTextView && pdfAnchors != null && source != null) {
            onAnchorJump(PdfTextLayout.offsetOfPage(pdfAnchors, page))
        } else jump(page)
    }
    val jumpPdfAndClose: (Int) -> Unit = { page -> jumpPdfPage(page); panel = null }
    // Marks stay PDF pages; their labels follow the layout on screen, like the shelf does.
    val bookmarkPageNumber: (Int) -> Int = { page ->
        val anchors = pdfAnchors
        if (pdfTextView && anchors != null && textSource != null) {
            textSource.pageForCharOffset(PdfTextLayout.offsetOfPage(anchors, page).toLong()) + 1
        } else page + 1
    }
    // Marks use the same line anchor as progress, so different lines in one page can be bookmarked.
    // A saved anchor is mapped through the current layout, so a bookmark still lights up the line
    // that contains it after a font or line-spacing change.
    val currentMarker = if (offsetMarks) chromeLineMark ?: chromeAnchor else pdfPage
    val currentMarked = if (offsetMarks) chromeLineMark != null else currentMarker in bookmarks
    val markLabel: (Int) -> String = if (textSource != null && (pureText || pdfTextView)) {
        { mark -> markLabelFor(textSource, pureText, pdfAnchors, mark) }
    } else {
        { page -> "第 ${bookmarkPageNumber(page)} 页" }
    }
    val jumpMarker: (Int) -> Unit = if (offsetMarks) {
        { offset -> onAnchorJump(offset); panel = null }
    } else jumpPdfAndClose
    // A text page knows its exact char anchor, so switching views never loses the reading spot.
    val switchView: (Boolean) -> Unit = { wanted ->
        if (wanted != pdfTextView) {
            val anchors = pdfAnchors
            val offset = if (wanted && anchors != null) {
                PdfTextLayout.offsetOfPage(anchors, pdfPage).toLong()
            } else {
                if (textSource != null) state.lineAnchorNow().toLong() else 0L
            }
            onSwitchView(wanted, offset)
        }
    }
    // Any typesetting change re-lays the book; a char offset maps the old page to the new one.
    val changeText: ((TextReadingSettings) -> TextReadingSettings) -> Unit = { transform ->
        val current = textSettings
        if (current != null) {
            val offset = if (session is TextPageSource) state.lineAnchorNow().toLong() else 0L
            onTextSettings(transform(current), offset)
        }
    }
    val contentLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(contentLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveVisiblePosition()
        }
        contentLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { contentLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Edge-back unwinds the panel first, so the drawer never traps the gesture or leaves the book unexpectedly.
    BackHandler {
        when {
            panel != null -> panel = null
            chrome -> chrome = false
            viewport.zoom > 1.01f -> viewport.toggleZoom()
            else -> {
                saveVisiblePosition()
                onBack()
            }
        }
    }

    // Switching direction keeps the paragraph under the eye: the new scroll state is put on the
    // same page with the same in-page pixel, and it only starts reporting once it has measured.
    val switchMode: (String) -> Unit = { value ->
        if (state.mode != value) {
            // Read the page from the state that is actually driving the screen. While a fling is
            // still running the pager has not settled yet, so its target is the page the reader
            // is looking at.
            val jumpTo = state.pendingJump
            val page = when {
                jumpTo != null -> jumpTo
                state.mode == "vertical" -> listState.firstVisibleItemIndex
                else -> pagerState.targetPage
            }.coerceIn(0, state.pageCount - 1)
            val scroll = when {
                value != "vertical" -> 0
                jumpTo != null -> state.pendingJumpScroll
                state.mode == "vertical" -> normalizedPageOffset(listState.firstVisibleItemScrollOffset, viewport.measuredZoom)
                else -> 0
            }
            // Both scroll states are put on the same page, and the viewport keeps gating
            // position reports until the new layout has measured that page. Without this
            // the freshly started collector would report the other state's stale page.
            viewport.setReadingMode(value == "vertical")
            viewport.resetForJump(page, scroll)
            state.modeSwitch = page to scroll
            state.currentPage = page
            state.offset = scroll
            state.changeMode(value)
            reportPosition(page, scroll, value)
        }
        panel = null
    }

    Box(Modifier.fillMaxSize()) {
        ReaderCanvas(
            session = session,
            state = state,
            textSource = textSource,
            restoredPage = restoredPage,
            restoredSize = restoredSize,
            keepPages = keepPages,
            visiblePages = visiblePages,
            scrolling = scrolling,
            pdfTextView = pdfTextView,
            pageBackground = pageBackground,
            background = readerBackground,
            prefetchRadius = prefetchRadius,
            rightToLeft = preferences.rightToLeft,
            highlight = speech.highlight,
            onGesture = { chrome = false },
            onTap = { point ->
                val fraction = viewport.pageFraction(point) ?: .5f
                when {
                    state.mode == "horizontal" && viewport.zoom <= 1.01f && fraction < .23f ->
                        jump(state.currentPage + if (preferences.rightToLeft) 1 else -1)
                    state.mode == "horizontal" && viewport.zoom <= 1.01f && fraction > .77f ->
                        jump(state.currentPage + if (preferences.rightToLeft) -1 else 1)
                    else -> {
                        // Freeze the line before opening the toolbar so the first panel frame already
                        // shows the correct chapter, bookmark state and progress.
                        if (!chrome) {
                            val anchor = state.lineAnchorNow()
                            chromeAnchor = anchor
                            chromeLineMark = textSource?.let { bookmarkAtLine(it, bookmarks, anchor) }
                        }
                        chrome = !chrome
                    }
                }
            },
        )
        ReaderTopBar(
            visible = chrome,
            bookTitle = book.title,
            // Text books keep the chapter heading on the page itself; the chrome only carries the
            // book title, so the chapter name is not printed twice.
            pageLabel = if (textSource == null) "${state.currentPage + 1} / ${state.pageCount}" else null,
            viewState = viewState,
            motionDuration = motionDuration,
            onBack = {
                saveVisiblePosition()
                onBack()
            },
            onPickView = { wanted -> switchView(wanted) },
            onOpenViewPanel = { panel = ReaderPanel.View },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        AnimatedVisibility(
            visible = !chrome && (speech.reader.status == SpeechReader.Status.Playing ||
                speech.reader.status == SpeechReader.Status.Paused),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(motionDuration)) + slideInVertically(tween(motionDuration)) { it },
            exit = fadeOut(tween(motionDuration)) + slideOutVertically(tween(motionDuration)) { it },
        ) {
            SpeechBar(
                text = speech.reader.line?.text.orEmpty(),
                status = speech.reader.status,
                onToggle = toggleSpeech,
                onStop = stopSpeech,
                modifier = Modifier.navigationBarsPadding().padding(16.dp),
            )
        }
        ReaderBottomSheet(
            visible = chrome,
            panel = panel,
            motionDuration = motionDuration,
            onCollapse = { if (panel != null) panel = null else chrome = false },
            onDismissPanel = { panel = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            when (panel) {
                null -> {
                    val source = textSource
                    val shownPage = (slider?.roundToInt() ?: state.currentPage)
                        .coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                    val shownProgress = if (source != null) {
                        (pendingPercent ?: slider ?: textPageFraction).coerceIn(0f, 100f)
                    } else null
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            when {
                                source != null && (pendingPercent != null || slider != null) -> {
                                    val offset = (source.characterCount.toLong() * shownProgress!! / 100f).toInt()
                                    source.chapterTitleForOffset(offset) ?: "正文"
                                }
                                source != null -> source.chapterTitleForOffset(chromeAnchor) ?: "正文"
                                else -> "${shownPage + 1} / ${state.pageCount}"
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (shownProgress == null) {
                            Text(
                                "${((shownPage + 1f) / state.pageCount * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box(Modifier.padding(horizontal = 22.dp)) {
                        PaperSlider(
                            value = if (shownProgress != null) shownProgress else slider ?: state.currentPage.toFloat(),
                            onValueChange = { slider = it },
                            onValueChangeFinished = {
                                val value = slider
                                slider = null
                                if (value != null) {
                                    if (source != null) {
                                        // Keep the requested position visible while the
                                        // target chapter page is being loaded.
                                        pendingPercent = value
                                        onSeek(value.roundToInt())
                                    } else {
                                        jump(value.roundToInt())
                                    }
                                }
                            },
                            valueRange = if (source != null) 0f..100f
                            else 0f..(state.pageCount - 1).coerceAtLeast(1).toFloat(),
                            enabled = if (source != null) true else state.pageCount > 1,
                        )
                    }
                    ReaderToolRow(
                        bookmarked = currentMarked,
                        isText = isText,
                        mode = state.mode,
                        brightness = brightness,
                        speechAvailable = speechAvailable,
                        onPanel = { panel = it },
                    )
                }
                ReaderPanel.Bookmarks -> BookmarksPanel(
                    bookmarks = bookmarks,
                    currentMark = currentMarker,
                    currentMarked = currentMarked,
                    onToggle = onBookmark,
                    onJump = jumpMarker,
                    label = markLabel,
                )
                ReaderPanel.Pages -> PageGridPanel(session, state.currentPage, jumpAndClose)
                ReaderPanel.Mode -> ReadingModePanel(state.mode, preferences.rightToLeft) { value, toLeft ->
                    onRightToLeft(toLeft)
                    switchMode(value)
                }
                ReaderPanel.Brightness -> BrightnessPanel(brightness, systemBrightness, onBrightness)
                ReaderPanel.Typesetting -> TypesettingPanel(
                    textSettings ?: TextReadingSettings(), changeText,
                )
                ReaderPanel.Toc -> {
                    val source = textSource
                    if (source != null) {
                        ChapterPanel(source, source.chapterIndexForPage(state.currentPage)) { chapter ->
                            panel = null
                            onChapterJump(chapter)
                        }
                    }
                }
                ReaderPanel.Speech -> SpeechPanel(
                    engines = speech.engines,
                    engine = choice.engine,
                    voices = choice.voices,
                    voice = choice.voice?.key,
                    rate = speechPreferences.rate,
                    status = speech.reader.status,
                    reading = speech.reader.line?.text,
                    failure = speech.reader.failure,
                    background = speechPreferences.background,
                    sleepMinutes = speech.sleepMinutes,
                    sleepRemaining = { speech.sleepRemaining.intValue },
                    onEngine = { packageName ->
                        // A voice belongs to its engine, so switching engines drops it.
                        onSpeech(packageName, null, speechPreferences.rate)
                        scope.launch { speech.reader.useEngine(packageName) }
                    },
                    onVoice = { key ->
                        val picked = choice.voices.firstOrNull { it.key == key }
                            ?: choice.voices.firstOrNull { it.name == key }
                        onSpeech(choice.engine, key, speechPreferences.rate)
                        speech.reader.setVoice(picked)
                    },
                    onRateFinished = { value ->
                        onSpeech(choice.engine, speechPreferences.voice, value)
                        speech.reader.applyRate(value)
                    },
                    onBackground = onSpeechBackground,
                    onSleepMinutes = { speech.setSleep(it) },
                    onToggle = toggleSpeech,
                    onStop = stopSpeech,
                )
                ReaderPanel.View -> viewState?.let { view ->
                    ViewPanel(view, onPick = { wanted ->
                        panel = null
                        switchView(wanted)
                    }, onRetry = {
                        panel = null
                        onRetryTextView()
                    })
                }
            }
        }
    }
}
