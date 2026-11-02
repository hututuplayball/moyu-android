package com.moyu.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.moyu.reader.ui.ReaderViewport
import com.moyu.reader.ui.ReaderViewportState
import com.moyu.reader.ui.normalizedPageOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real parent recognizer + real lazy layouts, without relying on Robolectric's PDF rasterizer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderGestureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: ReaderViewportState
    private val composed = mutableSetOf<Int>()
    private var taps = 0
    private var retries = 0
    private var widthFraction by mutableFloatStateOf(1f)

    private fun reader(vertical: Boolean = true, pages: Int = 80, loading: Boolean = false, rtl: Boolean = false) {
        compose.setContent {
            val list = rememberLazyListState(if (pages == 1) 0 else 4, if (pages == 1) 0 else 80)
            val pager = rememberPagerState(if (pages == 1) 0 else 4, pageCount = { pages })
            state = remember { ReaderViewportState(list, pager, vertical) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth(widthFraction).fillMaxHeight()) {
                    ReaderViewport(state, onTap = { taps++ }) { _, _, zoom, expanded ->
                        if (vertical) {
                            LazyColumn(modifier = expanded.fillMaxSize(), state = list) {
                                items(pages, key = { it }) { page ->
                                    Page(page, Modifier.fillMaxWidth().aspectRatio(2f / 3f), loading)
                                }
                            }
                        } else {
                            HorizontalPager(state = pager, modifier = expanded.fillMaxSize(), userScrollEnabled = zoom <= 1.01f,
                                reverseLayout = rtl, beyondViewportPageCount = 0) { page -> Page(page, Modifier.fillMaxSize(), loading) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Page(index: Int, modifier: Modifier, loading: Boolean) {
        DisposableEffect(index) {
            composed.add(index)
            onDispose { composed.remove(index); state.pageReady(index, null) }
        }
        Box(modifier.background(Color.White).testTag("page-$index").onGloballyPositioned {
            state.pageReady(index, if (loading) null else Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())))
        }) {
            if (loading) TextButton(onClick = { retries++ }) { Text("Retry page") }
        }
    }

    private fun pinchOut() {
        compose.onNodeWithTag("reader-viewport").performTouchInput {
            val y = height * .4f
            pinch(Offset(width * .35f, y), Offset(width * .65f, y),
                Offset(width * .15f, y), Offset(width * .85f, y), durationMillis = 320)
        }
        compose.waitForIdle()
    }

    @Test fun doubleTapAnchorsInPlaceAndResetRoundTrips() {
        reader()
        val before = state.list.firstVisibleItemScrollOffset
        val page = state.list.firstVisibleItemIndex
        val at = Offset(state.viewport.width * .45f, state.viewport.height * .3f)
        compose.onNodeWithTag("reader-viewport").performTouchInput { doubleClick(at) }
        compose.runOnIdle {
            assertEquals(2.5f, state.zoom, .001f)
            assertEquals(page, state.list.firstVisibleItemIndex)
            assertEquals(before + at.y, (state.list.firstVisibleItemScrollOffset + at.y) / state.zoom, 2f)
            assertEquals(0, taps)
            assertFalse(state.hasPendingAnchor)
        }
        compose.onNodeWithTag("reader-viewport").performTouchInput { doubleClick(at) }
        compose.runOnIdle {
            assertEquals(1f, state.zoom, .001f)
            assertEquals(page, state.list.firstVisibleItemIndex)
            assertEquals(before.toFloat(), state.list.firstVisibleItemScrollOffset.toFloat(), 2f)
        }
    }

    @Test fun nativeSingleFingerScrollAndTapAreNotTakenOver() {
        reader()
        compose.onNodeWithTag("reader-viewport").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onNodeWithTag("reader-viewport").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(1f, state.zoom, .001f)
            assertTrue(state.list.firstVisibleItemIndex > 4 || state.list.firstVisibleItemScrollOffset > 80)
            assertEquals(1, taps)
            assertTrue(composed.size < 12)
        }
    }

    @Test fun pinchKeepsFocalPointAndAdjacentPagesStayLazyAndZoomed() {
        reader()
        val before = state.list.firstVisibleItemScrollOffset
        val focalY = state.viewport.height * .4f
        pinchOut()
        compose.runOnIdle {
            assertTrue(state.zoom > 1.5f)
            assertEquals(before + focalY, (state.list.firstVisibleItemScrollOffset + focalY) / state.zoom, 3f)
            assertEquals(0, taps)
            val pageHeight = state.list.layoutInfo.visibleItemsInfo.first().size
            state.list.requestScrollToItem(5, pageHeight - state.viewport.height / 2)
        }
        compose.runOnIdle {
            val visible = state.list.layoutInfo.visibleItemsInfo
            assertTrue(visible.any { it.index == 5 } && visible.any { it.index == 6 })
            assertTrue(visible.all { it.size > state.viewport.width * 1.5f })
            assertTrue(composed.size < 12)
            assertFalse(state.hasPendingAnchor)
            assertEquals((state.list.firstVisibleItemScrollOffset / state.zoom).toInt().toFloat(),
                normalizedPageOffset(state.list.firstVisibleItemScrollOffset, state.measuredZoom).toFloat(), 1f)
        }
    }

    @Test fun zoomedDragFlingsAndNextTouchCancelsInertia() {
        reader()
        pinchOut()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reader-viewport").performTouchInput { swipeUp(durationMillis = 200) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue(state.fling?.isActive == true) }
        compose.onNodeWithTag("reader-viewport").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(state.fling?.isCancelled == true) }
        compose.onNodeWithTag("reader-viewport").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun liftingOnePinchFingerDoesNotTapJumpOrFling() {
        reader()
        compose.onNodeWithTag("reader-viewport").performTouchInput {
            down(0, Offset(width * .35f, height * .4f))
            down(1, Offset(width * .65f, height * .4f))
            moveTo(0, Offset(width * .2f, height * .4f))
            moveTo(1, Offset(width * .8f, height * .4f))
            up(1)
            advanceEventTime(100)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(0, taps)
            assertTrue(state.fling == null)
            assertTrue(state.zoom > 1f)
        }
    }

    @Test fun queuedAnchorCannotUndoJumpAndNegativeResetNormalizesToPreviousPage() {
        reader()
        compose.runOnIdle {
            val point = Offset(100f, 200f)
            state.transform(point, Offset.Zero, 2f)
            state.transform(point, Offset(0f, -20f), 1.2f)
            state.resetForJump(20)
        }
        compose.runOnIdle {
            assertEquals(20, state.list.firstVisibleItemIndex)
            assertEquals(0, state.list.firstVisibleItemScrollOffset)
            assertEquals(1f, state.zoom, .001f)
            assertFalse(state.hasPendingAnchor)
            state.transform(Offset(100f, 200f), Offset.Zero, 2f)
        }
        compose.runOnIdle { state.list.requestScrollToItem(20, 0) }
        compose.runOnIdle { state.toggleZoom(Offset(100f, 200f)) }
        compose.runOnIdle {
            assertEquals(19, state.list.firstVisibleItemIndex)
            assertTrue(state.list.firstVisibleItemScrollOffset > 0)
            assertFalse(state.hasPendingAnchor)
        }
    }

    @Test fun horizontalPinchPansBothAxesAndResetReenablesPager() {
        reader(vertical = false)
        pinchOut()
        val before = state.pan
        compose.onNodeWithTag("reader-viewport").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(4, state.pager.currentPage)
            assertTrue(state.pan.y < before.y)
            assertTrue(state.pan.x in (state.viewport.width * (1f - state.zoom))..0f)
            assertTrue(state.pan.y in (state.viewport.height * (1f - state.zoom))..0f)
            state.toggleZoom()
        }
        compose.runOnIdle {
            assertEquals(Offset.Zero, state.pan)
            assertEquals(1f, state.zoom, .001f)
        }
        compose.onNodeWithTag("reader-viewport").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(5, state.pager.settledPage) }
    }

    @Test fun rightToLeftPagingAlsoResumesAfterZoomReset() {
        reader(vertical = false, rtl = true)
        pinchOut()
        compose.runOnIdle { state.toggleZoom() }
        compose.onNodeWithTag("reader-viewport").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(3, state.pager.settledPage) }
    }

    @Test fun resizePreservesRelativeTopPositionAndClampsPan() {
        reader()
        pinchOut()
        val width = state.viewport.width
        val offset = state.list.firstVisibleItemScrollOffset
        val page = state.list.firstVisibleItemIndex
        val zoom = state.zoom
        compose.runOnIdle { widthFraction = .75f }
        compose.runOnIdle {
            assertTrue(state.viewport.width < width)
            assertEquals(zoom, state.zoom, .001f)
            assertEquals(page, state.list.firstVisibleItemIndex)
            assertEquals(offset * state.viewport.width.toFloat() / width, state.list.firstVisibleItemScrollOffset.toFloat(), 2f)
            assertTrue(state.pan.x in (state.viewport.width * (1f - state.zoom))..0f)
            assertFalse(state.hasPendingAnchor)
        }
    }

    @Test fun singlePageZoomResetRemainsInsideDocumentBounds() {
        reader(pages = 1)
        pinchOut()
        compose.runOnIdle { state.toggleZoom() }
        compose.runOnIdle {
            assertEquals(1f, state.zoom, .001f)
            assertEquals(0, state.list.firstVisibleItemIndex)
            assertTrue(state.list.firstVisibleItemScrollOffset >= 0)
            assertEquals(Offset.Zero, state.pan)
            assertFalse(state.hasPendingAnchor)
        }
    }

    @Test fun loadingOrErrorControlsAreNotIntercepted() {
        reader(pages = 1, loading = true)
        compose.onNodeWithText("Retry page").performTouchInput { click() }
        pinchOut()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, taps)
            assertEquals(1f, state.zoom, .001f)
            assertEquals(0, state.list.firstVisibleItemIndex)
        }
    }
}
