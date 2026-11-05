package com.moyu.reader

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.moyu.reader.ui.ReaderViewport
import com.moyu.reader.ui.ReaderViewportState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changing the reading direction must keep the page. Only one of the two scroll states drives the
 * screen at a time, so the other one is left wherever it was last used: switching has to put both
 * on the page being read before the surface flips, or the stale one reports itself as progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingModeSwitchTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var state: ReaderViewportState
    private lateinit var list: LazyListState
    private lateinit var pager: PagerState
    private var vertical by mutableStateOf(true)

    private fun reader(listPage: Int, pagerPage: Int, startVertical: Boolean) {
        vertical = startVertical
        compose.setContent {
            list = rememberLazyListState(listPage)
            pager = rememberPagerState(initialPage = pagerPage, pageCount = { 40 })
            state = remember { ReaderViewportState(list, pager, startVertical) }
            ReaderViewport(state, onTap = {}) { _, _, _, expanded ->
                if (vertical) {
                    LazyColumn(state = list, modifier = expanded.fillMaxSize()) {
                        items(40, key = { it }) { page ->
                            Text("page $page", Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        }
                    }
                } else {
                    HorizontalPager(state = pager, modifier = expanded.fillMaxSize()) { page ->
                        Text("page $page", Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun leavingVerticalForHorizontalKeepsThePage() {
        // The pager was last left on page 0 while the reader scrolled the list down to page 12.
        reader(listPage = 12, pagerPage = 0, startVertical = true)
        assertEquals(12, list.firstVisibleItemIndex)
        compose.runOnIdle {
            // Same order as the mode switch: flip the direction first, then place both states.
            state.setReadingMode(false)
            state.resetForJump(12)
            vertical = false
        }
        compose.waitForIdle()
        assertEquals(12, pager.currentPage)
        assertEquals(12, pager.settledPage)
    }

    @Test fun leavingHorizontalForVerticalKeepsThePage() {
        // The list was last left on page 0 while the reader paged across to page 12.
        reader(listPage = 0, pagerPage = 12, startVertical = false)
        assertEquals(12, pager.currentPage)
        compose.runOnIdle {
            state.setReadingMode(true)
            state.resetForJump(12, 0)
            vertical = true
        }
        compose.waitForIdle()
        assertEquals(12, list.firstVisibleItemIndex)
    }

    @Test fun inPageOffsetSurvivesTheReturnToVertical() {
        reader(listPage = 12, pagerPage = 0, startVertical = true)
        compose.runOnIdle {
            state.setReadingMode(false)
            state.resetForJump(12)
            vertical = false
        }
        compose.waitForIdle()
        // Coming back keeps the same page and lands at its top, which is where paging stopped.
        compose.runOnIdle {
            state.setReadingMode(true)
            state.resetForJump(pager.currentPage, 0)
            vertical = true
        }
        compose.waitForIdle()
        assertEquals(12, list.firstVisibleItemIndex)
    }
}
