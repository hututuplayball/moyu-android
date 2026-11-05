package com.moyu.reader

import com.moyu.reader.book.TextChapter
import com.moyu.reader.book.TextDocument
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextParagraph
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.pdf.PageSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextLayoutCacheAdoptionTest {
    private val page = PageSize(480, 800)
    private val density = 2f
    private val body = "山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。"

    private val document = TextDocument.of(
        listOf(
            TextParagraph("第一章 山风", title = true, chapterStart = true),
            TextParagraph(body),
            TextParagraph(body),
            TextParagraph("第二章 夜路", title = true, chapterStart = true),
            TextParagraph(body),
            TextParagraph(body),
            TextParagraph("第三章 晨光", title = true, chapterStart = true),
            TextParagraph(body),
            TextParagraph(body),
        ),
        listOf(TextChapter("第一章 山风", 0), TextChapter("第二章 夜路", 3), TextChapter("第三章 晨光", 6)),
    )

    private val secondChapterOffset = document.paragraphs.take(3).sumOf { it.text.length }
    private val thirdChapterOffset = document.paragraphs.take(6).sumOf { it.text.length }

    @Test fun openingAlsoLoadsTheChapterBehindTheReadingSpot() = runBlocking {
        val source = TextPageSource.reflow(
            document, TextReadingSettings(), page, density, startOffset = thirdChapterOffset,
        )
        assertTrue(source.pageCount > 0)
        // One chapter behind the spot is laid out too: a backward fling must not run out of
        // content at its first page.
        assertEquals("第二章 夜路", source.chapterTitleOfPage(0))
        assertEquals(secondChapterOffset, source.charOffsetOfPage(0).toInt())
        val readingPage = source.pageForCharOffset(thirdChapterOffset.toLong())
        assertEquals("第三章 晨光", source.chapterTitleOfPage(readingPage))
        assertEquals(thirdChapterOffset, source.charOffsetOfPage(readingPage).toInt())
        assertTrue(source.hasPreviousChapter())
        assertFalse(source.hasNextChapter())
    }

    @Test fun loadingPreviousChapterPrependsItsPages() = runBlocking {
        val source = TextPageSource.reflow(
            document, TextReadingSettings(), page, density, startOffset = thirdChapterOffset,
        )
        val before = source.pageCount
        val added = source.loadPreviousChapter()
        assertTrue(added > 0)
        assertEquals("第一章 山风", source.chapterTitleOfPage(0))
        assertEquals(0, source.charOffsetOfPage(0).toInt())
        assertEquals(before + added, source.pageCount)
        // Existing pages shifted by exactly the number the previous chapter contributed.
        val readingPage = source.pageForCharOffset(thirdChapterOffset.toLong())
        assertEquals(thirdChapterOffset, source.charOffsetOfPage(readingPage).toInt())
    }

    @Test fun loadingNextChapterAppendsItsPages() = runBlocking {
        val source = TextPageSource.reflow(document, TextReadingSettings(), page, density)
        val before = source.pageCount
        val added = source.loadNextChapter()
        assertTrue(added > 0)
        assertEquals(before + added, source.pageCount)
        assertEquals("第二章 夜路", source.chapterTitleOfPage(before))
        // A third chapter follows, so the window can keep growing past the second.
        assertTrue(source.hasNextChapter())
        val addedAgain = source.loadNextChapter()
        assertTrue(addedAgain > 0)
        assertEquals(before + added + addedAgain, source.pageCount)
        assertEquals("第三章 晨光", source.chapterTitleOfPage(before + added))
        assertFalse(source.hasNextChapter())
    }
}
