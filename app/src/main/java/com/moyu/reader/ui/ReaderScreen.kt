package com.moyu.reader.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
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
import androidx.core.content.ContextCompat
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
import com.moyu.reader.data.PDF_TEXT_VIEW_AVAILABLE
import com.moyu.reader.data.ReaderPreferences
import com.moyu.reader.data.READ_VIEW_TEXT
import com.moyu.reader.data.SpeechPreferences
import com.moyu.reader.data.TEXT_READY
import com.moyu.reader.data.TEXT_UNKNOWN
import com.moyu.reader.data.TEXT_UNSUPPORTED
import com.moyu.reader.page.PageSource
import com.moyu.reader.pdf.PageSize
import com.moyu.reader.speech.SpeechEngineInfo
import com.moyu.reader.speech.SpeechEngines
import com.moyu.reader.speech.SpeechReader
import com.moyu.reader.speech.SpeechService
import com.moyu.reader.speech.SpeechVoice
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
// Load the next chapter while this many pages are still ahead: a fast fling must not reach the
// end of the loaded window before the chapter arrives, or it stops dead at the last page.
private const val NEXT_CHAPTER_PAGES_LEFT = 6
// Keep this many pages of the previous chapter loaded before the reader settles near the start.
// Prepending shifts page indices, so it must not run while a fling is still moving.
private const val PREVIOUS_CHAPTER_PAGES_LEFT = 6

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
    speechPreferences: SpeechPreferences = SpeechPreferences(),
    onSpeech: (String?, String?, Float) -> Unit = { _, _, _ -> },
    onSpeechBackground: (Boolean) -> Unit = {},
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
    val canHaveTextView = PDF_TEXT_VIEW_AVAILABLE && isPdf && book.kind == KIND_EBOOK
    // A PDF reopens in the view it was last read in, once its text layer was proven usable.
    var textView by rememberSaveable(book.id) {
        mutableStateOf(PDF_TEXT_VIEW_AVAILABLE && book.hasTextView && book.readView == READ_VIEW_TEXT)
    }
    var pdfTextState by remember(book.id) { mutableIntStateOf(book.textState) }
    var pdfTextNotice by remember(book.id) { mutableStateOf<String?>(null) }
    var pdfAnchors by remember(book.id) { mutableStateOf<IntArray?>(null) }
    // One re-probe is allowed when the stored verdict outlives its cache; never more than one.
    var pdfTextReprobed by remember(book.id) { mutableStateOf(false) }
    // A char offset captured before a view switch; the new layout maps it back to a page.
    var pendingOffset by remember(book.id) { mutableStateOf<Long?>(null) }
    // A char target pending a fresh chapter window; it bypasses the typesetting debounce.
    var chapterJump by remember(book.id) { mutableStateOf(false) }
    // A separate revision for a chapter/seek reopen; retry also invalidates the parsed document.
    var openRevision by remember(book.id) { mutableIntStateOf(0) }
    // The page pixel derived from the saved line anchor for the next text window. It is also the
    // page offset used by non-text sources when no text anchor exists.
    var restoreScroll by remember(book.id) { mutableIntStateOf(book.pageOffset) }
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
    val isText = book.isTextBook || (isPdf && textView)
    val context = LocalContext.current
    // Reading aloud runs on the device's own speech engine. The engines are looked up once and
    // kept: probing them starts them, which is far too slow to do on every visit.
    val speech = remember(book.id) { SpeechReader(context) }
    // Background playback shows a notification; ask for the notification permission on first use.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val setSpeechBackground: (Boolean) -> Unit = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onSpeechBackground(enabled)
    }
    DisposableEffect(book.id) { onDispose { speech.release() } }
    // A reading that survived in the background must not outlive the reader either.
    DisposableEffect(book.id) {
        onDispose {
            SpeechService.stop(context.applicationContext)
            SpeechService.stopReading = null
        }
    }
    var engines by remember(book.id) { mutableStateOf<List<SpeechEngineInfo>>(emptyList()) }
    LaunchedEffect(book.id, isText) {
        if (!isText) return@LaunchedEffect
        val found = try {
            SpeechEngines.load(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        // Only engines that can read Chinese are of any use to a reader of Chinese books.
        engines = found.filter { it.chinese }
    }
    // Covers belong to the shelf. While a book is open they are dead weight: the page cache the
    // reader needs is worth more than artwork of a list that is not on screen. They are decoded
    // again when the reader goes back.
    LaunchedEffect(book.id) { clearCoverCache() }
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
                val matched = repository.importFile(uri, book.kind).book
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
        if (!isPdf || !PDF_TEXT_VIEW_AVAILABLE) {
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
    // The PDF text view is hidden; a book last read in it goes back to its original pages so the
    // saved position, the shelf label and the stored preference all speak the same layout again.
    LaunchedEffect(book.id) {
        if (isPdf && !PDF_TEXT_VIEW_AVAILABLE && book.readView == READ_VIEW_TEXT) onView(false)
    }
    LaunchedEffect(book.id, retry, openRevision, textSettings, isText) {
        // Consume the jump request before any suspension: a newer jump restarts this effect, and
        // its own request must never be cleared by the effect it cancelled.
        val jumping = chapterJump
        chapterJump = false
        error = null
        // A theme switch keeps every line break, so repaint in place instead of rebuilding the book.
        val active = session as? TextPageSource
        val settings = textSettings
        if (active != null && settings != null && active.keepsLayoutFor(settings) && !jumping) {
            pendingOffset = null
            active.restyle(settings.theme)
            return@LaunchedEffect
        }
        // A long book measures every paragraph again. While an older layout is on screen, wait for
        // the taps to settle so a run of A+/A- costs one measurement instead of one per tap.
        if (active != null && !jumping) {
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
            // The line anchor is the only stored text position. The page and its pixel offset are
            // derived from the freshly built layout, never from a pixel saved in an older layout.
            val openedPosition = text?.positionForCharOffset(readingOffset.toInt(), textPage.width)
            val startPage = if (text != null) {
                (openedPosition?.page ?: text.pageForCharOffset(readingOffset))
                    .coerceIn(0, opened.pageCount - 1)
            } else {
                current.currentPage.coerceIn(0, opened.pageCount - 1)
            }
            val startScroll = if (text != null) openedPosition?.pageOffset ?: 0 else current.pageOffset
            val startSize = opened.pageSize(startPage)
            // Publishing is the final step, so a failed reopen leaves the previous layout readable.
            val previous = session
            pending = null
            restoredSize = startSize
            restoreScroll = startScroll.coerceAtLeast(0)
            restorePage = if (text != null) startPage else null
            pendingOffset = null
            session = opened
            if (text != null) textDocument = text.document
            if (text != null && current.isTextBook && current.textAnchors == 0) {
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
    // Every explicit chapter/seek jump opens a fresh one-chapter window at the target anchor.
    // Nothing in the old window survives, so page indices, caches and labels cannot mix snapshots.
    val openAtChar: (Int) -> Unit = { offset ->
        if (session is TextPageSource) {
            chapterJump = true
            pendingOffset = offset.toLong()
            openRevision++
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
            if (book.isTextBook) repository.textBookmarks(book.id)
            else repository.bookmarks(book.id)
        }
        val bookmarks by bookmarkFlow.collectAsStateWithLifecycle(emptyList())
        ReaderContent(book, renderer, restoredSize!!, bookmarks, preferences, brightness,
            textSettings, restorePage,
            pdfTextView = isPdf && textView,
            pdfAnchors = pdfAnchors,
            viewState = if (isPdf && PDF_TEXT_VIEW_AVAILABLE) PdfViewState(
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
            handleTextSettings, onTextPosition,
            speech = speech,
            engines = engines,
            speechPreferences = speechPreferences,
            onSpeech = onSpeech,
            onSpeechBackground = setSpeechBackground,
            initialScroll = restoreScroll,
            onRestoreConsumed = { restorePage = null },
            onChapterJump = { chapter ->
                (session as? TextPageSource)?.let { openAtChar(it.chapterStartOffset(chapter)) }
            },
            onSeek = { percent ->
                (session as? TextPageSource)?.let {
                    openAtChar((it.characterCount.toLong() * percent.coerceIn(0, 100) / 100).toInt())
                }
            },
            onAnchorJump = openAtChar,
        )
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
    speech: SpeechReader,
    engines: List<SpeechEngineInfo>,
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
    var mode by rememberSaveable(book.id) { mutableStateOf(book.mode.ifBlank { preferences.defaultMode }) }
    val initialPage = (restorePage ?: book.currentPage).coerceIn(0, session.pageCount - 1)
    var currentPage by remember(session, book.id) { mutableIntStateOf(initialPage) }
    // A chapter window grows when the reader reaches its edge; Compose must observe the new count.
    var pagesVersion by remember(session) { mutableIntStateOf(0) }
    val pageCount = remember(session, pagesVersion) { session.pageCount }
    var pendingJump by remember(session) { mutableStateOf<Int?>(null) }
    // A chapter window prepend shifts page indices; this keeps the visible in-page pixel with it.
    var pendingJumpScroll by remember(session) { mutableIntStateOf(0) }
    // Where a reading-mode switch is heading, kept until the new layout has actually landed so an
    // exit in between still saves the page the reader switched to, not the old one.
    var modeSwitch by remember(session) { mutableStateOf<Pair<Int, Int>?>(null) }
    var offset by remember(session, book.id) { mutableIntStateOf(initialScroll) }
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
    // A view switch already knows its target page, so the new layout starts there without a flash.
    val startPage = (restorePage ?: currentPage).coerceIn(0, pageCount - 1)
    val listState = key(session) { rememberLazyListState(startPage, offset) }
    val pagerState = key(session) {
        rememberPagerState(initialPage = startPage, pageCount = { pageCount })
    }
    // One viewport lives for the whole session: switching between vertical scrolling and horizontal
    // paging keeps the same pending-jump gate, so neither state can report a stale page.
    val viewport = remember(book.id, session) { ReaderViewportState(listState, pagerState, mode == "vertical") }
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
    // The key must include the flag: the observer reads it when the app stops, not when it is set up.
    DisposableEffect(lifecycleOwner, session, speech, speechPreferences.background) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.trimMemory()
                // Background playback keeps going only when the reader asked for it; otherwise
                // the phone would keep talking with no notification behind it.
                if (!speechPreferences.background) speech.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val positionCallback by rememberUpdatedState(onPosition)
    val textPositionCallback by rememberUpdatedState(onTextPosition)
    val view = LocalView.current
    val activity = LocalContext.current as Activity
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
    // Panels close themselves through this wrapper, so no panel has to know about chrome state.
    val jumpAndClose: (Int) -> Unit = { page -> jump(page); panel = null }
    val textSource = session as? TextPageSource
    // Pure text marks are character anchors: window pages disappear when type or chapter changes.
    val pureText = book.isTextBook
    val offsetMarks = pureText && textSource != null
    // The one formula every text position goes through: page + in-page pixel -> first visible line.
    val anchorAt: (TextPageSource, Int, Int, Boolean) -> Int = { source, page, scroll, vertical ->
        if (vertical) {
            source.charOffsetAtPageOffset(page, scroll, viewport.viewport.width)
        } else {
            source.charOffsetOfPage(page).toInt()
        }
    }
    // Read the live line only when the chrome opens or a setting changes, not on every scroll
    // frame. Persistence uses the same formula directly in reportPosition.
    val currentLineAnchorNow: () -> Int = {
        val source = textSource
        if (source != null) {
            val page = if (mode == "vertical") listState.firstVisibleItemIndex else pagerState.currentPage
            anchorAt(
                source,
                page,
                normalizedPageOffset(listState.firstVisibleItemScrollOffset, viewport.measuredZoom),
                mode == "vertical",
            )
        } else 0
    }
    // The frozen anchor shown by the open toolbar. It is captured when the chrome is revealed.
    var chromeAnchor by remember(session, book.id) { mutableIntStateOf(0) }
    // The engine in use: the one the reader chose, else the device default, else the first engine
    // that can read Chinese. A voice belongs to its engine, so it is looked up there.
    val speechEngine = remember(engines, speechPreferences.engine) {
        engines.firstOrNull { it.packageName == speechPreferences.engine }?.packageName
            ?: engines.firstOrNull { it.packageName == SpeechEngines.defaultEngine(activity) }?.packageName
            ?: engines.firstOrNull()?.packageName
    }
    val speechVoices = remember(engines, speechEngine) {
        engines.firstOrNull { it.packageName == speechEngine }?.voices.orEmpty()
    }
    val speechVoice = remember(speechVoices, speechPreferences.voice) {
        // The stored value is the voice key (name + locale); older rows stored only the name.
        speechVoices.firstOrNull { it.key == speechPreferences.voice }
            ?: speechVoices.firstOrNull { it.name == speechPreferences.voice }
            // Nothing chosen yet: the engine's first voice is what it would use anyway.
            ?: speechVoices.firstOrNull()
    }
    // Reading aloud needs text to read and an engine that can read Chinese.
    val speechAvailable = engines.isNotEmpty() && textSource != null
    // Starting the reading takes the text from the line on screen to the end of the book.
    val startSpeech: () -> Unit = {
        val source = textSource
        if (source != null) {
            val paragraphs = source.paragraphsFrom(currentLineAnchorNow())
            if (paragraphs.isNotEmpty()) {
                scope.launch {
                    speech.start(paragraphs, speechEngine, speechVoice, speechPreferences.rate)
                }
            }
        }
    }
    val toggleSpeech: () -> Unit = {
        when (speech.status) {
            SpeechReader.Status.Playing -> speech.pause()
            SpeechReader.Status.Paused -> speech.resume()
            else -> startSpeech()
        }
    }
    val stopSpeech: () -> Unit = { speech.stop() }
    // The sentence being read is marked on the page itself, so the eye can follow the voice.
    val speechHighlight = speech.line?.let { line ->
        TextPageSource.Highlight(line.offset, line.offset + line.text.length)
    }
    // A sleep timer belongs to this reading session; picking a new value restarts the countdown.
    // The remaining seconds are only read inside the speech panel, so the ticking state does not
    // invalidate the whole reader canvas every second.
    var sleepMinutes by remember(book.id) { mutableIntStateOf(0) }
    val sleepRemaining = remember(book.id) { mutableIntStateOf(0) }
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes <= 0) {
            sleepRemaining.intValue = 0
            return@LaunchedEffect
        }
        var seconds = sleepMinutes * 60
        sleepRemaining.intValue = seconds
        while (seconds > 0) {
            delay(1_000L)
            seconds -= 1
            sleepRemaining.intValue = seconds
        }
        speech.stop()
        sleepMinutes = 0
    }
    // Background playback runs in a foreground service: it keeps the process alive and shows
    // what is being read. Without that service the system may freeze the app mid-sentence.
    LaunchedEffect(speechPreferences.background, speech.status, speech.line?.text) {
        val reading = speech.status == SpeechReader.Status.Playing ||
            speech.status == SpeechReader.Status.Paused
        if (speechPreferences.background && reading) {
            SpeechService.start(activity.applicationContext, book.title, speech.line?.text)
        } else {
            SpeechService.stop(activity.applicationContext)
        }
    }
    DisposableEffect(speech) {
        // The notification's stop button reaches this reader while it is alive.
        SpeechService.stopReading = { speech.stop() }
        onDispose { SpeechService.stopReading = null }
    }
    // Keep the sentence being read on screen. A page is a screenful, so following page changes is
    // enough; a sentence that ran past the loaded window reopens it at that offset.
    LaunchedEffect(speech.line?.offset, speech.status, session, mode, restorePage, viewport.viewport) {
        val line = speech.line ?: return@LaunchedEffect
        if (restorePage != null || speech.status != SpeechReader.Status.Playing) return@LaunchedEffect
        val source = textSource ?: return@LaunchedEffect
        // While zoomed the page is larger than the viewport and panning owns the position.
        if (viewport.zoom > 1.01f) return@LaunchedEffect
        if (source.lineAnchorInWindow(line.offset) == null) {
            onAnchorJump(line.offset)
            return@LaunchedEffect
        }
        val position = source.positionForCharOffset(line.offset, viewport.viewport.width)
        if (mode == "vertical") {
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
    val bookmarkAtLine: (Int) -> Int? = { anchor ->
        if (offsetMarks) {
            textSource!!.let { source ->
                bookmarks.firstOrNull { source.lineAnchorInWindow(it) == anchor }
            }
        } else null
    }
    var chromeLineMark by remember(session, book.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(chrome, session, currentPage, mode, pagesVersion, bookmarks) {
        if (chrome) {
            val anchor = currentLineAnchorNow()
            chromeAnchor = anchor
            chromeLineMark = bookmarkAtLine(anchor)
        } else {
            chromeLineMark = null
        }
    }
    // Pages are replaced when a chapter is loaded; the revision must invalidate the cached percent,
    // otherwise a TOC jump that lands on the same page index leaves the old chapter label on screen.
    val textPageFraction = remember(textSource, chromeAnchor, currentPage, pageCount, pagesVersion) {
        val source = textSource
        when {
            source == null -> 0f
            // A line anchor never reaches the book end; the last page is what "read to the end" is.
            source.isLastPageOfBook(currentPage) -> 100f
            else -> source.progressFractionForOffset(chromeAnchor)
        }
    }
    // Bookmarks, progress and the page label stay in PDF pages, whichever view is on screen.
    val pdfPage = if (pdfTextView && pdfAnchors != null && textSource != null) {
        PdfTextLayout.pageForOffset(pdfAnchors, chromeAnchor)
    } else currentPage
    val jumpPdfPage: (Int) -> Unit = { page ->
        val source = textSource
        if (pdfTextView && pdfAnchors != null && source != null) {
            val offset = PdfTextLayout.offsetOfPage(pdfAnchors, page)
            onAnchorJump(offset)
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
        { offset -> onAnchorJump(offset); panel = null }
    } else jumpPdfAndClose
    // A text page knows its exact char anchor, so switching views never loses the reading spot.
    val switchView: (Boolean) -> Unit = { wanted ->
        if (wanted != pdfTextView) {
            val anchors = pdfAnchors
            val offset = if (wanted && anchors != null) {
                PdfTextLayout.offsetOfPage(anchors, pdfPage).toLong()
            } else {
                if (textSource != null) currentLineAnchorNow().toLong() else 0L
            }
            onSwitchView(wanted, offset)
        }
    }
    // The text view reports text pages; the PDF view reports its own pages.
    val reportPosition: (Int, Int, String) -> Unit = { page, scroll, mode ->
        val source = textSource
        if (source != null) {
            // The anchor is the first visible line, not the first character of the page. The page
            // pixel is never stored as the position; it is derived from this anchor on restore.
            val anchor = anchorAt(source, page, scroll, mode == "vertical")
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
    // Any typesetting change re-lays the book; a char offset maps the old page to the new one.
    val changeText: ((TextReadingSettings) -> TextReadingSettings) -> Unit = { transform ->
        val current = textSettings
        if (current != null) {
            val offset = if (session is TextPageSource) currentLineAnchorNow().toLong() else 0L
            onTextSettings(transform(current), offset)
        }
    }
    // A comic's last page may be shorter than the viewport, so it can never become the first
    // visible item. Scrolled to the bottom is then the real "last page"; report it instead of the
    // page before it, or the label, progress and saved position all stop one page short.
    val atComicEnd: () -> Boolean = {
        textSource == null && pageCount > 0 && !listState.canScrollForward &&
            listState.layoutInfo.visibleItemsInfo.let { it.isNotEmpty() && it.size < pageCount }
    }
    // The settle collector runs a frame after the page stops; leaving must save the page that is
    // actually on screen or a quick exit records the previous page.
    val saveVisiblePosition by rememberUpdatedState {
        if (restorePage == null && pageCount > 0) {
            val jump = pendingJump
            val switched = modeSwitch
            val atEnd = mode == "vertical" && atComicEnd()
            val page = when {
                switched != null -> switched.first
                jump != null -> jump
                mode == "vertical" -> if (atEnd) pageCount - 1 else listState.firstVisibleItemIndex
                // targetPage is where the pager was heading when the user left, so a quick exit
                // during the snap animation does not record the previous page.
                else -> pagerState.targetPage
            }
            val safePage = page.coerceIn(0, pageCount - 1)
            val scroll = when {
                switched != null -> if (mode == "vertical") switched.second else 0
                mode != "vertical" -> 0
                // A chapter prepend has already shifted the page index but not yet settled.
                jump != null -> pendingJumpScroll
                atEnd -> 0
                else -> normalizedPageOffset(listState.firstVisibleItemScrollOffset, viewport.measuredZoom)
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
    LaunchedEffect(restorePage, mode, initialScroll) {
        val target = restorePage ?: return@LaunchedEffect
        val restoredOffset = if (mode == "vertical") initialScroll.coerceAtLeast(0) else 0
        // Never suspend here. A user scroll cancels a programmatic scrollToItem/scrollToPage,
        // which used to cancel this effect before onRestoreConsumed(); restorePage then stayed
        // non-null forever, disabling both position reports and next-chapter loading.
        currentPage = target
        offset = restoredOffset
        viewport.resetForJump(target, restoredOffset)
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
    // Keyed by mode as well: switching direction changes which scroll state owns the position.
    LaunchedEffect(viewport, restorePage, mode) {
        // No page report may race the restore jump: otherwise page zero can overwrite the anchor.
        if (restorePage != null) return@LaunchedEffect
        if (mode == "vertical") {
            // The list was created with the restored page/offset and resetForJump has already
            // requested it. Do not call a suspend scroll here either: a user drag would cancel
            // the effect before the snapshotFlow below could ever start.
            snapshotFlow {
                when {
                    pendingJump != null || viewport.hasPendingAnchor -> null
                    // Scrolled to the bottom of a comic: the (short) last page cannot become the
                    // first visible item, so this is the only way to report it as the current page.
                    atComicEnd() -> (pageCount - 1) to 0
                    else -> listState.firstVisibleItemIndex to
                        normalizedPageOffset(listState.firstVisibleItemScrollOffset, viewport.measuredZoom)
                }
            }.distinctUntilChanged().collect { position ->
                if (position != null) {
                    val (page, scroll) = position
                    modeSwitch = null
                    currentPage = page
                    offset = scroll
                    reportPosition(page, scroll, mode)
                }
            }
        } else {
            snapshotFlow {
                if (pendingJump != null || viewport.hasPendingAnchor) null
                else pagerState.settledPage
            }.distinctUntilChanged().collect { page ->
                if (page != null) {
                    modeSwitch = null
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
    LaunchedEffect(viewport, mode) {
        snapshotFlow { if (mode == "vertical") listState.isScrollInProgress else pagerState.isScrollInProgress }
            .distinctUntilChanged().collect { scrolling -> if (scrolling) chrome = false }
    }
    LaunchedEffect(session, pageCount, mode, restorePage) {
        // Chapter windows are grown just before the reader reaches an edge, so a page turn never
        // runs into a "no page" wall. The page is observed instead of being one of the effect keys:
        // a fling changes it every frame, and re-keying the effect cancelled the chapter load
        // before it could finish. The window then ran out under the fling and stopped dead at the
        // last loaded page.
        if (restorePage != null) return@LaunchedEffect
        val source = session as? TextPageSource ?: return@LaunchedEffect
        snapshotFlow {
            currentPage to (if (mode == "vertical") listState.isScrollInProgress else pagerState.isScrollInProgress)
        }.distinctUntilChanged().collect { (page, scrolling) ->
            if (page >= pageCount - NEXT_CHAPTER_PAGES_LEFT && source.hasNextChapter()) {
                // An empty chapter can paginate to zero pages. Keep advancing through such chapters
                // instead of letting one zero-page slice permanently stop the window at its edge.
                var added = 0
                while (added == 0 && source.hasNextChapter()) {
                    added = source.loadNextChapter()
                }
                if (added > 0) pagesVersion++
            }
            // Prepending shifts every page index, so the visible page must be requested again:
            // that cancels a running fling. Wait for the reader to settle, otherwise a backward
            // fling is cut dead in its tracks.
            if (!scrolling && page <= PREVIOUS_CHAPTER_PAGES_LEFT && source.hasPreviousChapter()) {
                val keepScroll = offset
                var added = 0
                while (added == 0 && source.hasPreviousChapter()) {
                    added = source.loadPreviousChapter()
                }
                if (added > 0) {
                    pagesVersion++
                    currentPage += added
                    pendingJump = currentPage
                    // Page indices shifted, the line on screen did not: carry its pixel offset.
                    pendingJumpScroll = keepScroll
                }
            }
        }
    }
    LaunchedEffect(pendingJump, pageCount, mode) {
        val target = pendingJump ?: return@LaunchedEffect
        if (target !in 0 until pageCount) return@LaunchedEffect
        val targetScroll = if (mode == "vertical") pendingJumpScroll else 0
        currentPage = target
        offset = targetScroll
        // resetForJump only requests a scroll; it never suspends and can therefore never be
        // cancelled by a user drag before pendingJump is cleared.
        viewport.resetForJump(target, targetScroll)
        pendingJump = null
        pendingJumpScroll = 0
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
                else -> {
                    // Freeze the line before opening the toolbar so the first panel frame already
                    // shows the correct chapter, bookmark state and progress.
                    if (!chrome) {
                        val anchor = currentLineAnchorNow()
                        chromeAnchor = anchor
                        chromeLineMark = bookmarkAtLine(anchor)
                    }
                    chrome = !chrome
                }
            }
        }, onGesture = { chrome = false }) { baseSize, bounds, zoom, expanded ->
            PrefetchPages(session, currentPage, pageCount, baseSize, mode, scrolling, zoom, prefetchRadius)
            // Flowing text is a single vector drawing surface behind the scrolling pages: the list
            // and the pager only reserve each page's rectangle, and one native layer paints them.
            // It has to stay first in this Box: a native view takes the touch hit, so drawing it
            // under the scrolling surface is what leaves every swipe to the list and the pager.
            Box(expanded.fillMaxSize()) {
                if (textSource != null) {
                    TextPageLayer(
                        source = textSource,
                        vertical = mode == "vertical",
                        listState = listState,
                        pagerState = pagerState,
                        onPageReady = viewport::pageReady,
                        modifier = Modifier.fillMaxSize(),
                        highlight = speechHighlight,
                    )
                }
                if (mode == "vertical") {
                    // PDF and comic pages are whole sheets: a gap would show up as a seam between
                    // them, so they sit flush. Text pages keep a small break so the paper edges
                    // still read as separate pages.
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                        verticalArrangement = if (textSource == null) Arrangement.Top else Arrangement.spacedBy(4.dp * zoom)) {
                        items(pageCount, key = { it }) { page ->
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
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), reverseLayout = preferences.rightToLeft,
                        userScrollEnabled = zoom <= 1.01f, beyondViewportPageCount = 1) { page ->
                        if (textSource != null) {
                            TextPagePlaceholder(textSource, page, Modifier.fillMaxSize(), fit = true)
                        } else {
                            PdfPage(session, page, Modifier.fillMaxSize(), fit = true,
                                baseViewport = baseSize, viewportBounds = bounds, zoom = zoom,
                                // While zoomed no page turn is possible, so only the page on screen keeps a bitmap.
                                keep = zoom <= 1.01f || page == pagerState.currentPage,
                                defer = page != pagerState.currentPage,
                                scrolling = scrolling, pageBackground = pageBackground, onPageReady = viewport::pageReady)
                        }
                    }
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
        AnimatedVisibility(
            visible = !chrome && (speech.status == SpeechReader.Status.Playing || speech.status == SpeechReader.Status.Paused),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(motionDuration)) + slideInVertically(tween(motionDuration)) { it },
            exit = fadeOut(tween(motionDuration)) + slideOutVertically(tween(motionDuration)) { it },
        ) {
            SpeechBar(
                text = speech.line?.text.orEmpty(),
                status = speech.status,
                onToggle = toggleSpeech,
                onStop = stopSpeech,
                modifier = Modifier.navigationBarsPadding().padding(16.dp),
            )
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(motionDuration)) + slideInVertically(tween(motionDuration)) { it }, exit = fadeOut(tween(motionDuration)) + slideOutVertically(tween(motionDuration)) { it }) {
            val switchMode: (String) -> Unit = { value ->
                if (mode != value) {
                    // Read the page from the state that is actually driving the screen. While a
                    // fling is still running the pager has not settled yet, so its target is the
                    // page the reader is looking at.
                    val jump = pendingJump
                    val page = when {
                        jump != null -> jump
                        mode == "vertical" -> listState.firstVisibleItemIndex
                        else -> pagerState.targetPage
                    }.coerceIn(0, pageCount - 1)
                    val scroll = when {
                        value != "vertical" -> 0
                        jump != null -> pendingJumpScroll
                        mode == "vertical" -> normalizedPageOffset(listState.firstVisibleItemScrollOffset, viewport.measuredZoom)
                        else -> 0
                    }
                    // Both scroll states are put on the same page, and the viewport keeps gating
                    // position reports until the new layout has measured that page. Without this
                    // the freshly started collector would report the other state's stale page.
                    viewport.setReadingMode(value == "vertical")
                    viewport.resetForJump(page, scroll)
                    modeSwitch = page to scroll
                    currentPage = page
                    offset = scroll
                    mode = value
                    reportPosition(page, scroll, value)
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
                                (pendingPercent ?: slider ?: textPageFraction)
                                    .coerceIn(0f, 100f)
                            } else null
                            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    when {
                                        source != null && (pendingPercent != null || slider != null) -> {
                                            val offset = (source.characterCount.toLong() * shownProgress!! / 100f).toInt()
                                            source.chapterTitleForOffset(offset) ?: "正文"
                                        }
                                        source != null -> source.chapterTitleForOffset(chromeAnchor) ?: "正文"
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
                                                onSeek(value.roundToInt())
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
                                bookmarked = currentMarked,
                                isText = isText,
                                mode = mode,
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
                                    panel = null
                                    onChapterJump(chapter)
                                }
                            }
                        }
                        ReaderPanel.Speech -> SpeechPanel(
                            engines = engines,
                            engine = speechEngine,
                            voices = speechVoices,
                            voice = speechVoice?.key,
                            rate = speechPreferences.rate,
                            status = speech.status,
                            reading = speech.line?.text,
                            failure = speech.failure,
                            background = speechPreferences.background,
                            sleepMinutes = sleepMinutes,
                            sleepRemaining = { sleepRemaining.intValue },
                            onEngine = { packageName ->
                                // A voice belongs to its engine, so switching engines drops it.
                                onSpeech(packageName, null, speechPreferences.rate)
                                scope.launch { speech.useEngine(packageName) }
                            },
                            onVoice = { key ->
                                val picked = speechVoices.firstOrNull { it.key == key }
                                    ?: speechVoices.firstOrNull { it.name == key }
                                onSpeech(speechEngine, key, speechPreferences.rate)
                                speech.setVoice(picked)
                            },
                            onRateFinished = { value ->
                                onSpeech(speechEngine, speechPreferences.voice, value)
                                speech.applyRate(value)
                            },
                            onBackground = onSpeechBackground,
                            onSleepMinutes = { sleepMinutes = it },
                            onToggle = toggleSpeech,
                            onStop = stopSpeech,
                        )
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

