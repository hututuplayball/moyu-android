package com.moyu.reader

import androidx.compose.ui.unit.IntSize
import com.moyu.reader.pdf.PageSize
import com.moyu.reader.ui.anchoredScrollOffset
import com.moyu.reader.ui.anchoredTranslation
import com.moyu.reader.ui.boundedTranslation
import com.moyu.reader.ui.boundedFittedTranslation
import com.moyu.reader.ui.normalizedPageOffset
import com.moyu.reader.ui.pageDisplaySize
import com.moyu.reader.ui.preloadZoomPredecessors
import com.moyu.reader.ui.visiblePageTile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    @Test fun fingersStayOnTheSameContentIncludingPan() {
        val oldPan = -120f
        val oldOffset = 430f
        val fingerX = 350f
        val fingerY = 640f
        val ratio = 1.7f
        val nextX = anchoredTranslation(oldPan, fingerX, ratio, 18f)
        val nextOffset = anchoredScrollOffset(oldOffset, fingerY, ratio, -32f)
        assertEquals(fingerX + 18f, nextX + (fingerX - oldPan) * ratio, .001f)
        assertEquals(fingerY - 32f, (oldOffset + fingerY) * ratio - nextOffset, .001f)
    }

    @Test fun zoomResetRoundTripRetainsAnchorNotPageStart() {
        val zoomed = anchoredScrollOffset(235f, 610f, 3.5f, 0f)
        assertEquals(235f, anchoredScrollOffset(zoomed, 610f, 1f / 3.5f, 0f), .001f)
        val pan = anchoredTranslation(0f, 340f, 3.5f, 0f)
        assertEquals(0f, anchoredTranslation(pan, 340f, 1f / 3.5f, 0f), .001f)
    }

    @Test fun negativeAnchorsRemainAvailableForLazyListNormalization() {
        assertEquals(-280f, anchoredScrollOffset(40f, 600f, .5f, 0f), .001f)
        assertEquals(120, normalizedPageOffset(420, 3.5f))
        assertEquals(0, normalizedPageOffset(-20, 2f))
    }

    @Test fun translationIsBoundedAtBothEdgesAndResetsToZero() {
        assertEquals(0f, boundedTranslation(500f, 1080, 2f), .001f)
        assertEquals(-1080f, boundedTranslation(-9000f, 1080, 2f), .001f)
        assertEquals(-250f, boundedTranslation(-250f, 1080, 2f), .001f)
        assertEquals(0f, boundedTranslation(-200f, 1080, 1f), .001f)
    }

    @Test fun horizontalLetterboxingCannotBePannedCompletelyOffscreen() {
        // A narrow strip stays centered until its actual width exceeds the viewport.
        assertEquals(-2160f, boundedFittedTranslation(9999f, 100f, 1080, 5f), .001f)
        assertEquals(-2160f, boundedFittedTranslation(-9999f, 100f, 1080, 5f), .001f)
        // Wider pages stop when the real page edge (not its empty letterbox) reaches the viewport.
        assertEquals(-1200f, boundedFittedTranslation(9999f, 600f, 1080, 5f), .001f)
        assertEquals(-3120f, boundedFittedTranslation(-9999f, 600f, 1080, 5f), .001f)
        assertEquals(0f, boundedFittedTranslation(-500f, 600f, 1080, 1f), .001f)
    }

    @Test fun adjacentPagesReceiveSeparateVisibleIntersections() {
        val first = visiblePageTile(-120f, -900f, 1600f, 1200f, 1080, 1920)!!
        val second = visiblePageTile(-120f, 308f, 1600f, 2400f, 1080, 1920)!!
        assertEquals(0, first.screenTop)
        assertEquals(300, first.screenHeight)
        assertTrue(first.top > 0)
        assertEquals(308, second.screenTop)
        assertEquals(0, second.top)
        assertTrue(second.screenHeight in 1609..1612)
        assertTrue(first.left > 0 && second.left > 0)
    }

    @Test fun offscreenOrEmptyPagesDoNotRequestTiles() {
        assertNull(visiblePageTile(0f, 1920f, 1080f, 1600f, 1080, 1920))
        assertNull(visiblePageTile(0f, -1600f, 1080f, 1600f, 1080, 1920))
        assertNull(visiblePageTile(-1080f, 0f, 1080f, 1600f, 1080, 1920))
        assertNull(visiblePageTile(1080f, 0f, 1080f, 1600f, 1080, 1920))
        assertNull(visiblePageTile(0f, 0f, 0f, 1600f, 1080, 1920))
        assertNull(visiblePageTile(0f, 0f, 1080f, 1600f, 0, 0))
    }

    @Test fun horizontalLetterboxRetainsActualPageOrigin() {
        val tile = visiblePageTile(130f, 150f, 600f, 900f, 1080, 1920)!!
        assertEquals(130, tile.screenLeft)
        assertEquals(150, tile.screenTop)
        assertEquals(0, tile.left)
        assertEquals(0, tile.top)
        assertEquals(600, tile.width)
        assertEquals(900, tile.height)
    }

    @Test fun hugeViewportRemainsStrictlyMemoryBoundedAndAligned() {
        val tile = visiblePageTile(-10003f, -19999f, 38400f, 57600f, 7680, 4320)!!
        assertTrue(tile.width <= 2048 && tile.height <= 2048)
        assertTrue(tile.width.toLong() * tile.height <= 1_500_000)
        assertTrue(tile.left >= 0 && tile.top >= 0)
        val rasterScale = tile.fullWidth / 38400f
        assertEquals(tile.screenLeft.toFloat(), -10003f + tile.left / rasterScale, 1f)
        assertEquals(tile.screenTop.toFloat(), -19999f + tile.top / rasterScale, 1f)
        assertTrue(tile.width < tile.fullWidth)
    }

    @Test fun longStripRendersOnlyTheOnscreenSlice() {
        val tile = visiblePageTile(-1700f, -75000f, 5400f, 108000f, 1080, 1920)!!
        assertTrue(tile.top > tile.height * 20)
        assertTrue(tile.height <= 1920)
        assertTrue(tile.width < tile.fullWidth)
        assertTrue(tile.width.toLong() * tile.height <= 1_500_000)
        assertTrue(tile.screenHeight in 1917..1920)
    }

    @Test fun pageDisplaySizeRejectsDimensionsComposeCannotLayOut() {
        // A 5x zoomed long strip stays representable; beyond the 24-bit layout ceiling it is an explicit error.
        assertEquals(IntSize(1080, 324000), pageDisplaySize(PageSize(5400, 1_620_000), 1080, 1920, false))
        assertEquals(IntSize(1080, 1530), pageDisplaySize(PageSize(600, 850), 1080, 1920, false))
        assertNull(pageDisplaySize(PageSize(1, 1_000_000), 1080, 1920, false))
        assertNull(pageDisplaySize(PageSize(0, 10), 1080, 1920, false))
    }

    @Test fun zoomPreloadsOnlyThePredecessorsInsideOneBaseViewport() = runBlocking {
        val sizes = mapOf(0 to PageSize(600, 850), 1 to PageSize(600, 850), 2 to PageSize(600, 900))
        val loaded = mutableListOf<Int>()
        val load: suspend (Int) -> PageSize = { page -> loaded += page; sizes.getValue(page) }
        preloadZoomPredecessors(2, IntSize(1080, 1920), { null }, load)
        assertEquals(listOf(1, 0), loaded)
        loaded.clear()
        preloadZoomPredecessors(0, IntSize(1080, 1920), { null }, load)
        assertTrue(loaded.isEmpty())
        // Mostly-cached strips finish without opening the document again.
        preloadZoomPredecessors(2, IntSize(1080, 1920), { sizes[it] }, { error("loaded a cached page") })
    }
}
