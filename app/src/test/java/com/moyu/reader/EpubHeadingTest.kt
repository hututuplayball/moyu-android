package com.moyu.reader

import com.moyu.reader.book.EpubPackageReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubHeadingTest {
    @Test fun repeatedHeadingLinesAreNotPrintedTwice() {
        val document = EpubPackageReader.read(ByteArrayInputStream(epub()))

        val heading = document.paragraphs.first()
        assertTrue(heading.title)
        assertEquals("第1章：垂髻客", heading.text)
        // The <h1> and the duplicate subtitle <p> are both dropped from the body.
        assertEquals(1, document.paragraphs.count { it.text == "第1章：垂髻客" })
        assertEquals("宁家。", document.paragraphs[1].text)
        assertEquals("议事堂。", document.paragraphs[2].text)
        assertEquals("第2章 归途", document.paragraphs[3].text)
        // A real sentence that merely starts with a chapter marker must stay untouched.
        assertEquals("第2章的故事，从雨夜开始。", document.paragraphs[4].text)
        assertEquals(2, document.chapters.size)
        assertEquals("第1章：垂髻客", document.chapters[0].title)
        assertEquals("第2章 归途", document.chapters[1].title)
    }

    private fun epub(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entry(zip, "META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent())
            entry(zip, "OEBPS/content.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent())
            entry(zip, "OEBPS/toc.ncx", """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="n1" playOrder="1">
                      <navLabel><text>第1章：垂髻客</text></navLabel>
                      <content src="chapter1.xhtml"/>
                    </navPoint>
                    <navPoint id="n2" playOrder="2">
                      <navLabel><text>第2章 归途</text></navLabel>
                      <content src="chapter2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
            """.trimIndent())
            entry(zip, "OEBPS/chapter1.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                <h1>第1章：垂髻客</h1>
                <p>第1章：垂髻客</p>
                <p>宁家。</p>
                <p>议事堂。</p>
                </body></html>
            """.trimIndent())
            entry(zip, "OEBPS/chapter2.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                <h1>第2章 归途</h1>
                <p>第2章的故事，从雨夜开始。</p>
                <p>他回到了家。</p>
                </body></html>
            """.trimIndent())
        }
        return bytes.toByteArray()
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
