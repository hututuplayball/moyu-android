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
        ),
        listOf(TextChapter("第一章 山风", 0), TextChapter("第二章 夜路", 3)),
    )

    private val secondChapterOffset = document.paragraphs.take(3).sumOf { it.text.length }

    @Test fun openingAnchorsOnlyTheChapterAroundTheReadingSpot() = runBlocking {
        val source = TextPageSource.reflow(
            document, TextReadingSettings(), page, density, startOffset = secondChapterOffset,
        )
        assertTrue(source.pageCount > 0)
        assertEquals("第二章 夜路", source.chapterTitleOfPage(0))
        // The first chapter exists before this window, but was not measured to open it.
        assertTrue(source.hasPreviousChapter())
        assertFalse(source.hasNextChapter())
        assertEquals(secondChapterOffset, source.charOffsetOfPage(0).toInt())
    }

    @Test fun loadingPreviousChapterPrependsItsPages() = runBlocking {
        val source = TextPageSource.reflow(
            document, TextReadingSettings(), page, density, startOffset = secondChapterOffset,
        )
        val added = source.loadPreviousChapter()
        assertTrue(added > 0)
        assertEquals("第一章 山风", source.chapterTitleOfPage(0))
        assertEquals(0, source.charOffsetOfPage(0).toInt())
        // Existing pages shifted by exactly the number the previous chapter contributed.
        assertEquals(secondChapterOffset, source.charOffsetOfPage(added).toInt())
    }

    @Test fun loadingNextChapterAppendsItsPages() = runBlocking {
        val source = TextPageSource.reflow(document, TextReadingSettings(), page, density)
        val before = source.pageCount
        val added = source.loadNextChapter()
        assertTrue(added > 0)
        assertEquals(before + added, source.pageCount)
        assertEquals("第二章 夜路", source.chapterTitleOfPage(before))
        assertFalse(source.hasNextChapter())
    }
}
