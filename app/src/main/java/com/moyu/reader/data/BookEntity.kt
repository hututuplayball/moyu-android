package com.moyu.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val fileName: String,
    val pageCount: Int,
    val currentPage: Int = 0,
    val pageOffset: Int = 0,
    val mode: String = "",
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val groupId: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    // Reference rows read the user's original document through this granted URI and own no copy.
    @ColumnInfo(defaultValue = "NULL") val sourceUri: String? = null,
    @ColumnInfo(defaultValue = "0") val storageMode: Int = STORAGE_COPY,
    @ColumnInfo(defaultValue = "0") val format: Int = FORMAT_PDF,
    /** Which shelf this book belongs to. The reading surface is still chosen by [format]. */
    @ColumnInfo(defaultValue = "0") val kind: Int = KIND_COMIC,
    /** A PDF's text layer: unknown until it was extracted, ready, or confirmed unusable. */
    @ColumnInfo(defaultValue = "0") val textState: Int = TEXT_UNKNOWN,
    /** The PDF layout the reader left off in, so it reopens where it was left and the shelf follows. */
    @ColumnInfo(defaultValue = "0") val readView: Int = READ_VIEW_PDF,
    /** The text view paginates for the window and typesetting, so its own page and count are kept. */
    @ColumnInfo(defaultValue = "0") val textPage: Int = 0,
    @ColumnInfo(defaultValue = "0") val textPageCount: Int = 0,
    /** Character anchor in the flowing text: stable across font sizes and chapter windows. */
    @ColumnInfo(defaultValue = "0") val textOffset: Int = 0,
    /** Whole-book reading percentage for the shelf label; no page count is shown for text. */
    @ColumnInfo(defaultValue = "0") val textProgress: Int = 0,
    /** 1 once legacy page bookmarks were converted to character anchors for this book. */
    @ColumnInfo(defaultValue = "0") val textAnchors: Int = 0,
) {
    val isReference: Boolean get() = storageMode == STORAGE_REFERENCE

    /** True when this PDF can also be read as flowing text. */
    val hasTextView: Boolean get() = format == FORMAT_PDF && textState == TEXT_READY

    /**
     * True when this book is laid out as flowing text. An EPUB bought as a comic is a fixed-layout
     * package of image pages, so it keeps the page reader even though the file is still an EPUB.
     */
    val isTextBook: Boolean get() = format == FORMAT_TEXT || (format == FORMAT_EPUB && kind == KIND_EBOOK)

    /** True when the shelf should label this book with its flowing-text pages, not the PDF's own. */
    val showsTextPages: Boolean get() = PDF_TEXT_VIEW_AVAILABLE && hasTextView && readView == READ_VIEW_TEXT && textPageCount > 0

    /** The page count to label this book with: whatever layout it was last read in. */
    val labeledPageCount: Int get() = if (showsTextPages) textPageCount else pageCount

    /** 1-based page inside [labeledPageCount]; 0 when nothing can be opened yet. */
    val labeledPage: Int get() = when {
        labeledPageCount <= 0 -> 0
        showsTextPages -> textPage.coerceIn(0, labeledPageCount - 1) + 1
        else -> currentPage.coerceIn(0, labeledPageCount - 1) + 1
    }
}

const val READ_VIEW_PDF = 0
const val READ_VIEW_TEXT = 1

/**
 * The PDF text view is hidden: its flowing layout reads poorly on illustrated PDFs and it costs a
 * whole-book text extraction the first time a PDF is opened. The code stays in place, and flipping
 * this back on restores the view switch, the shelf labels and the extraction probe together.
 */
const val PDF_TEXT_VIEW_AVAILABLE = false

const val STORAGE_COPY = 0
const val STORAGE_REFERENCE = 1

const val FORMAT_PDF = 0
const val FORMAT_ARCHIVE = 1
const val FORMAT_FOLDER = 2
const val FORMAT_TEXT = 3
const val FORMAT_EPUB = 4

const val KIND_COMIC = 0
const val KIND_EBOOK = 1

const val TEXT_UNKNOWN = 0
const val TEXT_READY = 1
const val TEXT_UNSUPPORTED = 2
