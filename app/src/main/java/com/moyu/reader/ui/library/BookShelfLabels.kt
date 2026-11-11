package com.moyu.reader.ui.library

import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.FORMAT_ARCHIVE
import com.moyu.reader.data.FORMAT_EPUB
import com.moyu.reader.data.FORMAT_FOLDER
import com.moyu.reader.data.FORMAT_PDF
import com.moyu.reader.data.FORMAT_TEXT
import com.moyu.reader.data.PDF_TEXT_VIEW_AVAILABLE
import com.moyu.reader.data.READ_VIEW_TEXT

/**
 * Everything a shelf tile says about a book, derived from its stored row.
 *
 * These are plain functions on [BookEntity] rather than helpers inside the grid: the labels, the
 * progress bar and the "已读完" badge must agree with each other, and a book's page count depends
 * on which layout it was last read in. Keeping the rules in one place means a shelf tile, the
 * continuation card and the reader's progress row cannot drift apart.
 */

/** The layout a book is read in, which decides whether its label counts pages or percent. */
internal fun BookEntity.isTextLayout(): Boolean =
    isTextBook ||
        (format == FORMAT_PDF && PDF_TEXT_VIEW_AVAILABLE && hasTextView && readView == READ_VIEW_TEXT)

internal fun BookEntity.isFinished(): Boolean =
    if (isTextLayout()) textProgress >= 100
    else hasStarted() && labeledPageCount > 0 && labeledPage == labeledPageCount

internal fun BookEntity.hasStarted(): Boolean =
    mode.isNotBlank() || currentPage > 0 || pageOffset > 0 || updatedAt > importedAt

internal fun BookEntity.formatLabel(): String = when (format) {
    FORMAT_ARCHIVE -> "CBZ"
    FORMAT_FOLDER -> "文件夹"
    FORMAT_TEXT -> "TXT"
    FORMAT_EPUB -> "EPUB"
    else -> "PDF"
}

// Text layouts report a character percentage; PDF pages keep the page-based fraction.
internal fun BookEntity.progress(): Float = when {
    // Pages are a chapter window now, so page fields must never stand in for text progress:
    // the first page of an 11-page chapter would otherwise be shown as 9% of the whole book.
    isTextLayout() -> textProgress / 100f
    labeledPageCount <= 0 || !hasStarted() -> 0f
    else -> labeledPage.toFloat() / labeledPageCount
}
