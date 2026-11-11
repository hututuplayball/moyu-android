package com.moyu.reader.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyu.reader.book.TextDocument
import com.moyu.reader.book.TextLineSpacing
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
import com.moyu.reader.data.PDF_TEXT_VIEW_AVAILABLE
import com.moyu.reader.data.READ_VIEW_TEXT
import com.moyu.reader.data.ReaderPreferences
import com.moyu.reader.data.SpeechPreferences
import com.moyu.reader.data.TEXT_READY
import com.moyu.reader.data.TEXT_UNKNOWN
import com.moyu.reader.data.TEXT_UNSUPPORTED
import com.moyu.reader.page.PageSource
import com.moyu.reader.pdf.PageSize
import com.moyu.reader.speech.SpeechEngines
import com.moyu.reader.speech.SpeechReader
import com.moyu.reader.speech.SpeechService
import com.moyu.reader.ui.PaperAction
import com.moyu.reader.ui.PdfViewState
import com.moyu.reader.ui.library.clearCoverCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Typesetting taps settle before the whole book is measured again: five A+ steps cost one reflow.
private const val REFLOW_SETTLE_MS = 200L

/**
 * Owns one reading session: it opens the page source, re-lays it when the typesetting changes, and
 * puts the original file back in reach when it moved. Once a source exists, the screen itself is
 * [ReaderContent]'s job.
 */
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
    val speech = remember(book.id) { ReaderSpeech(SpeechReader(context)) }
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
    DisposableEffect(book.id) { onDispose { speech.reader.release() } }
    // A reading that survived in the background must not outlive the reader either.
    DisposableEffect(book.id) {
        onDispose {
            SpeechService.stop(context.applicationContext)
            SpeechService.stopReading = null
        }
    }
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
        speech.useEngines(found.filter { it.chinese })
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
        val active = session as? com.moyu.reader.book.TextPageSource
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
            val text = opened as? com.moyu.reader.book.TextPageSource
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
        if (session is com.moyu.reader.book.TextPageSource) {
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
            onBack = onBack,
            onPosition = onPosition,
            onBookmark = onBookmark,
            onBrightness = onBrightness,
            onRightToLeft = onRightToLeft,
            onTextSettings = handleTextSettings,
            onTextPosition = onTextPosition,
            speech = speech,
            speechPreferences = speechPreferences,
            onSpeech = onSpeech,
            onSpeechBackground = setSpeechBackground,
            initialScroll = restoreScroll,
            onRestoreConsumed = { restorePage = null },
            onChapterJump = { chapter ->
                (session as? com.moyu.reader.book.TextPageSource)?.let { openAtChar(it.chapterStartOffset(chapter)) }
            },
            onSeek = { percent ->
                (session as? com.moyu.reader.book.TextPageSource)?.let {
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
