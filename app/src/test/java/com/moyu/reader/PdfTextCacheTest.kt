package com.moyu.reader

import com.moyu.reader.book.PdfTextCache
import com.moyu.reader.book.PdfTextLayout
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The on-disk copy of a PDF's text layer: trustworthy when intact, absent when damaged. */
class PdfTextCacheTest {

    private val id = "abc".repeat(21) + "d"
    private lateinit var directory: File
    private lateinit var cache: PdfTextCache

    @Before fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "pdf-text-${UUID.randomUUID()}")
        cache = PdfTextCache(directory)
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun entryOf(vararg pages: List<String>) = PdfTextCache.Entry.of(PdfTextLayout.reflow(pages.toList()))

    private fun stored(id: String = this.id) = directory.listFiles()?.firstOrNull { it.name == "$id.pdftext" }

    @Test fun roundTripKeepsParagraphsAndAnchors() {
        val original = entryOf(
            listOf(
                "山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。",
                "他沿着河岸走了很久，水面上的光斑晃动着，像无数枚碎掉又拼好的镜子。",
                "夜色渐深，他把灯芯挑亮了一点，窗外的雨声渐渐密了起来。",
                "她把伞收起来，抖掉上面的水珠，沿着巷子慢慢往回走。",
                "他坐在门槛上，手里翻着一本旧书，风从院子里吹进来。",
                "灯芯爆了一下，屋子里静得能听见远处的犬吠。",
                "门外传来脚步声，他把书合上放在了桌上。",
            ),
            listOf(
                "老槐树的影子落在墙上，像一幅慢慢洇开的水墨画。",
                "他合上书，听见远处传来一声长长的汽笛，船灯在雾里忽明忽暗。",
                "石阶上还留着一层薄薄的水光，脚步踩上去有细碎的声响。",
                "雨停了，屋檐滴下的水珠在水洼里一颗颗砸开。",
                "山那边的天色慢慢暗了下去，炊烟贴着田地散开。",
                "她把窗推开一条缝，风把桌上的纸吹得哗哗作响。",
                "院子里安静下来，只剩下远处河水拍岸的声音。",
            ),
        )
        assertEquals(null, original.problem)
        cache.write(id, original)
        val restored = cache.read(id)
        assertTrue(restored != null)
        assertNull(restored!!.problem)
        assertEquals(original.paragraphs, restored.paragraphs)
        assertArrayEquals(original.pageCharStarts, restored.pageCharStarts)
        // Leftover temporary names would look like a half-written cache on the next launch.
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun unusableResultKeepsItsProblem() {
        val rejected = PdfTextCache.Entry.of(
            PdfTextLayout.reflow(List(3) { page -> List(4) { line -> "\uFFFD".repeat(8 + page * 4 + line) } }),
        )
        assertEquals(PdfTextLayout.Problem.GARBLED, rejected.problem)
        cache.write(id, rejected)
        val restored = cache.read(id)
        assertTrue(restored != null)
        assertEquals(PdfTextLayout.Problem.GARBLED, restored!!.problem)
        assertEquals(rejected.paragraphs, restored.paragraphs)
        assertArrayEquals(rejected.pageCharStarts, restored.pageCharStarts)
    }

    @Test fun damagedFileIsIgnored() {
        cache.write(id, entryOf(listOf("正文第一句。")))
        val file = stored()!!
        val broken = file.readBytes().copyOf(16)
        file.writeBytes(broken)
        assertNull(cache.read(id))
    }

    @Test fun idsThatAreNotContentHashesAreRefused() {
        val entry = entryOf(listOf("正文第一句。"))
        cache.write("../escape", entry)
        cache.write("12345", entry)
        cache.write("A".repeat(64), entry)
        assertTrue(directory.listFiles()?.isEmpty() != false)
        assertNull(cache.read("../escape"))
        assertNull(cache.read("12345"))
    }

    @Test fun anchorsMustNeverMoveBackwards() {
        cache.write(id, PdfTextCache.Entry(null, listOf("正文第一句。"), intArrayOf(0, 12, 5)))
        assertNull(cache.read(id))
    }

    @Test fun headerAnswersWithoutLoadingEveryParagraph() {
        // Two PDF pages of the same paragraph text, so the anchors have a real shape.
        val paragraph = "山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。"
        val paragraphs = List(20) { paragraph }
        val starts = intArrayOf(0, paragraphs.take(9).sumOf { it.length }, paragraphs.sumOf { it.length })
        cache.write(id, PdfTextCache.Entry(null, paragraphs, starts))
        val header = cache.readHeader(id)
        assertTrue(header != null)
        assertTrue(header!!.usable)
        assertNull(header.explanation())
        assertArrayEquals(starts, header.pageCharStarts)
    }

    @Test fun storedFailureKeepsItsMessageAndIsNeverUsable() {
        val note = "此 PDF 已加密或受密码保护，暂不支持文字版。"
        cache.writeFailure(id, note)
        val header = cache.readHeader(id)
        assertTrue(header != null)
        assertFalse(header!!.usable)
        assertEquals(note, header.explanation())
        val stored = cache.read(id)
        assertTrue(stored != null)
        assertFalse(stored!!.usable)
        assertEquals(note, stored.note)
        assertTrue(stored.paragraphs.isEmpty())
    }

    /** A stored verdict never doubles as page anchors: mapping must not trust it. */
    @Test fun failureHeaderHasNoUsableAnchors() {
        cache.writeFailure(id, "这个 PDF 太大，暂不支持文字版，请用原版阅读。")
        val header = cache.readHeader(id)!!
        assertFalse(header.usable)
        assertArrayEquals(intArrayOf(0, 0), header.pageCharStarts)
    }

    @Test fun deletedEntryIsGone() {
        cache.write(id, entryOf(listOf("正文第一句。")))
        assertTrue(stored() != null)
        cache.delete(id)
        assertTrue(stored() == null)
        assertNull(cache.read(id))
    }
}
