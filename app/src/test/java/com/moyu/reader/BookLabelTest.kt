package com.moyu.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shelf labels a PDF with the layout it was last read in; these are the rules behind that. */
class BookLabelTest {
    private fun pdf(
        readView: Int,
        textPage: Int = 0,
        textPageCount: Int = 0,
        textState: Int = TEXT_READY,
    ) = BookEntity("a".repeat(64), "Book", "book.pdf", 40, currentPage = 20,
        format = FORMAT_PDF, kind = KIND_EBOOK, textState = textState,
        readView = readView, textPage = textPage, textPageCount = textPageCount)

    @Test fun originalViewUsesTheDocumentsOwnPages() {
        val book = pdf(READ_VIEW_PDF, textPage = 26, textPageCount = 52)
        assertFalse(book.showsTextPages)
        assertEquals(40, book.labeledPageCount)
        assertEquals(21, book.labeledPage)
    }

    @Test fun textViewUsesTheLayoutItLastBuilt() {
        val book = pdf(READ_VIEW_TEXT, textPage = 26, textPageCount = 52)
        assertTrue(book.showsTextPages)
        assertEquals(52, book.labeledPageCount)
        assertEquals(27, book.labeledPage)
    }

    @Test fun withoutABuiltTextLayoutThePdfPagesStay() {
        val book = pdf(READ_VIEW_TEXT, textPage = 0, textPageCount = 0)
        assertFalse(book.showsTextPages)
        assertEquals(40, book.labeledPageCount)
        assertEquals(21, book.labeledPage)
    }

    @Test fun anUnusableTextLayerNeverLabelsTheBook() {
        val book = pdf(READ_VIEW_TEXT, textPage = 26, textPageCount = 52, textState = TEXT_UNSUPPORTED)
        assertFalse(book.hasTextView)
        assertFalse(book.showsTextPages)
        assertEquals(40, book.labeledPageCount)
        assertEquals(21, book.labeledPage)
    }

    @Test fun staleTextPagesAreClampedToTheStoredCount() {
        val book = pdf(READ_VIEW_TEXT, textPage = 99, textPageCount = 52)
        assertEquals(52, book.labeledPage)
    }

    @Test fun textBooksIgnoreThePdfViewColumns() {
        val book = BookEntity("a".repeat(64), "Book", "book.txt", 12, currentPage = 3,
            mode = "vertical", format = FORMAT_TEXT, kind = KIND_EBOOK,
            readView = READ_VIEW_TEXT, textPage = 26, textPageCount = 52)
        assertFalse(book.showsTextPages)
        assertEquals(12, book.labeledPageCount)
        assertEquals(4, book.labeledPage)
    }
}
