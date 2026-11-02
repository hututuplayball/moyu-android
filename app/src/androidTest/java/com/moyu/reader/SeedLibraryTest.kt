package com.moyu.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.LibraryRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds a few books and groups on a debug device so the shelf can be exercised by hand. */
@RunWith(AndroidJUnit4::class)
class SeedLibraryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = LibraryRepository(context)

    @Test fun seed() {
        runBlocking {
        val seedDir = File(context.getExternalFilesDir(null), "seed")
        val generated = listOf(
            makePdf("海边来信", 3),
            makePdf("群山回声", 4),
            makePdf("远方的岛", 5),
        )
        val imported = mutableListOf<BookEntity>()
        for (file in generated) imported += repository.importFile(Uri.fromFile(file))
        for (name in listOf("one-page.pdf", "sea-letters.pdf", "large-comic.pdf")) {
            val file = File(seedDir, name)
            if (file.isFile) imported += repository.importFile(Uri.fromFile(file))
        }
        if (repository.groups.first().isEmpty()) {
            repository.createGroup("海岛")
            repository.createGroup("远行")
        }
        val groups = repository.groups.first()
        val islands = groups.firstOrNull { it.name == "海岛" }
        val travel = groups.firstOrNull { it.name == "远行" }
        // Assign by position so repeated runs on an already seeded device still regroup.
        val shelf = repository.books.first().sortedBy { it.importedAt }
        repository.moveBooks(shelf.take(2).map { it.id }, islands?.id)
        repository.moveBooks(shelf.drop(2).take(1).map { it.id }, travel?.id)
        generated.forEach { it.delete() }
        val after = repository.books.first()
        Log.i(
            "SeedLibrary",
            "seeded ${imported.joinToString { "${it.title}(${it.pageCount})" }}; " +
                groups.joinToString { "${it.name}=${after.count { book -> book.groupId == it.id }}" },
        )
        }
    }

    private fun makePdf(name: String, pages: Int): File {
        val file = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
        val document = PdfDocument()
        try {
            val paint = Paint().apply {
                color = Color.DKGRAY
                textSize = 40f
            }
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("$name ${index + 1}", 60f, 120f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
