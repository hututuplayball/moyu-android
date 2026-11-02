package com.moyu.reader.ui

import android.app.Activity
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyu.reader.book.PdfTextLayout
import com.moyu.reader.book.TextDocument
import com.moyu.reader.book.TextLineSpacing
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.book.TextThemes
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.FORMAT_ARCHIVE
import com.moyu.reader.data.FORMAT_EPUB
import com.moyu.reader.data.FORMAT_FOLDER
import com.moyu.reader.data.FORMAT_PDF
import com.moyu.reader.data.FORMAT_TEXT
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.data.LibraryRepository
import com.moyu.reader.data.MAX_PREFETCH_RADIUS
import com.moyu.reader.data.MIN_PREFETCH_RADIUS
import com.moyu.reader.data.ReaderPreferences
import com.moyu.reader.data.READ_VIEW_TEXT
import com.moyu.reader.data.TEXT_READY
import com.moyu.reader.data.TEXT_UNKNOWN
import com.moyu.reader.data.TEXT_UNSUPPORTED
import com.moyu.reader.page.PageSource
import com.moyu.reader.pdf.PageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Typesetting taps settle before the whole book is measured again: five A+ steps cost one reflow.
private const val REFLOW_SETTLE_MS = 200L

@Composable
fun ReaderScreen(
    book: BookEntity,
    repository: LibraryRepository,
    preferences: ReaderPreferences,
    brightness: Float,
    onBack: () -> Unit,
    onPosition: (Int, Int, String) -> Unit,
    onBookmark: (Int) -> Unit,
    onBrightness: (Float) -> Unit,
    onRightToLeft: (Boolean) -> Unit,
    onRelinked: () -> Unit = {},
    onTextReading: (Int?, String?, String?) -> Unit = { _, _, _ -> },
    onTextPosition: (Int, Int) -> Unit = { _, _ -> },
    onView: (Boolean) -> Unit = {},
) {
    var session by remember(book.id) { mutableStateOf<PageSource?>(null) }
    var restoredSize by remember(book.id) { mutableStateOf<PageSize?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var retry by remember(book.id) { mutableIntStateOf(0) }
    var relinking by remember(book.id) { mutableStateOf(false) }
    // The parsed book outlives a typesetting change, so only the layout has to be rebuilt.
    var textDocument by remember(book.id, retry) { mutableStateOf<TextDocument?>(null) }
    // Only e-book PDFs gain a flowing-text view; comics keep their pages untouched.
    val isPdf = book.format == FORMAT_PDF
    val canHaveTextView = isPdf && book.kind == KIND_EBOOK
    // A PDF reopens in the view it was last read in, once its text layer was proven usable.
    var textView by rememberSaveable(book.id) { mutableStateOf(book.hasTextView && book.readView == READ_VIEW_TEXT) }
    var pdfTextState by remember(book.id) { mutableIntStateOf(book.textState) }
    var pdfTextNotice by remember(book.id) { mutableStateOf<String?>(null) }
    var pdfAnchors by remember(book.id) { mutableStateOf<IntArray?>(null) }
    // One re-probe is allowed when the stored verdict outlives its cache; never more than one.
    var pdfTextReprobed by remember(book.id) { mutableStateOf(false) }
    // A char offset captured before a view switch; the new layout maps it back to a page.
    var pendingOffset by remember(book.id) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    // Retiring a page source suspends on IO, while the composition that owned it may already be gone.
    val closer = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    DisposableEffect(book.id) {
        onDispose {
            val live = session
            session = null
            // Close the source first, then collect after its coroutine has released its last
            // reference. Android otherwise keeps the freed page bitmaps until another allocation
            // pressures the heap, which made returning to the shelf look like it never dropped.
            var closeJob = live?.let { source ->
                closer.launch {
                    try {
                        source.close()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The reader is already gone; a failed close must not keep it alive.
                    }
                }
            }
            closer.launch {
                closeJob?.join()
                closeJob = null
                delay(150)
                Runtime.getRuntime().gc()
            }
        }
    }
    val isText = book.format == FORMAT_TEXT || book.format == FORMAT_EPUB || (isPdf && textView)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Text books paginate for the real window; comics keep PDF-land sizing untouched.
    val textPage = remember(book.id, configuration.screenWidthDp, configuration.screenHeightDp, density.density, isText) {
        val scale = density.density
        PageSize(
            (configuration.screenWidthDp * scale).roundToInt().coerceAtLeast(240),
            (configuration.screenHeightDp * scale).roundToInt().coerceAtLeast(360),
        )
    }
    val textSettings = remember(isText, preferences.textFontSize, preferences.textLineSpacing, preferences.textThemeId) {
        if (isText) {
            TextReadingSettings(
                fontSizeSp = preferences.textFontSize,
                lineSpacing = TextLineSpacing.byId(preferences.textLineSpacing),
                theme = TextThemes.byId(preferences.textThemeId),
            )
        } else null
    }
    // A target page pending a layout change; set after switching views or typesetting.
    var restorePage by remember(book.id) { mutableStateOf<Int?>(null) }
    // Re-picking the original file re-imports it under the same content hash, so progress and
    // bookmarks survive even when the document was moved or its permission was revoked.
    val pickOriginal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        relinking = true
        scope.launch {
            try {
                val matched = repository.importFile(uri)
                if (matched.id == book.id) {
                    error = null
                    // The original may have been unreachable when the text layer was probed.
                    pdfTextState = TEXT_UNKNOWN
                    pdfTextReprobed = false
                    onRelinked()
                    retry++
                } else {
                    error = "所选文件与这本书不是同一份文件"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "重新导入失败，请稍后再试"
            } finally {
                relinking = false
            }
        }
    }
    // Folders are re-linked in place: choosing a different folder never adds a second book.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        relinking = true
        scope.launch {
            try {
                if (repository.relinkFolder(book, uri)) {
                    error = null
                    onRelinked()
                    retry++
                } else {
                    error = "所选文件夹与这本书不是同一份内容"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "重新关联失败，请稍后再试"
            } finally {
                relinking = false
            }
        }
    }
    // Page anchors bridge the PDF view and the text view; they also explain a missing text layer.
    LaunchedEffect(book.id, pdfTextState) {
        if (!isPdf) {
            pdfAnchors = null
            pdfTextNotice = null
            return@LaunchedEffect
        }
        if (pdfTextState != TEXT_READY) {
            pdfAnchors = null
            pdfTextNotice = if (pdfTextState == TEXT_UNSUPPORTED) repository.pdfTextNotice(book) else null
            return@LaunchedEffect
        }
        val anchors = repository.pdfTextAnchors(book)
        pdfTextNotice = null
        pdfAnchors = anchors
        // The book says ready but its cached text is gone (cache wiped, restore from another
        // device): probe once more. The flag keeps an unwritable cache from looping forever.
        if (anchors == null && !pdfTextReprobed) {
            pdfTextReprobed = true
            pdfTextState = TEXT_UNKNOWN
        }
    }
    // The text layer is probed once in the background: the original view never waits for it.
    LaunchedEffect(book.id, retry, canHaveTextView, pdfTextState) {
        if (!canHaveTextView || pdfTextState != TEXT_UNKNOWN) return@LaunchedEffect
        val state = try {
            repository.preparePdfText(repository.getBook(book.id) ?: book)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed probe stays unknown so a later visit may retry it.
            return@LaunchedEffect
        }
        pdfTextState = state
    }
    LaunchedEffect(book.id, retry, textSettings, isText) {
        error = null
        // A theme switch keeps every line break, so repaint in place instead of rebuilding the book.
        val active = session as? TextPageSource
        val settings = textSettings
        if (active != null && settings != null && active.keepsLayoutFor(settings)) {
            pendingOffset = null
            active.restyle(settings.theme)
            return@LaunchedEffect
        }
        // A long book measures every paragraph again. While an older layout is on screen, wait for
        // the taps to settle so a run of A+/A- costs one measurement instead of one per tap.
        if (active != null) {
            delay(REFLOW_SETTLE_MS)
        }
        var pending: PageSource? = null
        try {
            val current = repository.getBook(book.id) ?: book
            val cached = textDocument
            // A text book reopens at its character anchor; pages are only a window on that text.
            val anchor = pendingOffset ?: current.textOffset.toLong()
            val opened = when {
                // Font sizes are in sp, so the real density decides how large the page text renders.
                settings == null -> repository.openBook(current)
                cached != null -> repository.reflowText(
                    current, cached, settings, textPage, density.density, anchor.toInt(),
                )
                current.format == FORMAT_PDF -> repository.openPdfText(
                    current, settings, textPage, density.density, anchor.toInt(),
                )
                else -> repository.openBook(
                    current, settings, textPage, density.density, anchor.toInt(),
                )
            }
            pending = opened
            val text = opened as? TextPageSource
            var readingOffset = anchor
            // Rows written before character anchors stored a page number; translate it once by ratio.
            if (text != null && pendingOffset == null && anchor == 0L) {
                val legacyPage = if (current.format == FORMAT_PDF) current.textPage else current.currentPage
                val legacyCount = if (current.format == FORMAT_PDF) current.textPageCount else current.pageCount
                if (legacyPage > 0 && legacyCount > 0 && text.characterCount > 0) {
                    readingOffset = (text.characterCount.toLong() * legacyPage / legacyCount)
                        .coerceIn(0L, (text.characterCount - 1).toLong())
                    text.jumpToOffset(readingOffset.toInt())
                }
            }
            val startPage = if (text != null) {
                text.pageForCharOffset(readingOffset).coerceIn(0, opened.pageCount - 1)
            } else {
                current.currentPage.coerceIn(0, opened.pageCount - 1)
            }
            val startSize = opened.pageSize(startPage)
            // Publishing is the final step, so a failed reopen leaves the previous layout readable.
            val previous = session
            pending = null
            restoredSize = startSize
            restorePage = if (text != null) startPage else null
            pendingOffset = null
            session = opened
            if (text != null) textDocument = text.document
            if (text != null && (current.format == FORMAT_TEXT || current.format == FORMAT_EPUB) &&
                current.textAnchors == 0
            ) {
                // One-time conversion; a failure only delays it to the next visit.
                try { repository.migrateTextBookmarks(current, text.characterCount) } catch (_: Exception) { }
            }
            if (previous != null) closer.launch { previous.close() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message ?: "这本书暂时无法打开，文件可能已损坏"
            // A live layout keeps working; only a first open without one needs the error screen.
            if (session == null) error = message
        } finally {
            // A source that was cancelled before it reached the UI is released here instead.
            pending?.let { stale -> closer.launch { stale.close() } }
        }
    }
    // Settings changes reopen the text layout; the captured offset keeps the reading spot.
    val handleTextSettings: (TextReadingSettings, Long) -> Unit = { next, offset ->
        pendingOffset = offset
        onTextReading(next.fontSizeSp, next.lineSpacing.id, next.theme.id)
    }
    // Switching between the original pages and the flowing text keeps the exact reading spot.
    val switchView: (Boolean, Long) -> Unit = { wanted, offset ->
        pendingOffset = offset
        textView = wanted
        onView(wanted)
    }
    BackHandler(onBack = onBack)
    val renderer = session
    if (renderer != null) {
        val bookmarkFlow = remember(book.id) {
            if (book.format == FORMAT_TEXT || book.format == FORMAT_EPUB) repository.textBookmarks(book.id)
            else repository.bookmarks(book.id)
        }
        val bookmarks by bookmarkFlow.collectAsStateWithLifecycle(emptyList())
        ReaderContent(book, renderer, restoredSize!!, bookmarks, preferences, brightness,
            textSettings, restorePage,
            pdfTextView = isPdf && textView,
            pdfAnchors = pdfAnchors,
            viewState = if (isPdf) PdfViewState(
                textView = textView,
                available = pdfTextState == TEXT_READY && pdfAnchors != null,
                preparing = canHaveTextView && pdfTextState == TEXT_UNKNOWN,
                notice = if (canHaveTextView) {
                    // The verdict may claim ready while its cached text is unreadable.
                    pdfTextNotice ?: if (pdfTextState == TEXT_READY && pdfAnchors == null) {
                        "文字版数据缺失，重新打开这本书会再次识别。"
                    } else null
                } else "漫画 PDF 只支持原版阅读。",
                retryable = canHaveTextView && pdfTextState == TEXT_UNSUPPORTED,
            ) else null,
            onSwitchView = switchView,
            onRetryTextView = {
                pdfTextReprobed = false
                scope.launch {
                    pdfTextState = try {
                        repository.forgetPdfText(repository.getBook(book.id) ?: book)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        pdfTextState
                    }
                }
            },
            onBack, onPosition, onBookmark, onBrightness, onRightToLeft,
            handleTextSettings, onTextPosition, onRestoreConsumed = { restorePage = null })
    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (error == null) CircularProgressIndicator()
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(error!!)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperAction("返回书架", onBack)
                val folderBook = book.format == FORMAT_FOLDER
                PaperAction(
                    if (relinking) "正在读取…" else if (folderBook) "重新选择原文件夹" else "重新选择原文件",
                    {
                        if (folderBook) pickFolder.launch(null)
                        else pickOriginal.launch(relinkMimeTypes(book.format))
                    },
                    enabled = !relinking, primary = true,
                )
            }
            // A text view that cannot open must never lock the reader out of the original pages.
            if (isPdf && textView) {
                PaperAction("用原版阅读", {
                    pendingOffset = null
                    textView = false
                    onView(false)
                    retry++
                }, enabled = !relinking)
            } else {
                PaperAction("重试", { retry++ }, enabled = !relinking)
            }
        }
    }
}

private fun relinkMimeTypes(format: Int): Array<String> = when (format) {
    FORMAT_ARCHIVE -> arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/vnd.comicbook+zip",
        "application/x-cbz",
        "application/epub+zip",
        "application/octet-stream",
    )
    FORMAT_TEXT -> arrayOf("text/plain", "application/octet-stream")
    FORMAT_EPUB -> arrayOf("application/epub+zip", "application/zip", "application/octet-stream")
    else -> arrayOf("application/pdf")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
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
    onRestoreConsumed: () -> Unit,
) {
    val restoredPage = remember(book.id) { book.currentPage }
    var mode by rememberSaveable(book.id) { mutableStateOf(book.mode.ifBlank { preferences.defaultMode }) }
    val initialPage = (restorePage ?: book.currentPage).coerceIn(0, session.pageCount - 1)
    var currentPage by rememberSaveable(book.id) { mutableIntStateOf(initialPage) }
    // A chapter window grows when the reader reaches its edge; Compose must observe the new count.
    var pagesVersion by remember(session) { mutableIntStateOf(0) }
    val pageCount = remember(session, pagesVersion) { session.pageCount }
    var pendingJump by remember(session) { mutableStateOf<Int?>(null) }
    var offset by rememberSaveable(book.id) { mutableIntStateOf(book.pageOffset) }
    var chrome by remember(book.id) { mutableStateOf(false) }
    var panel by remember(book.id) { mutableStateOf<ReaderPanel?>(null) }
    var slider by remember { mutableStateOf<Float?>(null) }
    // The slider keeps the requested spot while the target chapter is being loaded, so the thumb
    // and the chapter label never fall back to the previous page mid-jump.
    var pendingPercent by remember { mutableStateOf<Float?>(null) }
    // A view switch already knows its target page, so the new layout starts there without a flash.
    val startPage = (restorePage ?: currentPage).coerceIn(0, pageCount - 1)
    val listState = key(session) { rememberLazyListState(startPage, offset) }
    val pagerState = key(session) {
        rememberPagerState(initialPage = startPage, pageCount = { pageCount })
    }
    val viewport = remember(book.id, mode, session) { ReaderViewportState(listState, pagerState, mode == "vertical") }
    PreloadZoomMetadata(viewport, session, session::cachedPageSize, session::pageSize)
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.trimMemory()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val positionCallback by rememberUpdatedState(onPosition)
    val textPositionCallback by rememberUpdatedState(onTextPosition)
    val view = LocalView.current
    val activity = LocalContext.current as Activity
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
    val motionDuration = if (LocalMoyuReduceMotion.current) 0 else 220
    // How far each side is warmed; the settings screen owns the value so weak phones can ask for less.
    val prefetchRadius = preferences.prefetchRadius.coerceIn(MIN_PREFETCH_RADIUS, MAX_PREFETCH_RADIUS)
    val jump: (Int) -> Unit = { value ->
        val target = value.coerceIn(0, pageCount - 1)
        viewport.resetForJump(target)
        if (mode != "vertical") scope.launch { pagerState.scrollToPage(target) }
    }
    // A chapter load can change the window and its page indices; this publishes the new page first.
    val applyJump: (Int) -> Unit = { page ->
        pagesVersion++
        pendingJump = page.coerceAtLeast(0)
    }
    val jumpProgress: (Int) -> Unit = { percent ->
        val source = session as? TextPageSource
        if (source == null) jump(percent) else scope.launch {
            val offset = (source.characterCount.toLong() * percent.coerceIn(0, 100) / 100).toInt()
            val page = source.jumpToOffset(offset)
            applyJump(page)
        }
    }
    // Panels close themselves through this wrapper, so no panel has to know about chrome state.
    val jumpAndClose: (Int) -> Unit = { page -> jump(page); panel = null }
    val textSource = session as? TextPageSource
    // Pages are replaced when a chapter is loaded; the revision must invalidate the cached percent,
    // otherwise a TOC jump that lands on the same page index leaves the old chapter label on screen.
    val textPagePercent = remember(textSource, currentPage, pageCount, pagesVersion) {
        textSource?.progressPercentOfPage(currentPage) ?: 0
    }
    // Bookmarks, progress and the page label stay in PDF pages, whichever view is on screen.
    val pdfPage = if (pdfTextView && pdfAnchors != null && textSource != null) {
        PdfTextLayout.pageForOffset(pdfAnchors, textSource.charOffsetOfPage(currentPage).toInt())
    } else currentPage
    val jumpPdfPage: (Int) -> Unit = { page ->
        val source = textSource
        if (pdfTextView && pdfAnchors != null && source != null) {
            val offset = PdfTextLayout.offsetOfPage(pdfAnchors, page)
            scope.launch { applyJump(source.jumpToOffset(offset)) }
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
    // Pure text marks are character anchors: window pages disappear when type or chapter changes.
    val pureText = book.format == FORMAT_TEXT || book.format == FORMAT_EPUB
    val offsetMarks = pureText && textSource != null
    val currentMarker = if (offsetMarks) textSource!!.charOffsetOfPage(currentPage).toInt() else pdfPage
    val markLabel: (Int) -> String = if (textSource != null && (pureText || pdfTextView)) {
        { mark ->
            if (pureText) {
                textSource.chapterTitleForOffset(mark) ?: "正文"
            } else {
                val offset = pdfAnchors?.let { PdfTextLayout.offsetOfPage(it, mark) } ?: 0
                textSource.chapterTitleForOffset(offset) ?: "正文"
            }
        }
    } else {
        { page -> "第 ${bookmarkPageNumber(page)} 页" }
    }
    val jumpMarker: (Int) -> Unit = if (offsetMarks) {
        { offset ->
            scope.launch {
                val target = textSource?.jumpToOffset(offset)
                if (target != null) {
                    applyJump(target)
                    panel = null
                }
            }
        }
    } else jumpPdfAndClose
    // A text page knows its exact char anchor, so switching views never loses the reading spot.
    val switchView: (Boolean) -> Unit = { wanted ->
        if (wanted != pdfTextView) {
            val anchors = pdfAnchors
            val offset = if (wanted && anchors != null) {
                PdfTextLayout.offsetOfPage(anchors, pdfPage).toLong()
            } else {
                textSource?.charOffsetOfPage(currentPage) ?: 0L
            }
            onSwitchView(wanted, offset)
        }
    }
    // The text view reports text pages; the PDF view reports its own pages.
    val reportPosition: (Int, Int, String) -> Unit = { page, scroll, mode ->
        val source = textSource
        if (source != null) {
            val offset = source.charOffsetOfPage(page)
            textPositionCallback(offset.toInt(), source.progressPercentOfPage(page))
            val anchors = if (pdfTextView) pdfAnchors else null
            if (anchors != null) {
                positionCallback(PdfTextLayout.pageForOffset(anchors, offset.toInt()), 0, mode)
            } else {
                // Pages are window-local; only the mode belongs to the PDF progress row now.
                positionCallback(0, 0, mode)
            }
        } else {
            positionCallback(page, scroll, mode)
        }
    }
    // Any typesetting change re-lays the book; a char offset maps the old page to the new one.
    val changeText: ((TextReadingSettings) -> TextReadingSettings) -> Unit = { transform ->
        val current = textSettings
        if (current != null) {
            val offset = (session as? TextPageSource)?.charOffsetOfPage(currentPage) ?: 0L
            onTextSettings(transform(current), offset)
        }
    }
    // The settle collector runs a frame after the page stops; leaving must save the page that is
    // actually on screen or a quick exit records the previous page.
    val saveVisiblePosition by rememberUpdatedState {
        if (restorePage == null && pendingJump == null && pageCount > 0) {
            val page = if (mode == "vertical") listState.firstVisibleItemIndex else pagerState.currentPage
            val safePage = page.coerceIn(0, pageCount - 1)
            val scroll = if (mode == "vertical") {
                normalizedPageOffset(listState.firstVisibleItemScrollOffset, viewport.measuredZoom)
            } else {
                0
            }
            reportPosition(safePage, scroll, mode)
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
    LaunchedEffect(restorePage, mode) {
        val target = restorePage ?: return@LaunchedEffect
        // Override any saved window page before the first position report can observe it.
        currentPage = target
        viewport.resetForJump(target)
        if (mode == "vertical") listState.scrollToItem(target, offset) else pagerState.scrollToPage(target)
        onRestoreConsumed()
    }
    // Reading uses its own brightness; leaving the reader hands the window back to the system.
    DisposableEffect(brightness) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        val params = window.attributes
        params.screenBrightness = if (brightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else brightness
        window.attributes = params
        onDispose {
            val restored = window.attributes
            restored.screenBrightness = previous
            window.attributes = restored
        }
    }
    DisposableEffect(preferences.keepScreenOn) {
        if (preferences.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(chrome, light) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(viewport, restorePage) {
        // No page report may race the restore jump: otherwise page zero can overwrite the anchor.
        if (restorePage != null) return@LaunchedEffect
        if (mode == "vertical") {
            listState.scrollToItem(currentPage.coerceIn(0, pageCount - 1), offset)
            snapshotFlow {
                if (pendingJump != null || viewport.hasPendingAnchor) null
                else listState.firstVisibleItemIndex to
                    normalizedPageOffset(listState.firstVisibleItemScrollOffset, viewport.measuredZoom)
            }.distinctUntilChanged().collect { position ->
                if (position != null) {
                    val (page, scroll) = position
                    currentPage = page
                    offset = scroll
                    reportPosition(page, scroll, mode)
                }
            }
        } else {
            pagerState.scrollToPage(currentPage.coerceIn(0, pageCount - 1))
            snapshotFlow {
                if (pendingJump != null || viewport.hasPendingAnchor) null
                else pagerState.settledPage
            }.distinctUntilChanged().collect { page ->
                if (page != null) {
                    currentPage = page
                    offset = 0
                    reportPosition(page, 0, mode)
                }
            }
        }
    }
    LaunchedEffect(pendingJump) {
        if (pendingJump == null) pendingPercent = null
    }
    LaunchedEffect(viewport) {
        snapshotFlow { if (mode == "vertical") listState.isScrollInProgress else pagerState.isScrollInProgress }
            .distinctUntilChanged().collect { scrolling -> if (scrolling) chrome = false }
    }
    LaunchedEffect(session, currentPage, pageCount, mode, restorePage) {
        // Chapter windows are grown just before the reader reaches an edge, so a page turn never
        // runs into a "no page" wall. Prepending shifts indices, so the visible page is restored.
        if (restorePage != null) return@LaunchedEffect
        val source = session as? TextPageSource ?: return@LaunchedEffect
        if (currentPage >= pageCount - 2 && source.hasNextChapter()) {
            val added = source.loadNextChapter()
            if (added > 0) pagesVersion++
        }
        if (currentPage <= 1 && source.hasPreviousChapter()) {
            val added = source.loadPreviousChapter()
            if (added > 0) {
                pagesVersion++
                currentPage += added
                pendingJump = currentPage
            }
        }
    }
    LaunchedEffect(pendingJump, pageCount, mode) {
        val target = pendingJump ?: return@LaunchedEffect
        if (target !in 0 until pageCount) return@LaunchedEffect
        currentPage = target
        offset = 0
        viewport.resetForJump(target)
        if (mode == "vertical") listState.scrollToItem(target, 0)
        else pagerState.scrollToPage(target)
        pendingJump = null
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
    Box(Modifier.fillMaxSize().background(readerBackground)) {
        ReaderViewport(viewport, onTap = { point ->
            val fraction = viewport.pageFraction(point) ?: .5f
            when {
                mode == "horizontal" && viewport.zoom <= 1.01f && fraction < .23f -> jump(currentPage + if (preferences.rightToLeft) 1 else -1)
                mode == "horizontal" && viewport.zoom <= 1.01f && fraction > .77f -> jump(currentPage + if (preferences.rightToLeft) -1 else 1)
                else -> chrome = !chrome
            }
        }, onGesture = { chrome = false }) { baseSize, bounds, zoom, expanded ->
            PrefetchPages(session, currentPage, pageCount, baseSize, mode, scrolling, zoom, prefetchRadius)
            if (mode == "vertical") {
                LazyColumn(state = listState, modifier = expanded.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp * zoom)) {
                    items(pageCount, key = { it }) { page ->
                        PdfPage(session, page, Modifier.fillMaxWidth(),
                            initialSize = if (!pdfTextView && page == restoredPage) restoredSize else null,
                            baseViewport = baseSize, viewportBounds = bounds, zoom = zoom,
                            keep = page in keepPages, scrolling = scrolling, visible = page in visiblePages,
                            pageBackground = pageBackground, onPageReady = viewport::pageReady)
                    }
                }
            } else {
                // Keep one page on each side composed: it already owns its bitmap, so the page a
                // swipe reveals is drawn from the first frame. A page composed only mid-swipe has
                // to show a spinner, then a half-width preview, then the full page — the blink
                // that turning back used to cause.
                HorizontalPager(state = pagerState, modifier = expanded.fillMaxSize(), reverseLayout = preferences.rightToLeft,
                    userScrollEnabled = zoom <= 1.01f, beyondViewportPageCount = 1) { page ->
                    PdfPage(session, page, Modifier.fillMaxSize(), fit = true,
                        baseViewport = baseSize, viewportBounds = bounds, zoom = zoom,
                        // While zoomed no page turn is possible, so only the page on screen keeps a bitmap.
                        keep = zoom <= 1.01f || page == pagerState.currentPage,
                        defer = page != pagerState.currentPage,
                        scrolling = scrolling, pageBackground = pageBackground, onPageReady = viewport::pageReady)
                }
            }
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(motionDuration)) + slideInVertically(tween(motionDuration)) { -it }, exit = fadeOut(tween(motionDuration)) + slideOutVertically(tween(motionDuration)) { -it }) {
            Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .97f), shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PaperIconAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架", {
                        saveVisiblePosition()
                        onBack()
                    })
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        // Text books keep the chapter heading on the page itself; the chrome only
                        // carries the book title, so the chapter name is not printed twice.
                        if (textSource == null) {
                            Text(
                                "${currentPage + 1} / ${pageCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // The PDF view switch belongs with the back button, not a toolbar page away.
                    val switchState = viewState
                    if (switchState != null && (switchState.available || switchState.preparing || switchState.retryable)) {
                        ReaderViewSwitch(switchState, onPick = { wanted -> switchView(wanted) },
                            onOpen = { panel = ReaderPanel.View })
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(motionDuration)) + slideInVertically(tween(motionDuration)) { it }, exit = fadeOut(tween(motionDuration)) + slideOutVertically(tween(motionDuration)) { it }) {
            val switchMode: (String) -> Unit = { value ->
                if (mode != value) {
                    viewport.resetForJump(currentPage)
                    offset = 0
                    mode = value
                    reportPosition(currentPage, 0, value)
                }
                panel = null
            }
            Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shadowElevation = 10.dp) {
                // One panel only: sub views replace the tools in place instead of stacking a dialog on top.
                Column(Modifier.fillMaxWidth().navigationBarsPadding().animateContentSize(tween(motionDuration))) {
                    Box(
                        Modifier.fillMaxWidth().height(if (panel == null) 38.dp else 30.dp)
                            .pointerInput(panel) {
                                var dx = 0f
                                var dy = 0f
                                detectDragGestures(
                                    onDragStart = { dx = 0f; dy = 0f },
                                    onDragEnd = {
                                        if (dx > 70f || dy > 70f) {
                                            if (panel != null) panel = null else chrome = false
                                        }
                                    },
                                ) { change, drag ->
                                    dx += drag.x
                                    dy += drag.y
                                    change.consume()
                                }
                            }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClickLabel = "收起阅读面板") { if (panel != null) panel = null else chrome = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    val openPanel = panel
                    if (openPanel != null) {
                        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            PaperIconAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回阅读工具栏", { panel = null })
                            Text(openPanel.title, Modifier.weight(1f).padding(start = 2.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        PaperDivider(Modifier.padding(horizontal = 20.dp))
                    }
                    when (panel) {
                        null -> {
                            val source = textSource
                            val shownPage = (slider?.roundToInt() ?: currentPage)
                                .coerceIn(0, pageCount - 1)
                            val shownProgress = if (source != null) {
                                (pendingPercent ?: slider ?: textPagePercent.toFloat())
                                    .coerceIn(0f, 100f)
                            } else null
                            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    when {
                                        source != null && shownProgress != null -> {
                                            val offset = (source.characterCount.toLong() * shownProgress / 100f).toInt()
                                            source.chapterTitleForOffset(offset) ?: "正文"
                                        }
                                        else -> "${shownPage + 1} / ${pageCount}"
                                    },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                if (shownProgress == null) {
                                    Text(
                                        "${((shownPage + 1f) / pageCount * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Box(Modifier.padding(horizontal = 22.dp)) {
                                PaperSlider(
                                    value = if (shownProgress != null) shownProgress else slider ?: currentPage.toFloat(),
                                    onValueChange = { slider = it },
                                    onValueChangeFinished = {
                                        val value = slider
                                        slider = null
                                        if (value != null) {
                                            if (source != null) {
                                                // Keep the requested position visible while the
                                                // target chapter page is being loaded.
                                                pendingPercent = value
                                                jumpProgress(value.roundToInt())
                                            } else {
                                                jump(value.roundToInt())
                                            }
                                        }
                                    },
                                    valueRange = if (source != null) 0f..100f
                                    else 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                                    enabled = if (source != null) true else pageCount > 1,
                                )
                            }
                            ReaderToolRow(
                                pdfPage = currentMarker,
                                bookmarks = bookmarks,
                                isText = isText,
                                mode = mode,
                                brightness = brightness,
                                onPanel = { panel = it },
                            )
                        }
                        ReaderPanel.Bookmarks -> BookmarksPanel(
                            bookmarks = bookmarks,
                            currentPage = currentMarker,
                            onToggle = onBookmark,
                            onJump = jumpMarker,
                            label = markLabel,
                        )
                        ReaderPanel.Pages -> PageGridPanel(session, currentPage, jumpAndClose)
                        ReaderPanel.Mode -> ReadingModePanel(mode, preferences.rightToLeft) { value, toLeft ->
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
                                ChapterPanel(source, source.chapterIndexForPage(currentPage)) { chapter ->
                                    scope.launch {
                                        val page = source.jumpToChapter(chapter)
                                        applyJump(page)
                                        panel = null
                                    }
                                }
                            }
                        }
                        ReaderPanel.View -> viewState?.let { state ->
                            ViewPanel(state, onPick = { wanted ->
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
    }
}

/**
 * Warms the pages before and after the reader at the exact width [PdfPage] will request. It only
 * runs once the reader is still, so the visible page and the swipe in progress keep the renderer
 * to themselves. Nearest pages go first; the cache is deliberately larger than the radius.
 */
@Composable
private fun PrefetchPages(
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

