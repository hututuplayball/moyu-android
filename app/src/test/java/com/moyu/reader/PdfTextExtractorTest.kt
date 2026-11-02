package com.moyu.reader

import com.moyu.reader.book.PdfTextExtractor
import com.moyu.reader.book.TextBookException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The real PDF parser: pages are read one by one and the page number line never reaches the text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfTextExtractorTest {

    private lateinit var directory: File

    @Before fun setUp() {
        // PDFBox reads its font metrics and CMaps from the app's assets on Android.
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
        directory = File(System.getProperty("java.io.tmpdir"), "pdf-extract-${UUID.randomUUID()}")
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun writeSample(pages: Int = 3): File {
        if (!directory.isDirectory) directory.mkdirs()
        val file = File(directory, "sample.pdf")
        PDDocument().use { document ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(60f, 760f)
                    stream.showText("Chapter ${index + 1} begins here")
                    stream.newLineAtOffset(0f, -22f)
                    stream.showText("The river kept its own quiet pace through the valley.")
                    stream.endText()
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 10f)
                    stream.newLineAtOffset(290f, 40f)
                    stream.showText((index + 1).toString())
                    stream.endText()
                }
            }
            document.save(file)
        }
        return file
    }

    @Test fun pagesAreReadOneByOneWithoutThePageNumber() = runBlocking {
        val file = writeSample()
        val pages = PdfTextExtractor.read(FileInputStream(file), directory, 3)
        assertEquals(3, pages.size)
        assertTrue(pages[0].any { it.contains("Chapter 1 begins here") })
        assertTrue(pages[2].any { it.contains("Chapter 3 begins here") })
        // The footer digit is page furniture, dropped before paragraph rebuilding.
        assertTrue(pages.all { page -> page.none { it == "1" || it == "2" || it == "3" } })
    }

    @Test fun readingStopsAtTheRequestedPageLimit() = runBlocking {
        val file = writeSample(pages = 4)
        val pages = PdfTextExtractor.read(FileInputStream(file), directory, 2)
        assertEquals(2, pages.size)
    }

    @Test fun aFileThatIsNotAPdfIsRejectedWithAReadableMessage() = runBlocking {
        if (!directory.isDirectory) directory.mkdirs()
        val file = File(directory, "broken.pdf")
        file.writeText("this is not a pdf")
        val failure = try {
            PdfTextExtractor.read(FileInputStream(file), directory, 3)
            null
        } catch (error: TextBookException) {
            error
        }
        assertNotNull(failure)
        assertTrue(failure!!.message!!.isNotBlank())
    }
}
