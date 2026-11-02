package com.moyu.reader

import com.moyu.reader.book.EpubPackageReader
import com.moyu.reader.book.TextBookException
import com.moyu.reader.book.TextDocument
import com.moyu.reader.book.TextEncoding
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.book.TextThemes
import com.moyu.reader.book.TxtParser
import com.moyu.reader.data.FORMAT_EPUB
import com.moyu.reader.data.FORMAT_TEXT
import com.moyu.reader.pdf.PageSize
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextBookTest {
    @Test fun txtKeepsParagraphsAndDetectsChapters() {
        val text = "第一章 起点\r\n山里的清晨来得很慢，雾气贴着稻田慢慢散开。\r\n\r\n他把柴刀别回腰间，沿着田埂往村子走。\n\n第二章 行路\n夜色渐深，他把灯芯挑亮了一点。\n"
        val document = TxtParser.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals(5, document.paragraphs.size)
        assertEquals(2, document.chapters.size)
        assertEquals("第一章 起点", document.paragraphs[0].text)
        assertTrue(document.paragraphs[0].title)
        assertTrue(document.paragraphs[0].chapterStart)
        assertEquals("第二章 行路", document.paragraphs[3].text)
        assertTrue(document.paragraphs[3].chapterStart)
        assertEquals("夜色渐深，他把灯芯挑亮了一点。", document.paragraphs[4].text)
        assertEquals(0, document.chapters[0].paragraphIndex)
        assertEquals(3, document.chapters[1].paragraphIndex)
    }

    @Test fun txtFallsBackToGbkWhenUtf8Fails() {
        val document = TxtParser.read(ByteArrayInputStream("第一章 天光\n他推开门，院子里落满了槐花。".toByteArray(charset("GBK"))))
        assertEquals("第一章 天光", document.paragraphs[0].text)
        assertEquals("他推开门，院子里落满了槐花。", document.paragraphs[1].text)
    }

    @Test fun looksLikeTextAcceptsNovelsAndRejectsBinaries() {
        val utf8 = "这是一本正常的小说，第一章从山脚下开始写起。".toByteArray(Charsets.UTF_8)
        assertTrue(TextEncoding.looksLikeText(utf8, utf8.size))
        val gbk = "第一章 天光".toByteArray(charset("GBK"))
        assertTrue(TextEncoding.looksLikeText(gbk, gbk.size))
        val binary = ByteArray(64) { index -> if (index == 32) 0 else (index + 65).toByte() }
        assertTrue(!TextEncoding.looksLikeText(binary, binary.size))
        assertTrue(!TextEncoding.looksLikeText("短".toByteArray(), 1))
    }

    @Test fun epubFollowsSpineAndUsesNcxTitles() {
        val document = EpubPackageReader.read(ByteArrayInputStream(sampleEpub()))
        assertEquals(5, document.paragraphs.size)
        assertEquals(2, document.chapters.size)
        assertEquals("第一章 起点", document.paragraphs[0].text)
        assertTrue(document.paragraphs[0].chapterStart)
        assertEquals("山里的清晨来得很慢，雾气贴着稻田慢慢散开。", document.paragraphs[1].text)
        assertEquals("第二章 行路", document.paragraphs[3].text)
        assertTrue(document.paragraphs[3].chapterStart)
        assertEquals("夜色渐深，他把灯芯挑亮了一点。", document.paragraphs[4].text)
        assertEquals(3, document.chapters[1].paragraphIndex)
    }

    @Test fun epubWithoutContainerXmlIsRejected() {
        var rejected = false
        try {
            EpubPackageReader.read(ByteArrayInputStream(zipOf("readme.txt" to "not an epub")))
        } catch (_: TextBookException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun bytesThatAreNotZipsAreRejectedAsEpub() {
        var rejected = false
        try {
            EpubPackageReader.read(ByteArrayInputStream("这就是一份普通文本，绝不是一个压缩包。".toByteArray(Charsets.UTF_8)))
        } catch (_: TextBookException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun epub3UsesNavDocumentWhenThereIsNoNcx() {
        val document = EpubPackageReader.read(ByteArrayInputStream(sampleEpub3()))
        assertEquals(2, document.chapters.size)
        assertEquals("第一章 起点", document.chapters[0].title)
        assertEquals("第二章 行路", document.chapters[1].title)
        assertEquals(3, document.chapters[1].paragraphIndex)
    }

    @Test fun sampleEpub2TitlesComeFromNcxBeforeNav() {
        val withBoth = zipOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to opf(
                manifest = """
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>""",
                version = "2.0",
            ),
            "OEBPS/toc.ncx" to ncx(),
            "OEBPS/nav.xhtml" to navDocument(),
            "OEBPS/ch1.xhtml" to chapterOne(),
            "OEBPS/ch2.xhtml" to chapterTwo(),
        )
        val document = EpubPackageReader.read(ByteArrayInputStream(withBoth))
        // Both navigations exist; the NCX names win, so the nav labels never leak into chapters.
        assertEquals("第一章 起点", document.chapters[0].title)
        assertEquals("第二章 行路", document.chapters[1].title)
    }

    @Test fun epubWithManyMediaEntriesStillFindsItsText() {
        // 4200 illustrations sit before the container; skipping them must not exhaust the budget
        // that decides how many readable entries an EPUB may keep.
        val entries = ArrayList<Pair<String, String>>()
        entries += "mimetype" to "application/epub+zip"
        repeat(4200) { entries += "OEBPS/images/page-$it.png" to "not an image" }
        entries += "META-INF/container.xml" to containerXml()
        entries += "OEBPS/content.opf" to opf(
            manifest = """
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>""",
            spine = """<spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>""",
            version = "3.0",
        )
        entries += "OEBPS/ch1.xhtml" to chapterOne()
        entries += "OEBPS/ch2.xhtml" to chapterTwo()
        val document = EpubPackageReader.read(ByteArrayInputStream(zipOf(*entries.toTypedArray())))
        assertEquals(2, document.chapters.size)
        assertEquals("第一章 起点", document.chapters[0].title)
        assertEquals("山里的清晨来得很慢，雾气贴着稻田慢慢散开。", document.paragraphs[1].text)
    }

    @Test fun epubDropsImagePlaceholdersFromText() {
        val document = EpubPackageReader.read(ByteArrayInputStream(zipOf(
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to opf(
                manifest = """<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<spine><itemref idref="ch1"/></spine>""",
                version = "3.0",
            ),
            "OEBPS/ch1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html><body><h1>第一章 起点</h1><p>他推开门，<img src="../images/door.png"/>院子里落满了槐花。</p></body></html>""",
        )))
        // Without an image getter the <img> leaves a placeholder glyph that must never reach text.
        assertTrue(document.paragraphs.none { it.text.contains('\uFFFC') })
        assertEquals("他推开门，院子里落满了槐花。", document.paragraphs[1].text)
    }

    @Test fun epubKeepsHardWrappedSourceLinesInsideOneParagraph() {
        val document = EpubPackageReader.read(ByteArrayInputStream(zipOf(
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to opf(
                manifest = """<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<spine><itemref idref="ch1"/></spine>""",
                version = "3.0",
            ),
            "OEBPS/ch1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html><body><h1>第一章 起点</h1><p>
  他推开门，院子里
  落满了槐花。
</p><p>山里的<br/>清晨来得很慢。</p></body></html>""",
        )))
        assertEquals(4, document.paragraphs.size)
        // Source line breaks are cosmetic; only block tags and <br> may split the text.
        assertEquals("他推开门，院子里 落满了槐花。", document.paragraphs[1].text)
        assertEquals("山里的", document.paragraphs[2].text)
        assertEquals("清晨来得很慢。", document.paragraphs[3].text)
    }

    @Test fun epubUsesTheDocumentsOwnHeadingWhenThereIsNoNavigation() {
        // Longer than an opening-line snippet, so only the real heading can name this chapter.
        val title = "第一章 那个在稻田里捡到一只受伤白鹭的清晨与山间薄雾慢慢散开的记忆"
        assertTrue(title.length > 30)
        val document = EpubPackageReader.read(ByteArrayInputStream(zipOf(
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to opf(
                manifest = """<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<spine><itemref idref="ch1"/></spine>""",
                version = "3.0",
            ),
            "OEBPS/ch1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html><body><h1>$title</h1><p>他推开门，院子里落满了槐花。</p></body></html>""",
        )))
        assertEquals(title, document.chapters[0].title)
        // The heading must not be repeated as an ordinary paragraph of the chapter body.
        assertEquals(title, document.paragraphs[0].text)
        assertEquals("他推开门，院子里落满了槐花。", document.paragraphs[1].text)
    }

    @Test fun ncxNavPointWithoutLabelDoesNotInheritThePreviousTitle() {
        val document = EpubPackageReader.read(ByteArrayInputStream(zipOf(
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to opf(
                manifest = """
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>""",
                version = "2.0",
            ),
            "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
  <navPoint id="n1"><navLabel><text>第一章 起点</text></navLabel><content src="ch1.xhtml"/></navPoint>
  <navPoint id="n2"><content src="ch2.xhtml"/></navPoint>
</navMap></ncx>""",
            "OEBPS/ch1.xhtml" to chapterOne(),
            "OEBPS/ch2.xhtml" to chapterTwo(),
        )))
        assertEquals("第一章 起点", document.chapters[0].title)
        // The unlabelled navPoint must fall back to its own opening line, not the old label.
        assertEquals("夜色渐深，他把灯芯挑亮了一点。", document.chapters[1].title)
        assertEquals(3, document.chapters[1].paragraphIndex)
    }

    @Test fun looksLikeTextSurvivesAProbeCutInsideACharacter() {
        val full = "第一章 天光\n他推开门，院子里落满了槐花。山间的风把云吹开一线，远处的鸟鸣一声接着一声。"
            .toByteArray(charset("GBK"))
        // Import sniffs a 1024-byte prefix, which may end inside a two-byte character.
        for (cut in 8..full.size) {
            assertTrue("cut=$cut", TextEncoding.looksLikeText(full.copyOf(cut), cut))
        }
    }

    @Test fun paginationRendersPagesAndMapsOffsetsAcrossFontSizes() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "txt-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "novel.txt")
        try {
            val body = buildString {
                append("第一章 起点\n\n")
                repeat(30) { append("山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。\n\n") }
                append("第二章 行路\n\n")
                repeat(30) { append("他沿着河岸走了很久，水面上的光斑晃动着，像无数枚碎掉又拼好的镜子。\n\n") }
            }
            file.writeText(body)
            var midwayOffset = 0L
            var basePageCount = 0
            val source = TextPageSource.open(file, FORMAT_TEXT, TextReadingSettings(), PageSize(600, 900), 2f)
            try {
                assertTrue(source.pageCount > 1)
                assertEquals(PageSize(600, 900), source.pageSize(0))
                // Text stays vector: the same page renders sharply at any width.
                val bitmap = source.render(0, 300)
                assertEquals(300, bitmap.width)
                assertEquals(450, bitmap.height)
                assertTrue(source.cachedRender(0, 300) != null)
                // Offsets are monotonic and every page round-trips through its own offset.
                for (page in 0 until source.pageCount) {
                    val offset = source.charOffsetOfPage(page)
                    assertEquals(page, source.pageForCharOffset(offset))
                    if (page > 0) assertTrue(source.charOffsetOfPage(page) >= source.charOffsetOfPage(page - 1))
                }
                val chapters = source.toc()
                assertEquals(2, chapters.size)
                assertEquals("第一章 起点", chapters[0].title)
                assertEquals(0, chapters[0].page)
                assertEquals("第二章 行路", chapters[1].title)
                midwayOffset = source.charOffsetOfPage(source.pageCount / 2)
                basePageCount = source.pageCount
            } finally {
                source.close()
            }
            // A larger font relays the book; the recorded offset must land on a sensible page.
            val larger = TextPageSource.open(file, FORMAT_TEXT, TextReadingSettings(fontSizeSp = 26), PageSize(600, 900), 2f)
            try {
                assertTrue(larger.pageCount >= basePageCount)
                val mapped = larger.pageForCharOffset(midwayOffset)
                assertTrue(mapped in 0 until larger.pageCount)
            } finally {
                larger.close()
            }
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test fun restyleRepaintsPagesWithoutMovingThem() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "restyle-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "novel.txt")
        file.writeText(buildString {
            append("第一章 起点\n\n")
            repeat(60) { append("山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。\n\n") }
        })
        val source = TextPageSource.open(file, FORMAT_TEXT, TextReadingSettings(), PageSize(600, 900), 2f)
        try {
            val pages = source.pageCount
            val startChar = source.charOffsetOfPage(2)
            val before = source.render(2, 600)
            val night = TextThemes.all.first { it.id == "night" }
            source.restyle(night)
            // Repainting drops stale colours but never re-paginates or shifts the saved anchor.
            assertNull(source.cachedRender(2, 600))
            assertEquals(pages, source.pageCount)
            assertEquals(startChar, source.charOffsetOfPage(2))
            assertNotSame(before, source.render(2, 600))
        } finally {
            source.close()
            file.delete()
            directory.delete()
        }
    }

    @Test fun reflowRelaysAParsedBookWithoutReadingTheFileAgain() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "reflow-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "novel.txt")
        lateinit var document: TextDocument
        var midwayOffset = 0L
        try {
            val body = buildString {
                append("第一章 起点\n\n")
                repeat(30) { append("山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。\n\n") }
            }
            file.writeText(body)
            val original = TextPageSource.open(file, FORMAT_TEXT, TextReadingSettings(), PageSize(600, 900), 2f)
            try {
                document = original.document
                midwayOffset = original.charOffsetOfPage(original.pageCount / 2)
            } finally {
                original.close()
            }
        } finally {
            // A typesetting change must reuse the parsed book, so the file is gone by design.
            file.delete()
        }
        try {
            val reflowed = TextPageSource.reflow(document, TextReadingSettings(fontSizeSp = 26),
                PageSize(600, 900), 2f)
            try {
                assertEquals(document.paragraphs.size, reflowed.document.paragraphs.size)
                assertEquals("第一章 起点", reflowed.toc().first().title)
                val page = reflowed.pageForCharOffset(midwayOffset)
                // The old page start may sit mid-page in the bigger layout, never past its own end.
                assertTrue(reflowed.charOffsetOfPage(page) <= midwayOffset)
                if (page + 1 < reflowed.pageCount) {
                    assertTrue(reflowed.charOffsetOfPage(page + 1) > midwayOffset)
                }
                assertEquals(300, reflowed.render(page, 300).width)
            } finally {
                reflowed.close()
            }
        } finally {
            directory.delete()
        }
    }

    @Test fun epubSessionKeepsTableOfContentsAndStartPage() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "epub-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "book.epub")
        try {
            file.writeBytes(sampleEpub())
            val source = TextPageSource.open(file, FORMAT_EPUB, TextReadingSettings(), TextPageSource.CANONICAL_PAGE, TextPageSource.CANONICAL_DENSITY)
            try {
                assertEquals(2, source.toc().size)
                assertEquals("第一章 起点", source.toc()[0].title)
                assertEquals(0, source.pageForCharOffset(0))
            } finally {
                source.close()
            }
        } finally {
            file.delete()
            directory.delete()
        }
    }

    /** A two-chapter EPUB 2 laid out like a real package: container → OPF in OEBPS → NCX + XHTML. */
    private fun sampleEpub(): ByteArray = zipOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to containerXml(),
        "OEBPS/content.opf" to opf(
            manifest = """
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>""",
            spine = """<spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>""",
            version = "2.0",
        ),
        "OEBPS/toc.ncx" to ncx(),
        "OEBPS/ch1.xhtml" to chapterOne(),
        "OEBPS/ch2.xhtml" to chapterTwo(),
    )

    /** The same book as EPUB 3: no NCX at all, the table of contents lives in the nav document. */
    private fun sampleEpub3(): ByteArray = zipOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to containerXml(),
        "OEBPS/content.opf" to opf(
            manifest = """
    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>""",
            spine = """<spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>""",
            version = "3.0",
        ),
        "OEBPS/nav.xhtml" to navDocument(),
        "OEBPS/ch1.xhtml" to chapterOne(),
        "OEBPS/ch2.xhtml" to chapterTwo(),
    )

    private fun containerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    private fun opf(manifest: String, spine: String, version: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="$version" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试之书</dc:title></metadata>
  <manifest>$manifest
  </manifest>
  $spine
</package>"""

    private fun ncx(): String = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1" playOrder="1"><navLabel><text>第一章 起点</text></navLabel><content src="ch1.xhtml"/></navPoint>
    <navPoint id="n2" playOrder="2"><navLabel><text>第二章 行路</text></navLabel><content src="ch2.xhtml"/></navPoint>
  </navMap>
</ncx>"""

    /** A realistic nav document: a landmarks nav follows the toc nav and must not supply titles. */
    private fun navDocument(): String = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body>
<nav epub:type="toc" id="toc"><h1>目录</h1>
<ol>
  <li><a href="ch1.xhtml">第一章 起点</a></li>
  <li><a href="ch2.xhtml">第二章 行路</a></li>
</ol>
</nav>
<nav epub:type="landmarks" hidden="hidden"><ol><li><a href="ch1.xhtml">正文</a></li></ol></nav>
</body>
</html>"""

    private fun chapterOne(): String = """<?xml version="1.0" encoding="UTF-8"?>
<html><body><h1>第一章 起点</h1><p>山里的清晨来得很慢，雾气贴着稻田慢慢散开。</p><p>他把柴刀别回腰间，沿着田埂往村子走。</p></body></html>"""

    private fun chapterTwo(): String = """<?xml version="1.0" encoding="UTF-8"?>
<html><body><p>夜色渐深，他把灯芯挑亮了一点。</p></body></html>"""

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}

