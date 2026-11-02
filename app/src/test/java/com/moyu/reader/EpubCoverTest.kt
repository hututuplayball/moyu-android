package com.moyu.reader

import com.moyu.reader.book.EpubCover
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shelf cover comes from the publisher's declared artwork, not from a rendered text page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubCoverTest {
    private val cover = "cover-bytes".toByteArray()
    private val second = "second-art".toByteArray()

    @Test fun epub3CoverImagePropertyWins() {
        val bytes = epub(
            opfItems = """
                <item id="img1" href="images/other.jpg" media-type="image/jpeg"/>
                <item id="img2" href="images/cover.png" media-type="image/png" properties="cover-image"/>
            """.trimIndent(),
            extraFiles = mapOf("OEBPS/images/other.jpg" to second, "OEBPS/images/cover.png" to cover),
        )
        assertArrayEquals(cover, EpubCover.read(file(bytes)))
        assertArrayEquals(cover, EpubCover.read { ByteArrayInputStream(bytes) })
    }

    @Test fun epub2MetaCoverResolvesItsManifestItem() {
        val bytes = epub(
            opfItems = """
                <item id="cover-img" href="art/front.jpg" media-type="image/jpeg"/>
                <item id="text" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
            """.trimIndent(),
            meta = """<meta name="cover" content="cover-img"/>""",
            extraFiles = mapOf("OEBPS/art/front.jpg" to cover),
        )
        assertArrayEquals(cover, EpubCover.read(file(bytes)))
        assertArrayEquals(cover, EpubCover.read { ByteArrayInputStream(bytes) })
    }

    @Test fun guideReferenceIsUsedWhenNothingElseDeclaresACover() {
        val bytes = epub(
            opfItems = """<item id="front" href="art/front.jpg" media-type="image/jpeg"/>""",
            guide = """<reference type="cover" title="Cover" href="art/front.jpg"/>""",
            extraFiles = mapOf("OEBPS/art/front.jpg" to cover),
        )
        assertArrayEquals(cover, EpubCover.read(file(bytes)))
    }

    @Test fun aPackageWithoutACoverFallsBackToTheCaller() {
        val bytes = epub(
            opfItems = """<item id="text" href="chapter1.xhtml" media-type="application/xhtml+xml"/>""",
            extraFiles = emptyMap(),
        )
        assertNull(EpubCover.read(file(bytes)))
        assertNull(EpubCover.read { ByteArrayInputStream(bytes) })
    }

    @Test fun aDamagedArchiveIsNotAnError() {
        assertNull(EpubCover.read(file("not a zip".toByteArray())))
        assertNull(EpubCover.read { ByteArrayInputStream(ByteArray(0)) })
    }

    @Test fun aFlippedStreamReaderCanBeReopenedForTheCover() {
        val bytes = epub(
            opfItems = """<item id="img" href="images/cover.png" media-type="image/png" properties="cover-image"/>""",
            extraFiles = mapOf("OEBPS/images/cover.png" to cover),
        )
        // Each pass opens a fresh stream, so a non-seekable provider still yields the cover.
        assertNotNull(EpubCover.read { ByteArrayInputStream(bytes) })
    }

    private fun file(bytes: ByteArray): File {
        val directory = File(System.getProperty("java.io.tmpdir"), "epub-cover-${System.nanoTime()}")
        directory.mkdirs()
        return File(directory, "sample.epub").apply { writeBytes(bytes) }
    }

    private fun epub(
        opfItems: String,
        meta: String = "",
        guide: String = "",
        extraFiles: Map<String, ByteArray>,
    ): ByteArray {
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
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata>$meta</metadata>
                  <manifest>
                    $opfItems
                  </manifest>
                  <spine><itemref idref="text"/></spine>
                  ${if (guide.isBlank()) "" else "<guide>$guide</guide>"}
                </package>
            """.trimIndent())
            entry(zip, "OEBPS/chapter1.xhtml", "<html><body><p>正文</p></body></html>")
            extraFiles.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
