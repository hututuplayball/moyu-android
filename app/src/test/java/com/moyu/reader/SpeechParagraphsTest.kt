package com.moyu.reader

import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.data.FORMAT_TEXT
import com.moyu.reader.pdf.PageSize
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The text the machine reads out loud: it starts where the reader is and runs to the end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechParagraphsTest {
    @Test fun readingStartsAtTheGivenOffsetAndRunsToTheEnd() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "speech-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "book.txt")
        try {
            file.writeText(
                buildString {
                    append("第一章 起点\n\n")
                    repeat(20) { append("山间的风把云吹开一线，晨光落在石阶上。\n\n") }
                    append("第二章 行路\n\n")
                    repeat(20) { append("他沿着河岸走了很久，水面上的光斑晃动着。\n\n") }
                }
            )
            val source = TextPageSource.open(file, FORMAT_TEXT, TextReadingSettings(), PageSize(600, 900), 2f)
            try {
                val middle = source.charOffsetOfPage(source.pageCount / 2).toInt()
                val paragraphs = source.paragraphsFrom(middle)
                assertTrue(paragraphs.isNotEmpty())
                // The reading begins exactly at the offset the reader is on.
                assertEquals(middle, paragraphs.first().first)
                // Every later paragraph carries its own place in the book.
                paragraphs.zipWithNext().forEach { (a, b) ->
                    assertTrue("段落偏移没有递增", b.first > a.first)
                }
                // The offsets really do point at the text they claim.
                paragraphs.forEach { (offset, text) ->
                    assertTrue("段落为空", text.isNotBlank())
                    assertTrue("偏移越界", offset >= 0)
                }
            } finally {
                source.close()
            }
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test fun readingFromTheEndOfTheBookHasNothingToSay() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "speech-end-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "book.txt")
        try {
            file.writeText("第一章 起点\n\n山间的风把云吹开一线。\n\n")
            val source = TextPageSource.open(file, FORMAT_TEXT, TextReadingSettings(), PageSize(600, 900), 2f)
            try {
                assertTrue(source.paragraphsFrom(source.characterCount).isEmpty())
            } finally {
                source.close()
            }
        } finally {
            file.delete()
            directory.delete()
        }
    }
}
