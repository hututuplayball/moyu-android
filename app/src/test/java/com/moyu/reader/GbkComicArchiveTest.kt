package com.moyu.reader

import com.moyu.reader.page.ImagePageSource
import java.io.File
import java.nio.charset.Charset
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

/**
 * Chinese comic archives are often zipped with GBK entry names and no UTF-8 flag. Modern runtimes
 * reject such a central directory outright instead of returning replacement characters, so the
 * archive used to open as "invalid or damaged" even though every byte was intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GbkComicArchiveTest {
    @Test fun aCbzWithGbkEntryNamesStillOpens() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "gbk-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val archive = File(directory, "comic.zip")
        try {
            // ZipOutputStream writes the GBK names literally and leaves the UTF-8 flag off.
            ZipOutputStream(archive.outputStream(), Charset.forName("GBK")).use { zip ->
                repeat(3) { index ->
                    zip.putNextEntry(ZipEntry("第${index + 1}页.png"))
                    zip.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                    zip.closeEntry()
                }
            }
            val source = ImagePageSource.open(archive)
            try {
                assertEquals(3, source.pageCount)
            } finally {
                source.close()
            }
        } finally {
            archive.delete()
            directory.delete()
        }
    }
}
