package com.moyu.reader

import com.moyu.reader.book.TextChapter
import com.moyu.reader.book.TextDocument
import com.moyu.reader.book.TextDocumentCache
import com.moyu.reader.book.TextParagraph
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextDocumentCacheTest {
    private lateinit var directory: File
    private lateinit var cache: TextDocumentCache
    private val id = "a1b2c3d4".repeat(8)

    @Before fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "text-doc-${UUID.randomUUID()}")
        directory.mkdirs()
        cache = TextDocumentCache(directory)
    }

    @Test fun aParsedBookSurvivesARoundTrip() {
        val document = TextDocument.of(
            listOf(
                TextParagraph("第一章 山风", title = true, chapterStart = true),
                TextParagraph("山间的风把云吹开一线。"),
                TextParagraph("第二章 夜路", title = true, chapterStart = true),
                TextParagraph("灯芯挑亮，夜色更深。"),
            ),
            listOf(TextChapter("第一章 山风", 0), TextChapter("第二章 夜路", 2)),
        )
        cache.write(id, document)
        val restored = cache.read(id)
        assertEquals(document.paragraphs.map { it.text }, restored?.paragraphs?.map { it.text })
        assertEquals(listOf(true, false, true, false), restored?.paragraphs?.map { it.title })
        assertEquals(listOf(0, 2), restored?.chapters?.map { it.paragraphIndex })
        assertTrue(restored!!.paragraphs[1].text.contains("山间"))
    }

    @Test fun idsThatAreNotContentHashesAreRefused() {
        val document = TextDocument.of(listOf(TextParagraph("正文")), emptyList())
        listOf("../escape", "12345", id.uppercase()).forEach { bad ->
            cache.write(bad, document)
            assertNull(cache.read(bad))
        }
        assertEquals(0, directory.listFiles()!!.size)
    }
}
