package com.moyu.reader

import com.moyu.reader.book.PdfTextLayout
import com.moyu.reader.book.PdfTextLayout.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules that turn positioned PDF lines into a book, tested without any PDF parser. */
class PdfTextLayoutTest {

    private fun reflow(vararg pages: List<String>) = PdfTextLayout.reflow(pages.toList())

    @Test fun hardWrappedLinesFormOneParagraph() {
        val first = "山里的清晨来得很慢，雾气贴着稻田慢慢"
        val second = "散开，他把柴刀别回腰间。"
        val result = reflow(listOf(first), listOf(second))
        assertEquals(listOf(first + second), result.paragraphs)
        // The second page starts where the first page's paragraph had grown to.
        assertEquals(first.length, result.pageCharStarts[1])
    }

    @Test fun finishedSentencesEndTheirParagraph() {
        val result = reflow(listOf("他推开门，院子里落满了槐花。"), listOf("夜色渐深，他把灯芯挑亮了一点。"))
        assertEquals(2, result.paragraphs.size)
        assertEquals("他推开门，院子里落满了槐花。", result.paragraphs[0])
    }

    @Test fun shortCjkOpenerStaysAHeading() {
        val heading = "第一章 起点"
        val body = "山间的风把云吹开一线，晨光落在石阶上。"
        val result = reflow(listOf(heading, body))
        assertEquals(listOf(heading, body), result.paragraphs)
    }

    @Test fun indentedLineStartsANewParagraph() {
        val first = "他推开门，院子里落满了槐花，"
        val second = "\u3000\u3000山里的风把云吹开一线。"
        val result = reflow(listOf(first, second))
        assertEquals(listOf(first, "山里的风把云吹开一线。"), result.paragraphs)
    }

    @Test fun runningHeaderIsDroppedButDistinctLinesSurvive() {
        // Four pages share one running header; every other line is unique to its page.
        val pages = listOf(
            listOf("墨屿 · 微光", "他推开门，院子里落满了槐花。", "山里的清晨来得很慢。", "他把柴刀别回腰间。"),
            listOf("墨屿 · 微光", "她关上窗，把灯芯挑亮。", "雨落在瓦上，声音很轻。", "远处的汽笛响了一声。"),
            listOf("墨屿 · 微光", "老槐树的影子落在墙上。", "他合上书，站起了身。", "石阶上还留着水光。"),
            listOf("墨屿 · 微光", "夜色渐深，雾气散开了。", "他沿着田埂往村子走。", "水面上的光斑晃动着。"),
        )
        val result = PdfTextLayout.reflow(pages)
        assertEquals(12, result.paragraphs.size)
        assertTrue(result.paragraphs.none { it.contains("墨屿") })
        assertEquals("他推开门，院子里落满了槐花。", result.paragraphs[0])
    }

    @Test fun pageNumbersNeverReachTheText() {
        val result = reflow(listOf("12", "- 12 -", "第 12 页", "1/240", "iv", "正文第一句。", "正文第二句。"))
        assertEquals(listOf("正文第一句。", "正文第二句。"), result.paragraphs)
    }

    @Test fun pageAnchorsMapBothWays() {
        val pages = listOf(
            listOf("他推开门，院子里落满了槐花。", "山里的清晨来得很慢。"),
            listOf("夜色渐深，他把灯芯挑亮了一点。"),
        )
        val result = PdfTextLayout.reflow(pages)
        assertEquals(3, result.paragraphs.size)
        val anchor = result.paragraphs[0].length + result.paragraphs[1].length
        assertEquals(anchor, result.pageCharStarts[1])
        assertEquals(result.paragraphs.sumOf { it.length }, result.pageCharStarts.last())
        assertEquals(0, PdfTextLayout.pageForOffset(result.pageCharStarts, 0))
        assertEquals(0, PdfTextLayout.pageForOffset(result.pageCharStarts, anchor - 1))
        assertEquals(1, PdfTextLayout.pageForOffset(result.pageCharStarts, anchor))
        assertEquals(1, PdfTextLayout.pageForOffset(result.pageCharStarts, result.pageCharStarts.last()))
        assertEquals(anchor, PdfTextLayout.offsetOfPage(result.pageCharStarts, 1))
        // Out-of-range pages clamp instead of throwing: the reader asks after every layout change.
        assertEquals(anchor, PdfTextLayout.offsetOfPage(result.pageCharStarts, 9))
        assertEquals(1, PdfTextLayout.pageForOffset(result.pageCharStarts, Int.MAX_VALUE))
    }

    @Test fun emptyPagesAreReportedAsEmpty() {
        val result = PdfTextLayout.reflow(List(5) { emptyList() })
        assertEquals(Problem.EMPTY, result.problem)
        assertTrue(result.paragraphs.isEmpty())
    }

    @Test fun scanWithAStampOrTwoIsRejected() {
        // A scanned book exposes a little watermark text, far too little to be the book itself.
        val pages = List(20) { listOf("扫描全能王") }
        val result = PdfTextLayout.reflow(pages)
        assertEquals(Problem.SCANNED, result.problem)
        assertEquals(20, result.pageCount)
    }

    @Test fun unmappedGlyphsAreRejectedAsGarbled() {
        val garbage = "\uFFFD".repeat(50)
        val result = PdfTextLayout.reflow(List(4) { listOf(garbage) })
        assertEquals(Problem.GARBLED, result.problem)
    }

    @Test fun readableNovelIsAccepted() {
        val pool = listOf(
            "山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。",
            "他沿着河岸走了很久，水面上的光斑晃动着，像无数枚碎掉又拼好的镜子。",
            "夜色渐深，他把灯芯挑亮了一点，窗外的雨声渐渐密了起来。",
            "她把伞收起来，抖掉上面的水珠，沿着巷子慢慢往回走。",
            "老槐树的影子落在墙上，像一幅慢慢洇开的水墨画。",
            "他合上书，听见远处传来一声长长的汽笛，船灯在雾里忽明忽暗。",
            "石阶上还留着一层薄薄的水光，脚步踩上去有细碎的声响。",
            "雨停了，屋檐滴下的水珠在水洼里一颗颗砸开。",
            "山那边的天色慢慢暗了下去，炊烟贴着田地散开。",
            "她把窗推开一条缝，风把桌上的纸吹得哗哗作响。",
            "他坐在门槛上，手里翻着一本旧书，风从院子里吹进来。",
            "灯芯爆了一下，屋子里静得能听见远处的犬吠。",
        )
        val pages = (0 until 8).map { index ->
            listOf(pool[index], pool[index + 1], pool[index + 2], pool[index + 3], pool[index + 4])
        }
        val result = PdfTextLayout.reflow(pages)
        assertNull(result.problem)
        assertTrue(result.usable)
        assertEquals(8, result.pageCount)
        assertEquals(9, result.pageCharStarts.size)
        assertEquals(0, result.pageCharStarts.first())
        // Anchors only grow: a later page can never start before an earlier one.
        assertTrue(result.pageCharStarts.toList().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun latinWordsAreJoinedWithoutLosingASyllable() {
        val result = reflow(listOf("inter-"), listOf("national cooperation"), listOf("continues here."))
        assertEquals(listOf("international cooperation continues here."), result.paragraphs)
    }
}
