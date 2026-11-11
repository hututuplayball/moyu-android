package com.moyu.reader.ui.reader

import com.moyu.reader.book.PdfTextLayout
import com.moyu.reader.book.TextPageSource

/**
 * The one formula every text position goes through: a page plus its in-page pixel becomes the first
 * visible line, expressed as a character offset.
 *
 * Bookmarks, progress, the saved position, the chapter label and the speech cursor all start here.
 * They used to each compute it slightly differently, which is how a saved position could disagree
 * with the page it was saved from.
 */
internal fun lineAnchor(
    source: TextPageSource,
    page: Int,
    scroll: Int,
    vertical: Boolean,
    viewportWidth: Int,
): Int = if (vertical) {
    source.charOffsetAtPageOffset(page, scroll, viewportWidth)
} else {
    source.charOffsetOfPage(page).toInt()
}

/** The bookmark whose saved anchor maps to the line on screen, if any. */
internal fun bookmarkAtLine(source: TextPageSource, bookmarks: List<Int>, anchor: Int): Int? =
    bookmarks.firstOrNull { source.lineAnchorInWindow(it) == anchor }

/**
 * The label a mark shows. Text books and the PDF text view name the chapter a mark lands in; the
 * PDF view has no chapters, so it names the page instead (see the caller).
 */
internal fun markLabelFor(
    source: TextPageSource,
    pureText: Boolean,
    pdfAnchors: IntArray?,
    mark: Int,
): String = if (pureText) {
    source.chapterTitleForOffset(mark) ?: "正文"
} else {
    val offset = pdfAnchors?.let { PdfTextLayout.offsetOfPage(it, mark) } ?: 0
    source.chapterTitleForOffset(offset) ?: "正文"
}
