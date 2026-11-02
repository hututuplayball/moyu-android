package com.moyu.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyu.reader.data.LibraryRepository
import com.moyu.reader.data.PreferencesRepository
import com.moyu.reader.pdf.PdfSession
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = LibraryRepository(context)
    private val importedIds = mutableSetOf<String>()
    private val sources = mutableListOf<File>()

    @After fun clean() = runBlocking {
        importedIds.forEach { repository.deleteBook(it) }
        sources.forEach { it.delete() }
    }

    private fun makePdf(name: String, pages: Int, longPage: Boolean = false): File {
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
        sources += file
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val height = if (longPage && index == 1) 4000 else if (index % 3 == 2) 600 else 900
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, height, index + 1).create())
                page.canvas.drawColor(if (index % 2 == 0) Color.rgb(243, 239, 225) else Color.rgb(216, 228, 211))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 72, 57); textSize = 44f }
                page.canvas.drawText("MOYU / PAGE ${index + 1}", 35f, 80f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                page.canvas.drawRect(28f, 120f, 572f, height - 28f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(216, 165, 101)
                page.canvas.drawCircle(410f, 280f, 70f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }

    @Test fun importDeduplicatesAndPersistsProgressAndBookmarks() = runBlocking {
        val source = makePdf("library", 12)
        val book = repository.importFile(Uri.fromFile(source))
        importedIds += book.id
        assertEquals(12, book.pageCount)
        assertTrue(repository.bookFile(book).exists())
        assertTrue(repository.coverFile(book).length() > 0)
        assertEquals(book.id, repository.importFile(Uri.fromFile(source)).id)
        repository.saveProgress(book.id, 7, 123, "vertical")
        repository.toggleBookmark(book.id, 7)
        assertEquals(listOf(7), repository.bookmarks(book.id).first())
        val reopened = LibraryRepository(context).getBook(book.id)!!
        assertEquals(7, reopened.currentPage)
        assertEquals(123, reopened.pageOffset)
        assertEquals("vertical", reopened.mode)
        repository.toggleBookmark(book.id, 7)
        assertTrue(repository.bookmarks(book.id).first().isEmpty())
        repository.toggleBookmark(book.id, 3)
        repository.deleteBook(book.id)
        assertNull(repository.getBook(book.id))
        assertTrue(repository.bookmarks(book.id).first().isEmpty())
        assertFalse(repository.bookFile(book).exists())
        assertTrue(source.exists())
    }

    @Test fun rendersVisiblePagesAndZoomTilesFromLargeMixedDocument() = runBlocking {
        val source = makePdf("mixed", 120, longPage = true)
        val book = repository.importFile(Uri.fromFile(source))
        importedIds += book.id
        val session = PdfSession.open(repository.bookFile(book))
        try {
            assertEquals(120, session.pageCount)
            assertEquals(4000, session.pageSize(1).height)
            val first = session.render(0, 1080)
            assertSame(first, session.render(0, 1080))
            assertTrue(first.width.toLong() * first.height <= 3_200_000)
            val last = session.render(119, 1080)
            assertTrue(last.width > 0)
            val tile = session.renderTile(1, 5400, 100, 1000, 700, 900)
            assertEquals(700, tile.width)
            assertEquals(900, tile.height)
            assertTrue(tile.getPixel(100, 100) != Color.TRANSPARENT)
            coroutineScope {
                listOf(1, 2, 4, 8, 30, 119).map { page -> async { session.render(page, 720) } }.awaitAll()
            }
        } finally { session.close() }
        session.close()
    }

    @Test fun rejectsCorruptPdfWithoutAddingBooksOrDeletingSource() = runBlocking {
        val file = File(context.cacheDir, "invalid-${System.nanoTime()}.pdf").apply { writeText("not a PDF") }
        sources += file
        val before = repository.books.first().size
        var rejected = false
        try { repository.importFile(Uri.fromFile(file)) }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(before, repository.books.first().size)
        assertTrue(file.exists())
    }

    @Test fun preferencesSurviveRepositoryRecreation() = runBlocking {
        val preferences = PreferencesRepository(context)
        val original = preferences.preferences.first()
        try {
            preferences.setTheme("dark")
            preferences.setDefaultMode("horizontal")
            preferences.setRightToLeft(true)
            preferences.setKeepScreenOn(false)
            preferences.setLastReadingBook("e".repeat(64))
            val restored = PreferencesRepository(context).preferences.first()
            assertEquals("dark", restored.theme)
            assertEquals("horizontal", restored.defaultMode)
            assertTrue(restored.rightToLeft)
            assertFalse(restored.keepScreenOn)
            assertEquals("e".repeat(64), restored.lastReadingBookId)
        } finally {
            preferences.setTheme(original.theme)
            preferences.setDefaultMode(original.defaultMode)
            preferences.setRightToLeft(original.rightToLeft)
            preferences.setKeepScreenOn(original.keepScreenOn)
            preferences.setLastReadingBook(original.lastReadingBookId)
        }
    }
}
