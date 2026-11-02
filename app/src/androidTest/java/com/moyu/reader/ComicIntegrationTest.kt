package com.moyu.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyu.reader.data.FORMAT_ARCHIVE
import com.moyu.reader.data.LibraryRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComicIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = LibraryRepository(context)
    private val importedIds = mutableSetOf<String>()
    private val sources = mutableListOf<File>()

    @After fun clean() = runBlocking {
        importedIds.forEach { repository.deleteBook(it) }
        sources.forEach { it.delete() }
    }

    private fun makeComic(name: String): File {
        val pages = listOf("1.png", "2.png", "10.png")
        val zip = File(context.cacheDir, "$name-${System.nanoTime()}.cbz")
        sources += zip
        ZipOutputStream(zip.outputStream()).use { out ->
            pages.forEachIndexed { index, page ->
                val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(0xFF000000.toInt() or (index * 0x404040 + 0x606060))
                out.putNextEntry(ZipEntry(page))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.closeEntry()
                bitmap.recycle()
            }
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("ignored".toByteArray())
            out.closeEntry()
        }
        return zip
    }

    @Test fun importsArchiveReadsPagesAndRemovesWithoutTouchingSource() = runBlocking {
        val source = makeComic("comic")
        val book = repository.importFile(Uri.fromFile(source))
        importedIds += book.id
        assertEquals(FORMAT_ARCHIVE, book.format)
        assertEquals(3, book.pageCount)
        assertTrue(repository.coverFile(book).length() > 0)
        assertTrue(repository.bookFile(book).name.endsWith(".zip"))
        assertEquals(book.id, repository.importFile(Uri.fromFile(source)).id)

        val session = repository.openBook(book)
        try {
            assertEquals(3, session.pageCount)
            val first = session.render(0, 60)
            assertEquals(60, first.width)
            assertEquals(90, first.height)
            val last = session.render(2, 60)
            assertEquals(60, last.width)
            assertTrue(first.getPixel(5, 5) != last.getPixel(5, 5))
            val tile = session.renderTile(0, 120, 10, 10, 40, 40)
            assertEquals(40, tile.width)
            assertEquals(40, tile.height)
        } finally {
            session.close()
        }

        repository.saveProgress(book.id, 2, 0, "vertical")
        repository.toggleBookmark(book.id, 2)
        assertEquals(listOf(2), repository.bookmarks(book.id).first())
        val reopened = LibraryRepository(context).getBook(book.id)!!
        assertEquals(2, reopened.currentPage)
        assertEquals("vertical", reopened.mode)

        repository.deleteBook(book.id)
        importedIds -= book.id
        assertNull(repository.getBook(book.id))
        assertTrue(repository.bookmarks(book.id).first().isEmpty())
        assertFalse(repository.bookFile(book).exists())
        assertTrue(source.exists())
    }
}
