package com.moyu.reader

import android.graphics.Bitmap
import com.moyu.reader.page.ImagePageSource
import com.moyu.reader.page.PageSourceException
import com.moyu.reader.pdf.PageSize
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImagePageSourceTest {
    @Test fun archivePagesFollowFilenameOrderAndRenderAtRequestedWidth() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "cbz-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val archive = File(directory, "sample.cbz")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                writeImage(zip, "10.png", 140, 180)
                writeImage(zip, "2.png", 120, 160)
                writeImage(zip, "1.png", 100, 150)
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("not a page".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("__MACOSX/._1.png"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            val source = ImagePageSource.open(archive)
            try {
                assertEquals(3, source.pageCount)
                assertEquals(PageSize(100, 150), source.pageSize(0))
                assertEquals(PageSize(120, 160), source.pageSize(1))
                assertEquals(PageSize(140, 180), source.pageSize(2))
                val bitmap = source.render(2, 70)
                assertEquals(70, bitmap.width)
                assertEquals(90, bitmap.height)
                // Prefetch and the page turn must agree on the exact cache key.
                assertTrue(source.cachedRender(2, 70) != null)
                assertTrue(source.cachedRender(2, 140) == null)
            } finally {
                source.close()
            }
        } finally {
            archive.delete()
            directory.delete()
        }
    }

    @Test fun archiveWithoutImagesIsRejected() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "cbz-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val archive = File(directory, "empty.zip")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("no images".toByteArray())
                zip.closeEntry()
            }
            var rejected = false
            try {
                ImagePageSource.open(archive).close()
            } catch (_: PageSourceException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            archive.delete()
            directory.delete()
        }
    }

    private fun writeImage(zip: ZipOutputStream, name: String, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            zip.putNextEntry(ZipEntry(name))
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
            zip.closeEntry()
        } finally {
            bitmap.recycle()
        }
    }
}
