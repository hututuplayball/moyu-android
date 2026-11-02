package com.moyu.reader

import com.moyu.reader.book.PdfTextBook
import com.moyu.reader.book.PdfTextCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A PDF has no table of contents, so chapters come from the openers a novel uses. */
class PdfTextBookTest {

    private fun document(vararg paragraphs: String) =
        PdfTextBook.document(PdfTextCache.Entry(null, paragraphs.toList(), intArrayOf(0, 9)))

    @Test fun chapterOpenersBecomeTitlesAndTocEntries() {
        val doc = document("第一章 起点", "山间的风把云吹开一线，晨光落在石阶上。", "Chapter 2 The Road",
            "The river kept its own quiet pace through the valley.")
        assertEquals(listOf(0, 2), doc.chapters.map { it.paragraphIndex })
        assertEquals(listOf("第一章 起点", "Chapter 2 The Road"), doc.chapters.map { it.title })
        assertTrue(doc.paragraphs[0].title)
        assertTrue(doc.paragraphs[0].chapterStart)
        assertFalse(doc.paragraphs[1].title)
        assertFalse(doc.paragraphs[3].title)
    }

    @Test fun aLongBodyLineOpeningWithAChapterNumberIsNotAHeading() {
        val doc = document("第一章里写到的那个小镇，后来在地图上再也找不到了，只剩下一条河的名字，" +
            "他把这件事记在了本子的最后一页上。")
        assertTrue(doc.chapters.isEmpty())
        assertFalse(doc.paragraphs.single().title)
    }

    @Test fun specialSectionsCountAsChapters() {
        val doc = document("序章", "山间的风把云吹开一线。", "番外 一")
        assertEquals(listOf("序章", "番外 一"), doc.chapters.map { it.title })
    }
}
