package com.moyu.reader

import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.FORMAT_ARCHIVE
import com.moyu.reader.data.FORMAT_EPUB
import com.moyu.reader.data.FORMAT_PDF
import com.moyu.reader.data.FORMAT_TEXT
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.ui.library.formatLabel
import com.moyu.reader.ui.library.hasStarted
import com.moyu.reader.ui.library.isFinished
import com.moyu.reader.ui.library.progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a shelf tile says about a book.
 *
 * The label, the progress bar and the "已读完" badge are three views of the same row, so they are
 * asserted together: a book that reads 100% must also be the book the badge marks finished.
 */
class ShelfLabelTest {
    private fun book(
        format: Int = FORMAT_PDF,
        kind: Int = KIND_COMIC,
        pageCount: Int = 20,
        currentPage: Int = 0,
        pageOffset: Int = 0,
        mode: String = "",
        textProgress: Int = 0,
        importedAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
    ) = BookEntity(
        id = "b1",
        title = "山海",
        fileName = "shanhai.pdf",
        pageCount = pageCount,
        currentPage = currentPage,
        pageOffset = pageOffset,
        mode = mode,
        importedAt = importedAt,
        updatedAt = updatedAt,
        format = format,
        kind = kind,
        textProgress = textProgress,
    )

    @Test
    fun aBookThatWasNeverOpenedHasNotStarted() {
        val untouched = book()
        assertFalse(untouched.hasStarted())
        assertEquals(0f, untouched.progress(), 0.0001f)
        assertFalse(untouched.isFinished())
    }

    @Test
    fun aFirstPageTurnCountsAsStarted() {
        assertTrue(book(mode = "vertical").hasStarted())
        assertTrue(book(currentPage = 1).hasStarted())
        assertTrue(book(pageOffset = 40).hasStarted())
        // A row touched after import was read, even if it came back to page zero.
        assertTrue(book(updatedAt = 2_000L).hasStarted())
    }

    @Test
    fun progressFollowsTheOneBasedPageLabel() {
        // Page index 9 of 20 is the tenth page, so the bar reads exactly half.
        assertEquals(0.5f, book(currentPage = 9).progress(), 0.0001f)
        assertEquals(0.05f, book(currentPage = 0, mode = "vertical").progress(), 0.0001f)
    }

    @Test
    fun theLastPageIsFinishedAndReadsOneHundredPercent() {
        val done = book(currentPage = 19)
        assertTrue(done.isFinished())
        assertEquals(1f, done.progress(), 0.0001f)
        // One page short of the end is read, but not finished.
        val almost = book(currentPage = 18)
        assertFalse(almost.isFinished())
    }

    @Test
    fun anUnstartedBookIsNeverFinishedEvenWithNoPages() {
        val empty = book(pageCount = 0)
        assertFalse(empty.isFinished())
        assertEquals(0f, empty.progress(), 0.0001f)
    }

    @Test
    fun aTextBookReportsCharactersNotPages() {
        val text = book(format = FORMAT_TEXT, kind = KIND_EBOOK, pageCount = 900, textProgress = 42)
        // Pages are a chapter window for text, so the page fields must never stand in for progress.
        assertEquals(0.42f, text.progress(), 0.0001f)
        assertFalse(text.isFinished())
        assertEquals(1f, text.copy(textProgress = 100).progress(), 0.0001f)
        assertTrue(text.copy(textProgress = 100).isFinished())
    }

    @Test
    fun anEpubOnTheEbookShelfIsLaidOutAsText() {
        val epub = book(format = FORMAT_EPUB, kind = KIND_EBOOK, textProgress = 10)
        assertEquals(0.1f, epub.progress(), 0.0001f)
        // The same file imported as a comic is a fixed-layout package: it keeps its pages.
        val comic = book(format = FORMAT_EPUB, kind = KIND_COMIC, currentPage = 4, pageCount = 20)
        assertEquals(0.25f, comic.progress(), 0.0001f)
    }

    @Test
    fun formatLabelsNameTheContainerTheReaderPicked() {
        assertEquals("CBZ", book(format = FORMAT_ARCHIVE).formatLabel())
        assertEquals("EPUB", book(format = FORMAT_EPUB).formatLabel())
        assertEquals("TXT", book(format = FORMAT_TEXT).formatLabel())
        assertEquals("PDF", book(format = FORMAT_PDF).formatLabel())
    }
}
