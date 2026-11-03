package com.moyu.reader.book

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.moyu.reader.data.FORMAT_EPUB
import com.moyu.reader.page.PageSource
import com.moyu.reader.page.pageCacheLimit
import com.moyu.reader.pdf.PageSize
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lays a [TextDocument] out into fixed-size pages and draws them as bitmaps. Text stays vector at
 * any render width, so prefetch, zoom tiles and covers are all sharp. It implements the shared
 * [PageSource] contract, so the reading surface, bookmarks and progress work like PDF and comics.
 */
class TextPageSource private constructor(
    private val doc: TextDocument,
    private val settings: TextReadingSettings,
    rawWidth: Int,
    rawHeight: Int,
    density: Float,
) : PageSource {
    data class Segment(val paragraph: Int, val startLine: Int, val lineCount: Int)
    class TextPage(val segments: List<Segment>, val startChar: Int)
    data class TocEntry(val title: String, val page: Int)
    /** A line anchor and the page position that brings that line to the top of the viewport. */
    data class TextPosition(val page: Int, val pageOffset: Int, val charOffset: Int)

    private val pageWidth = rawWidth.coerceIn(MIN_PAGE_WIDTH, MAX_WIDTH)
    private val pageHeight = rawHeight.coerceIn(MIN_PAGE_HEIGHT, MAX_HEIGHT)
    private var theme = settings.theme
    private val bodyPx = (settings.fontSizeSp * density).roundToInt().coerceIn(MIN_BODY_PX, MAX_BODY_PX)
    private val titlePx = (bodyPx * TITLE_SCALE).roundToInt()
    private val marginX = (pageWidth * HORIZONTAL_MARGIN).roundToInt().coerceAtLeast(8)
    private val contentWidth = (pageWidth - marginX * 2).coerceAtLeast(1)
    private val marginTop = (pageHeight * TOP_MARGIN).roundToInt()
    private val marginBottom = (pageHeight * BOTTOM_MARGIN).roundToInt().coerceAtLeast(bodyPx)
    private val contentHeight = (pageHeight - marginTop - marginBottom).coerceAtLeast(bodyPx * 3)

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = bodyPx.toFloat()
        color = theme.text.toInt()
        typeface = Typeface.DEFAULT
        isSubpixelText = true
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = titlePx.toFloat()
        color = theme.text.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isSubpixelText = true
    }

    /**
     * Layouts are rebuilt on demand. Keeping one per paragraph would pin an entire novel's text in
     * memory, so the budget is counted in source characters, which tracks layout cost closely.
     */
    private val layouts = object : LruCache<Int, StaticLayout>(LAYOUT_CACHE_CHARS) {
        override fun sizeOf(key: Int, value: StaticLayout): Int = value.text.length
    }
    private val layoutMutex = Mutex()

    private val cacheLimit = pageCacheLimit()
    private val bitmaps = object : LruCache<RenderKey, Bitmap>(cacheLimit) {
        override fun sizeOf(key: RenderKey, value: Bitmap): Int = value.allocationByteCount
    }

    private var pages: List<TextPage> = emptyList()
    // A text book is laid out one chapter at a time; pages before the loaded window do not exist.
    private data class ChapterSlice(val index: Int, val pages: List<TextPage>)
    private data class ChapterEntry(val title: String, val paragraph: Int, val charOffset: Int)
    private val slices = ArrayList<ChapterSlice>()
    private var paragraphOffsets = IntArray(1)
    /**
     * The only chapter list used by the reader. It comes from the parsed document's own table of
     * contents; `chapterStart` flags are only a fallback. Everything (TOC, jump, progress label,
     * bookmark label, restore) indexes this same list, so the three views cannot disagree.
     */
    private val chapters: List<ChapterEntry>
    private val totalChars: Int
    private val layoutSize = PageSize(pageWidth, pageHeight)

    init {
        val offsets = IntArray(doc.paragraphs.size + 1)
        var cursor = 0
        doc.paragraphs.forEachIndexed { index, paragraph ->
            offsets[index] = cursor
            cursor += paragraph.text.length
        }
        offsets[doc.paragraphs.size] = cursor
        paragraphOffsets = offsets
        totalChars = cursor
        chapters = buildChapters()
    }

    /** Uses the parsed document's own chapter table as the single source of truth. */
    private fun buildChapters(): List<ChapterEntry> {
        val last = doc.paragraphs.lastIndex
        val entries = ArrayList<ChapterEntry>()
        val declared = doc.chapters
            .asSequence()
            .filter { it.paragraphIndex in 0..last }
            .sortedBy { it.paragraphIndex }
            .distinctBy { it.paragraphIndex }
            .toList()
        if (declared.isNotEmpty()) {
            declared.forEachIndexed { index, chapter ->
                val paragraph = doc.paragraphs[chapter.paragraphIndex]
                val title = chapter.title.trim().ifBlank {
                    if (paragraph.title) paragraph.text else "第 ${index + 1} 章"
                }
                entries += ChapterEntry(title, chapter.paragraphIndex, paragraphOffsets[chapter.paragraphIndex])
            }
        }
        // A parsed chapter list can be shorter than the body (for example a nav that skips a
        // heading). Add any genuine chapter-start paragraph the document list missed, using the
        // paragraph's own title, so a chapter never becomes unreachable.
        val seenParagraphs = entries.mapTo(HashSet()) { it.paragraph }
        doc.paragraphs.indices
            .filter { doc.paragraphs[it].chapterStart && it !in seenParagraphs }
            .forEach { paragraphIndex ->
                val paragraph = doc.paragraphs[paragraphIndex]
                val title = if (paragraph.title) paragraph.text else "第 ${entries.size + 1} 章"
                entries += ChapterEntry(title, paragraphIndex, paragraphOffsets[paragraphIndex])
            }
        if (entries.isEmpty()) {
            // No declared chapters and no chapter-start paragraphs: one readable body.
            entries += ChapterEntry("正文", 0, 0)
        } else {
            entries.sortBy { it.paragraph }
        }
        // Keep the text before the first declared chapter reachable without pretending it is a chapter.
        if (entries.first().paragraph > 0) entries.add(0, ChapterEntry("前言", 0, 0))
        return entries
    }

    override val pageCount: Int get() = pages.size
    /** A text page is a few draw calls into a bitmap, so it can stay sharp during a swipe. */
    override val sharpWhileScrolling: Boolean get() = true
    @Volatile
    private var generation = 0
    override val renderVersion: Int get() = generation
    override fun cachedPageSize(index: Int): PageSize? = if (index in 0 until pageCount) layoutSize else null

    override suspend fun pageSize(index: Int): PageSize {
        requireIndex(index)
        return layoutSize
    }

    /** Character offset of the page's first char: the anchor that survives font-size changes. */
    fun charOffsetOfPage(page: Int): Long {
        if (pages.isEmpty()) return 0L
        return pages[page.coerceIn(0, pages.lastIndex)].startChar.toLong()
    }

    fun pageForCharOffset(offset: Long): Int {
        if (pages.isEmpty()) return 0
        val value = offset.coerceIn(0L, paragraphOffsets.last().toLong()).toInt()
        var low = 0
        var high = pages.lastIndex
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pages[mid].startChar <= value) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /**
     * Character anchor of the first line visible at [scrollOffset] pixels inside [page].
     * The anchor is always a line start, so it survives font, line-height and chapter changes.
     */
    fun charOffsetAtPageOffset(page: Int, scrollOffset: Int, viewportWidth: Int): Int {
        if (pages.isEmpty()) return 0
        val index = page.coerceIn(0, pages.lastIndex)
        val text = pages[index]
        // Before the first measure the caller has no viewport width yet; the page itself was
        // paginated at the same width, so that is the correct fallback.
        val width = if (viewportWidth > 0) viewportWidth else pageWidth
        val scale = width.toDouble() / pageWidth
        val sourceY = scrollOffset.coerceAtLeast(0).toDouble() / scale
        var top = marginTop.toDouble()
        var lastLineStart = text.startChar
        for (segment in text.segments) {
            val layout = layoutFor(segment.paragraph)
            val pitch = lineMetrics(layout).second
            val height = segment.lineCount * pitch
            if (sourceY < top + height) {
                val lineInSegment = ((sourceY - top).coerceAtLeast(0.0) / pitch)
                    .toInt().coerceIn(0, segment.lineCount - 1)
                val line = segment.startLine + lineInSegment
                val lineStart = layout.getLineStart(line).coerceAtLeast(0)
                return paragraphOffsets[segment.paragraph] + lineStart
            }
            top += height
            val line = segment.startLine + segment.lineCount - 1
            lastLineStart = paragraphOffsets[segment.paragraph] + layout.getLineStart(line)
        }
        // The viewport top is in the page's bottom margin: the next page's first line is on top.
        return pages.getOrNull(index + 1)?.startChar ?: lastLineStart
    }

    /**
     * Page and pixel offset that put the line containing [offset] at the viewport top. This is the
     * inverse of [charOffsetAtPageOffset], computed from the current layout instead of saved pixels.
     */
    fun positionForCharOffset(offset: Int, viewportWidth: Int): TextPosition {
        if (pages.isEmpty()) return TextPosition(0, 0, 0)
        if (totalChars <= 0) return TextPosition(0, 0, 0)
        val target = offset.coerceIn(0, totalChars - 1)
        val page = pageForCharOffset(target.toLong())
        val text = pages[page]
        if (target <= text.startChar) return TextPosition(page, 0, text.startChar)
        val scale = if (viewportWidth > 0) viewportWidth.toDouble() / pageWidth else 1.0
        var top = marginTop.toDouble()
        for (segment in text.segments) {
            val layout = layoutFor(segment.paragraph)
            val paragraphStart = paragraphOffsets[segment.paragraph]
            val paragraphEnd = paragraphStart + layout.text.length
            val pitch = lineMetrics(layout).second
            if (target >= paragraphStart && target < paragraphEnd) {
                val line = layout.getLineForOffset(target - paragraphStart)
                    .coerceIn(0, layout.lineCount - 1)
                val relative = line - segment.startLine
                if (relative in 0 until segment.lineCount) {
                    val lineStart = paragraphStart + layout.getLineStart(line)
                    if (lineStart <= text.startChar) return TextPosition(page, 0, lineStart)
                    val y = top + relative * pitch
                    val scroll = ceil(y * scale).toInt().coerceAtLeast(0)
                    return TextPosition(page, scroll, lineStart)
                }
            }
            top += segment.lineCount * pitch
        }
        return TextPosition(page, 0, text.startChar)
    }

    /**
     * The current-layout line start containing [offset], only when that offset is in this window.
     * Bookmarks survive reflow by mapping their saved anchor through the current line layout.
     */
    fun lineAnchorInWindow(offset: Int): Int? {
        if (pages.isEmpty()) return null
        if (offset !in windowCharRange()) return null
        return positionForCharOffset(offset, pageWidth).charOffset
    }

    fun toc(): List<TocEntry> {
        val entries = chapters.map { chapter ->
            TocEntry(chapter.title, pageForCharOffset(chapter.charOffset.toLong()))
        }
        return entries.ifEmpty { listOf(TocEntry("正文", 0)) }
    }

    /** Total characters of the book: the denominator of the reading percentage. */
    val characterCount: Int get() = totalChars

    /** Title of the chapter holding [page]; null when the book has no recognisable chapters. */
    fun chapterTitleOfPage(page: Int): String? {
        if (pages.isEmpty()) return null
        return chapterTitle(chapterIndexForPage(page))
    }

    /** Reading progress of [page] across the whole book, 0..100; independent of page count. */
    fun progressPercentOfPage(page: Int): Int {
        if (totalChars <= 0) return 0
        return ((charOffsetOfPage(page) * 100L) / totalChars).toInt().coerceIn(0, 100)
    }

    /** Chapter title for a bookmark anchor, even when that chapter is not in the loaded window. */
    fun chapterTitleForOffset(offset: Int): String? =
        chapters[chapterIndexForOffset(offset)].title

    /** Exact percentage for the progress slider; the integer version is only for stored metadata. */
    fun progressFractionOfPage(page: Int): Float {
        if (totalChars <= 0) return 0f
        return (charOffsetOfPage(page) * 100f / totalChars).coerceIn(0f, 100f)
    }

    fun progressPercentForOffset(offset: Int): Int {
        if (totalChars <= 0) return 0
        return ((offset.coerceIn(0, totalChars) * 100L) / totalChars).toInt().coerceIn(0, 100)
    }

    /** Exact percentage of a line anchor, for controls that must not round a chapter away. */
    fun progressFractionForOffset(offset: Int): Float {
        if (totalChars <= 0) return 0f
        return (offset.coerceIn(0, totalChars) * 100f / totalChars).coerceIn(0f, 100f)
    }

    private fun paragraphForOffset(offset: Int): Int {
        val target = offset.coerceIn(0, (totalChars - 1).coerceAtLeast(0))
        var low = 0
        var high = doc.paragraphs.lastIndex
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (paragraphOffsets[mid] <= target) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** Fingerprint of the metrics that decide where a page ends; colors and night mode are excluded. */
    internal fun cacheKey(bookId: String): TextLayoutCache.Key = TextLayoutCache.Key(
        bookId = bookId,
        paragraphCount = doc.paragraphs.size,
        totalChars = totalChars,
        signature = listOf(
            "v$LAYOUT_VERSION",
            pageWidth,
            pageHeight,
            bodyPx,
            titlePx,
            marginX,
            marginTop,
            marginBottom,
            contentWidth,
            contentHeight,
            settings.lineSpacing.multiplier,
            Build.VERSION.SDK_INT,
        ).joinToString("|"),
    )

    /** Rebuilds pages from a stored map; false means the map does not fit this book, so lay it out. */
    internal fun adopt(snapshot: TextLayoutCache.Snapshot, key: TextLayoutCache.Key): Boolean {
        if (key.paragraphCount != doc.paragraphs.size || key.totalChars != totalChars) return false
        val restored = snapshot.pages.map { page ->
            TextPage(page.segments.map { Segment(it[0], it[1], it[2]) }, page.startChar)
        }
        if (!restored.isSound(snapshot.paragraphOffsets)) return false
        pages = restored
        paragraphOffsets = snapshot.paragraphOffsets
        return true
    }

    /** The pages in a form that [TextLayoutCache] can keep, so the next launch skips measuring. */
    internal fun snapshot(): TextLayoutCache.Snapshot = TextLayoutCache.Snapshot(
        pages = pages.map { page ->
            TextLayoutCache.StoredPage(
                page.startChar,
                page.segments.map { segment -> intArrayOf(segment.paragraph, segment.startLine, segment.lineCount) },
            )
        },
        paragraphOffsets = paragraphOffsets,
    )

    /** A stored map may be damaged on disk; every shape that would break drawing is rejected. */
    private fun List<TextPage>.isSound(offsets: IntArray): Boolean {
        if (isEmpty() || first().startChar != 0) return false
        if (offsets.size != doc.paragraphs.size + 1) return false
        if (offsets[0] != 0 || offsets.last() != totalChars) return false
        for (index in 1 until offsets.size) {
            if (offsets[index] < offsets[index - 1]) return false
        }
        var previousStart = 0
        for (page in this) {
            if (page.startChar < previousStart || page.startChar > totalChars) return false
            if (page.segments.isEmpty()) return false
            previousStart = page.startChar
            for (segment in page.segments) {
                if (segment.paragraph !in doc.paragraphs.indices) return false
                if (segment.startLine < 0 || segment.lineCount < 1) return false
            }
        }
        return true
    }

    override suspend fun render(index: Int, width: Int, cache: Boolean): Bitmap = layoutMutex.withLock {
        requireIndex(index)
        if (width <= 0) throw TextBookException("页面渲染宽度必须大于零。")
        val size = targetSize(width)
        val key = RenderKey(index, size.width, size.height, generation)
        if (cache) bitmaps.get(key)?.let { return@withLock it }
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            drawScaled(Canvas(bitmap), index, scale = size.width.toDouble() / pageWidth)
            val bytes = bitmap.allocationByteCount
            if (cache && bytes <= cacheLimit) {
                bitmaps.trimToSize(cacheLimit - bytes)
                bitmaps.put(key, bitmap)
            }
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    override fun cachedRender(index: Int, width: Int): Bitmap? {
        if (width <= 0 || index !in 0 until pageCount) return null
        val size = targetSize(width)
        return bitmaps.get(RenderKey(index, size.width, size.height, generation))
    }

    override suspend fun renderTile(
        index: Int,
        fullWidth: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap = layoutMutex.withLock {
        requireIndex(index)
        if (fullWidth <= 0 || width <= 0 || height <= 0 || left < 0 || top < 0) {
            throw TextBookException("页面分块尺寸或位置无效。")
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            drawScaled(Canvas(bitmap), index, scale = fullWidth.toDouble() / pageWidth, left = left, top = top)
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    override fun trimMemory() {
        bitmaps.evictAll()
    }

    /** True when only the theme changed: the page boundaries and line breaks would be identical. */
    fun keepsLayoutFor(other: TextReadingSettings): Boolean =
        other.fontSizeSp == settings.fontSizeSp && other.lineSpacing == settings.lineSpacing

    /**
     * Repaints every page for a new theme without laying out a line again: switching to night mode
     * must be instant and must not move the reader to another page.
     */
    suspend fun restyle(theme: TextTheme) = layoutMutex.withLock {
        if (theme.id == this.theme.id) return@withLock
        this.theme = theme
        bodyPaint.color = theme.text.toInt()
        titlePaint.color = theme.text.toInt()
        bitmaps.evictAll()
    }

    override suspend fun close() {
        layoutMutex.withLock {
            bitmaps.evictAll()
            layouts.evictAll()
        }
    }

    private fun requireIndex(index: Int) {
        if (index !in 0 until pageCount) throw TextBookException("页码超出这本书的范围。")
    }

    /** Exact bitmap size for [width]; text scales by width alone, so height follows the page ratio. */
    private fun targetSize(width: Int): PageSize {
        val targetWidth = width.coerceAtMost(MAX_WIDTH)
        val targetHeight = ceil(pageHeight * (targetWidth.toDouble() / pageWidth)).toInt().coerceIn(1, MAX_HEIGHT)
        return PageSize(targetWidth, targetHeight)
    }

    /** Draws [index] at [scale] as if the whole page were rendered, shifted by (left, top). */
    private fun drawScaled(canvas: Canvas, index: Int, scale: Double, left: Int = 0, top: Int = 0) {
        canvas.scale(scale.toFloat(), scale.toFloat())
        canvas.translate(-left.toFloat(), -top.toFloat())
        drawPage(canvas, index)
    }

    @Synchronized
    private fun layoutFor(index: Int): StaticLayout =
        layoutFor(index, if (doc.paragraphs[index].title) titlePaint else bodyPaint)

    /**
     * Builds one paragraph at [index] with a caller-owned paint. Pagination runs chapters in
     * parallel, so each worker uses its own paint copy instead of sharing measurement state.
     */
    private fun layoutFor(index: Int, paint: TextPaint): StaticLayout {
        layouts.get(index)?.let { return it }
        val paragraph = doc.paragraphs[index]
        // Paragraphs start flush left: the source text is trimmed during parsing and no indent is
        // injected here, so wrapped lines and paragraph openers share the same left edge.
        val text = paragraph.text
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, settings.lineSpacing.multiplier)
            .setIncludePad(false)
            .build()
        layouts.put(index, layout)
        return layout
    }

    private fun drawPage(canvas: Canvas, index: Int) {
        canvas.drawColor(theme.bg.toInt())
        val page = pages[index]
        var y = marginTop
        for (segment in page.segments) {
            val layout = layoutFor(segment.paragraph)
            val pitch = lineMetrics(layout).second
            val top = layout.getLineTop(segment.startLine)
            val bottom = layout.getLineBottom(segment.startLine + segment.lineCount - 1)
            canvas.save()
            canvas.clipRect(
                marginX.toFloat(), y.toFloat(),
                (marginX + contentWidth).toFloat(), (y + bottom - top).toFloat(),
            )
            canvas.translate(marginX.toFloat(), y - top.toFloat())
            layout.draw(canvas)
            canvas.restore()
            // Advance by the same top-to-top pitch for every line. StaticLayout leaves the last
            // line of a paragraph at the bare font height, which made paragraph gaps visibly
            // tighter than the wrapped lines inside a paragraph.
            y += segment.lineCount * pitch
        }
    }

    /** Loads the chapter holding [offset] and nothing else: this is the book's first visible page. */
    suspend fun loadInitial(offset: Int): Unit = withContext(Dispatchers.Default) {
        layoutMutex.withLock {
            val chapter = chapterForOffset(offset)
            slices.clear()
            slices += ChapterSlice(chapter, paginateChapter(chapter))
            generation++
            bitmaps.evictAll()
            rebuildPages()
        }
    }

    /** Appends the next chapter; returns how many pages were added, or zero at the book end. */
    suspend fun loadNextChapter(): Int = withContext(Dispatchers.Default) {
        layoutMutex.withLock {
            val next = (slices.lastOrNull()?.index ?: -1) + 1
            if (next >= chapters.size) return@withLock 0
            val pages = paginateChapter(next)
            slices += ChapterSlice(next, pages)
            rebuildPages()
            pages.size
        }
    }

    /** Prepends the previous chapter; returns how many pages shifted the existing page indices. */
    suspend fun loadPreviousChapter(): Int = withContext(Dispatchers.Default) {
        layoutMutex.withLock {
            val previous = (slices.firstOrNull()?.index ?: 0) - 1
            if (previous < 0) return@withLock 0
            val pages = paginateChapter(previous)
            slices.add(0, ChapterSlice(previous, pages))
            // Prepending shifts every existing page index; bitmaps keyed by the old indices would
            // otherwise draw the neighbouring chapter over the page the reader is looking at.
            generation++
            bitmaps.evictAll()
            rebuildPages()
            pages.size
        }
    }

    fun hasNextChapter(): Boolean =
        (slices.lastOrNull()?.index ?: -1) + 1 < chapters.size

    fun hasPreviousChapter(): Boolean = (slices.firstOrNull()?.index ?: 0) > 0

    fun chapterCount(): Int = chapters.size

    fun chapterTitle(index: Int): String = chapters[index.coerceIn(0, chapters.lastIndex)].title

    fun chapterStartOffset(index: Int): Int = chapters[index.coerceIn(0, chapters.lastIndex)].charOffset

    fun chapterIndexForPage(page: Int): Int =
        if (pages.isEmpty()) 0 else chapterIndexForOffset(charOffsetOfPage(page).toInt())

    /** Loads the chapter holding [offset] (if needed) and returns its page in the current window. */
    suspend fun jumpToOffset(offset: Int): Int {
        val target = offset.coerceIn(0, (totalChars - 1).coerceAtLeast(0))
        if (pages.isNotEmpty() && target >= pages.first().startChar && target in windowCharRange()) {
            return pageForCharOffset(target.toLong())
        }
        loadInitial(target)
        return pageForCharOffset(target.toLong())
    }

    /** Loads a chapter by index and returns its first page in the current window. */
    suspend fun jumpToChapter(index: Int): Int {
        val chapter = index.coerceIn(0, chapters.size - 1)
        if (slices.any { it.index == chapter }) {
            var page = 0
            for (slice in slices) {
                if (slice.index == chapter) return page
                page += slice.pages.size
            }
        }
        loadInitial(chapterStartOffset(chapter))
        return 0
    }

    private fun windowCharRange(): IntRange {
        val last = slices.lastOrNull() ?: return IntRange.EMPTY
        val end = chapters.getOrNull(last.index + 1)?.charOffset ?: totalChars
        return (pages.firstOrNull()?.startChar ?: 0) until end
    }

    private fun rebuildPages() {
        pages = slices.flatMap { it.pages }
    }

    private suspend fun paginateChapter(index: Int): List<TextPage> =
        paginateRange(chapters[index].paragraph, chapterEndParagraph(index), paragraphOffsets)

    private fun chapterEndParagraph(index: Int): Int =
        chapters.getOrNull(index + 1)?.paragraph ?: doc.paragraphs.size

    private fun chapterForOffset(offset: Int): Int = chapterIndexForOffset(offset)

    private fun chapterIndexForOffset(offset: Int): Int {
        val target = offset.coerceIn(0, (totalChars - 1).coerceAtLeast(0))
        var low = 0
        var high = chapters.lastIndex
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (chapters[mid].charOffset <= target) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** Lays out one chapter range; mirrors the sequential rules so page breaks stay identical. */
    private suspend fun paginateRange(start: Int, end: Int, offsets: IntArray): List<TextPage> {
        val result = ArrayList<TextPage>()
        val segments = ArrayList<Segment>()
        // Every worker owns its paints: text measurement state must not be shared across threads.
        val body = TextPaint(bodyPaint)
        val title = TextPaint(titlePaint)
        var used = 0
        var pageStart = offsets[start]
        var charCursor = offsets[start]

        fun endPage() {
            result += TextPage(segments.toList(), pageStart)
            segments.clear()
            used = 0
        }

        for (index in start until end) {
            if ((index - start) % ACTIVE_CHECK_INTERVAL == 0) currentCoroutineContext().ensureActive()
            val paragraph = doc.paragraphs[index]
            if (segments.isEmpty()) pageStart = charCursor
            val layout = layoutFor(index, if (paragraph.title) title else body)
            val (lineHeight, pitch) = lineMetrics(layout)
            var line = 0
            while (line < layout.lineCount) {
                val (take, advance) = linesThatFit(
                    layout, line, contentHeight - used, segments.isNotEmpty(), lineHeight, pitch,
                )
                if (take == 0) {
                    // The current page cannot take even one line: turn it and retry on a fresh page.
                    endPage()
                    pageStart = charCursor + lineStart(layout, line)
                    continue
                }
                appendSegment(segments, index, line, take)
                used += advance
                line += take
                if (line < layout.lineCount) {
                    endPage()
                    pageStart = charCursor + lineStart(layout, line)
                }
            }
            charCursor += paragraph.text.length
        }
        if (segments.isNotEmpty()) endPage()
        return result
    }

    /** Character offset of a layout line; paragraphs now start flush left. */
    private fun lineStart(layout: StaticLayout, line: Int): Int =
        layout.getLineStart(line).coerceAtLeast(0)

    /**
     * Height of the glyph line and the top-to-top pitch between lines. StaticLayout shortens the
     * final line of each layout to the bare font height; multiplying only the duration of the
     * layout would leave paragraph gaps tighter than its wrapped lines.
     */
    private fun lineMetrics(layout: StaticLayout): Pair<Int, Int> {
        val last = layout.lineCount - 1
        val height = (layout.getLineBottom(last) - layout.getLineTop(last)).coerceAtLeast(1)
        val pitch = (height * settings.lineSpacing.multiplier).roundToInt().coerceAtLeast(1)
        return height to pitch
    }

    /**
     * How many lines from [startLine] fit into [available], plus the top-to-top advance they use.
     * Zero means the page is full and must turn first; an empty page always takes a line, even an
     * oversized one.
     */
    private fun linesThatFit(
        layout: StaticLayout,
        startLine: Int,
        available: Int,
        pageHasContent: Boolean,
        lineHeight: Int,
        pitch: Int,
    ): Pair<Int, Int> {
        var take = 0
        while (startLine + take < layout.lineCount) {
            val needed = take * pitch + lineHeight
            if (needed > available) {
                if (take > 0 || pageHasContent) break
            }
            take++
        }
        return take to take * pitch
    }

    /** Merges a continuation of the same paragraph on the same page into one draw call. */
    private fun appendSegment(segments: MutableList<Segment>, index: Int, line: Int, count: Int) {
        val last = segments.lastOrNull()
        if (last != null && last.paragraph == index && last.startLine + last.lineCount == line) {
            segments[segments.lastIndex] = last.copy(lineCount = last.lineCount + count)
        } else {
            segments += Segment(index, line, count)
        }
    }

    private data class RenderKey(val index: Int, val width: Int, val height: Int, val version: Int)

    /** The parsed book. Laying it out again for new typesetting must not re-read the file. */
    val document: TextDocument get() = doc

    companion object {
        private const val MIN_PAGE_WIDTH = 240
        private const val MIN_PAGE_HEIGHT = 360
        private const val MAX_WIDTH = 4096
        private const val MAX_HEIGHT = 8192
        private const val MIN_BODY_PX = 12
        private const val MAX_BODY_PX = 120
        private const val TITLE_SCALE = 1.25f
        private const val HORIZONTAL_MARGIN = 0.055f
        private const val TOP_MARGIN = 0.045f
        private const val BOTTOM_MARGIN = 0.06f
        private const val LAYOUT_CACHE_CHARS = 256 * 1024
        private const val ACTIVE_CHECK_INTERVAL = 32

        /** Bump when page-breaking rules change, so stored page maps are rebuilt instead of misread. */
        // v3: paragraphs start flush left; the injected first-line indent was removed.
        private const val LAYOUT_VERSION = 3

        /** Imports paginate at this canonical size, so the stored page count is device-independent. */
        val CANONICAL_PAGE: PageSize get() = PageSize(1080, 2280)
        const val CANONICAL_DENSITY = 2.75f

        suspend fun open(
            file: File,
            format: Int,
            settings: TextReadingSettings,
            page: PageSize,
            density: Float,
            layoutCache: TextLayoutCache? = null,
            bookId: String? = null,
            startOffset: Int = 0,
        ): TextPageSource = openWith({ FileInputStream(file) }, format, settings, page, density, layoutCache, bookId, startOffset)

        /** Takes ownership of [descriptor]; it is closed with the input stream on every path. */
        suspend fun open(
            descriptor: ParcelFileDescriptor,
            format: Int,
            settings: TextReadingSettings,
            page: PageSize,
            density: Float,
            layoutCache: TextLayoutCache? = null,
            bookId: String? = null,
            startOffset: Int = 0,
        ): TextPageSource = openWith(
            { ParcelFileDescriptor.AutoCloseInputStream(descriptor) },
            format,
            settings,
            page,
            density,
            layoutCache,
            bookId,
            startOffset,
        )

        private suspend fun openWith(
            open: () -> InputStream,
            format: Int,
            settings: TextReadingSettings,
            page: PageSize,
            density: Float,
            layoutCache: TextLayoutCache? = null,
            bookId: String? = null,
            startOffset: Int = 0,
        ): TextPageSource = withContext(Dispatchers.IO) {
            val document = try {
                open().use { parse(it, format) }
            } catch (error: TextBookException) {
                throw error
            } catch (_: SecurityException) {
                throw TextBookException("无法读取这本书，请重新导入。")
            } catch (_: IOException) {
                throw TextBookException("这本书无法读取，文件可能已损坏。")
            }
            build(document, settings, page, density, layoutCache, bookId, startOffset)
        }

        /** Re-lays out an already parsed book, e.g. after a font size or theme change. */
        suspend fun reflow(
            document: TextDocument,
            settings: TextReadingSettings,
            page: PageSize,
            density: Float,
            layoutCache: TextLayoutCache? = null,
            bookId: String? = null,
            startOffset: Int = 0,
        ): TextPageSource = build(document, settings, page, density, layoutCache, bookId, startOffset)

        private fun parse(input: InputStream, format: Int): TextDocument = when (format) {
            FORMAT_EPUB -> EpubPackageReader.read(input)
            else -> TxtParser.read(input)
        }

        private suspend fun build(
            document: TextDocument,
            settings: TextReadingSettings,
            page: PageSize,
            density: Float,
            layoutCache: TextLayoutCache?,
            bookId: String?,
            startOffset: Int,
        ): TextPageSource = withContext(Dispatchers.Default) {
            // Only the chapter around the reading position is laid out; the rest waits until the
            // reader turns past a chapter boundary. A page map for the whole book is never required.
            val source = TextPageSource(document, settings, page.width, page.height, density)
            source.loadInitial(startOffset)
            currentCoroutineContext().ensureActive()
            source
        }
    }
}
