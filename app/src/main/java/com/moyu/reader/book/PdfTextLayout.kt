package com.moyu.reader.book

import kotlin.math.ceil
import kotlin.math.max

/**
 * Turns the raw line output of a PDF text extractor into readable paragraphs and decides whether
 * the document carries a real text layer at all.
 *
 * A PDF stores positioned glyph runs, never paragraphs. Every rule that matters here — rebuilding
 * paragraphs from hard-wrapped lines, dropping running headers and page numbers, anchoring each
 * PDF page in the rebuilt text, rejecting scanned or garbled documents — is pure Kotlin, so the
 * risky half of PDF reading stays unit-testable without a parser or a graphics stack.
 */
object PdfTextLayout {

    /** Why a PDF has no usable text version; its pages stay readable in the original view. */
    enum class Problem(val message: String) {
        EMPTY("这个 PDF 没有可提取的文字，请用原版阅读。"),
        SCANNED("扫描版 PDF，暂不支持文字版，请用原版阅读。"),
        GARBLED("这个 PDF 的文字层无法正常读取，请用原版阅读。"),
    }

    /**
     * [pageCharStarts] carries one anchor per PDF page plus the rebuilt text's total length, which
     * answers both directions between original pages and character offsets.
     */
    class Result(
        val paragraphs: List<String>,
        val pageCharStarts: IntArray,
        val problem: Problem?,
    ) {
        val usable: Boolean get() = problem == null
        val pageCount: Int get() = pageCharStarts.size - 1
    }

    /** Below this the document is a scan with a stamp or two, not a book. */
    private const val MIN_TOTAL_READABLE_CHARS = 300
    private const val MIN_TEXT_PAGE_CHARS = 20
    private const val MIN_TEXT_PAGE_SHARE = 0.1f
    private const val MAX_GARBLED_SHARE = 0.05f

    /** A paragraph opener this short without closing punctuation is a heading. */
    private const val MAX_HEADING_CHARS = 16
    private const val INDENT = '\u3000'
    private const val FURNITURE_LINES = 2
    private const val MAX_FURNITURE_CHARS = 40
    private const val FURNITURE_PAGE_SHARE = 0.25f

    /** Same invisible marks the EPUB reader drops: image placeholders, soft hyphens, zero-width. */
    private const val IGNORABLE = "\uFFFC\u00AD\u200B\u200C\u200D\u2060\uFEFF"
    private const val TERMINATORS = "。！？…；：.!?;:”’」』）)"
    private val PAGE_NUMBER = Regex(
        "^[-—–·\\s]*\\d{1,4}[-—–·\\s]*$|^第\\s*\\d{1,4}\\s*页$|^\\d{1,4}\\s*/\\s*\\d{1,4}$|^[-—–·\\s]*[ivxlcIVXLC]{1,6}[-—–·\\s]*$",
    )

    /** One page of extracted text: its lines become paragraphs, and the page keeps an anchor. */
    fun reflow(rawPages: List<List<String>>): Result {
        val cleaned = rawPages.map(::clean)
        val pages = dropPageFurniture(cleaned)
        val paragraphs = ArrayList<String>()
        val starts = IntArray(pages.size + 1)
        var committed = 0
        val current = StringBuilder()

        fun flush() {
            val text = current.toString()
            current.setLength(0)
            if (text.isNotEmpty()) {
                paragraphs += text
                committed += text.length
            }
        }

        pages.forEachIndexed { index, lines ->
            // A page starts where the previous paragraphs ended, plus any open paragraph so far.
            starts[index] = committed + current.length
            lines.forEach { line ->
                if (opensNewParagraph(current, line)) flush()
                if (current.isEmpty()) current.append(line.trimStart { it == INDENT })
                else appendLine(current, line)
            }
        }
        flush()
        starts[pages.size] = committed
        return Result(paragraphs, starts, problem(cleaned, pages, paragraphs))
    }

    /** Cleans one extracted page; [reflow] takes lines, so extractors can go page by page. */
    fun cleanLines(page: String): List<String> = clean(page.lineSequence().toList())

    /** PDF page holding [offset]; the last page owns everything at or past its anchor. */
    fun pageForOffset(pageCharStarts: IntArray, offset: Int): Int {
        if (pageCharStarts.size < 2) return 0
        val lastPage = pageCharStarts.size - 2
        val value = offset.coerceIn(0, pageCharStarts.last())
        var low = 0
        var high = lastPage
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pageCharStarts[mid] <= value) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** Character offset where [page] begins. */
    fun offsetOfPage(pageCharStarts: IntArray, page: Int): Int {
        if (pageCharStarts.size < 2) return 0
        return pageCharStarts[page.coerceIn(0, pageCharStarts.size - 2)]
    }

    /** Keeps the indentation of a paragraph opener as a marker the joiner can see. */
    private fun clean(lines: List<String>): List<String> = lines.mapNotNull { raw ->
        val body = raw.filterNot { it in IGNORABLE }.trimEnd { it.isWhitespace() }
        if (body.isEmpty()) return@mapNotNull null
        val indented = body.startsWith(INDENT) || body.startsWith("  ")
        val text = body.trimStart { it.isWhitespace() }
        if (text.isEmpty() || text.matches(PAGE_NUMBER)) return@mapNotNull null
        if (indented) "$INDENT$text" else text
    }

    /**
     * Page furniture repeats at the same edge of most pages; a line with the same shape (digits
     * ignored, so page numbers do not hide a header) that often is never part of the body.
     */
    private fun dropPageFurniture(pages: List<List<String>>): List<List<String>> {
        if (pages.size < 3) return pages
        val edgeLines = pages.flatMap { page ->
            val head = page.take(FURNITURE_LINES)
            val tail = if (page.size > FURNITURE_LINES) page.takeLast(FURNITURE_LINES) else emptyList()
            head + tail
        }
        val threshold = max(3, ceil(pages.size * FURNITURE_PAGE_SHARE).toInt())
        val repeated = edgeLines
            .filter { it.length <= MAX_FURNITURE_CHARS }
            .map(::furnitureKey)
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= threshold }
            .keys
        return pages.map { page ->
            // A sparse page is all edge: without a cap the body of a 4-line page would vanish.
            val limit = max(1, page.size / 8)
            var dropped = 0
            val kept = ArrayList<String>(page.size)
            page.forEachIndexed { index, line ->
                val edge = index < FURNITURE_LINES || index >= page.size - FURNITURE_LINES
                if (edge && dropped < limit && furnitureKey(line) in repeated) {
                    dropped++
                    return@forEachIndexed
                }
                kept += line
            }
            kept
        }
    }

    private fun furnitureKey(line: String): String =
        line.filterNot { it.isDigit() }.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    private fun opensNewParagraph(current: CharSequence, next: String): Boolean {
        if (current.isEmpty()) return false
        // A finished sentence ends its paragraph, whatever the next line starts with.
        if (current.last() in TERMINATORS) return true
        // An indented line always opens a paragraph in Chinese typesetting.
        if (next.startsWith(INDENT)) return true
        // A short opener without closing punctuation is a heading, not a wrapped sentence.
        // Latin text wraps mid-sentence all the time, so only CJK lines are judged this way.
        return current.length <= MAX_HEADING_CHARS && current.any { it.isCjk() }
    }

    private fun appendLine(current: StringBuilder, line: String) {
        val last = current.last()
        val first = line.first()
        when {
            // A trailing hyphen is a split word in Latin text; Chinese lines never end with one.
            last == '-' && first in 'a'..'z' -> {
                current.setLength(current.length - 1)
                current.append(line)
            }
            last.isLatinWordChar() && first.isLatinWordChar() -> current.append(' ').append(line)
            else -> current.append(line)
        }
    }

    private fun problem(
        rawPages: List<List<String>>,
        pages: List<List<String>>,
        paragraphs: List<String>,
    ): Problem? {
        val readable = paragraphs.sumOf { text -> text.count { it.isReadable() } }
        val garbled = paragraphs.sumOf { text -> text.count { it.isGarbled() } }
        if (readable == 0) {
            // Nothing left after removing page furniture still means text existed; only a
            // document that extracted nothing at all is truly empty.
            val unmapped = rawPages.sumOf { page -> page.sumOf { line -> line.count { it.isGarbled() } } }
            if (garbled > 0 || unmapped > 0) return Problem.GARBLED
            val anyText = rawPages.any { page -> page.any { line -> line.any { it.isReadable() } } }
            return if (anyText) Problem.SCANNED else Problem.EMPTY
        }
        // A font whose glyphs have no Unicode mapping extracts as private-use or replacement chars.
        if (garbled.toFloat() / (readable + garbled) > MAX_GARBLED_SHARE) return Problem.GARBLED
        if (readable < MIN_TOTAL_READABLE_CHARS) return Problem.SCANNED
        val textPages = pages.count { page ->
            page.sumOf { line -> line.count { it.isReadable() } } >= MIN_TEXT_PAGE_CHARS
        }
        if (textPages < max(1, ceil(pages.size * MIN_TEXT_PAGE_SHARE).toInt())) return Problem.SCANNED
        return null
    }

    private fun Char.isReadable(): Boolean =
        isCjk() || isLetterOrDigit()

    private fun Char.isCjk(): Boolean =
        this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'

    private fun Char.isGarbled(): Boolean =
        this == '\uFFFD' || this in '\uE000'..'\uF8FF' || (this < ' ' && !isWhitespace())

    private fun Char.isLatinWordChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
