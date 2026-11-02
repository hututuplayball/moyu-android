package com.moyu.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moyu.reader.data.LibraryRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun revealControls() {
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription("更多阅读选项").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader-viewport").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(400)
        }
        compose.onNodeWithContentDescription("更多阅读选项").assertIsDisplayed()
    }

    @Test fun libraryReaderInPlaceZoomBookmarksPositionAndSettingsWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LibraryRepository(context)
        val source = File(context.cacheDir, "ui-${System.nanoTime()}.pdf")
        val pdf = PdfDocument()
        try {
            repeat(8) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
                page.canvas.drawColor(Color.rgb(239, 232, 210))
                page.canvas.drawText("MOYU UI TEST ${index + 1}", 30f, 100f, Paint().apply { textSize = 36f; color = Color.BLACK })
                pdf.finishPage(page)
            }
            source.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val book = runBlocking { repository.importFile(Uri.fromFile(source)) }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(book.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(book.title)[0].performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("更多阅读选项").assertDoesNotExist()
            compose.onNodeWithText("缩略图").assertDoesNotExist()
            revealControls()
            compose.onNodeWithContentDescription("更多阅读选项").performClick()
            compose.onNodeWithText("添加书签").performClick()
            compose.onNodeWithTag("reader-viewport").performTouchInput {
                pinch(Offset(width * .35f, height * .4f), Offset(width * .65f, height * .4f),
                    Offset(width * .15f, height * .4f), Offset(width * .85f, height * .4f))
            }
            compose.onNodeWithContentDescription("关闭放大视图").assertDoesNotExist()
            compose.onNodeWithContentDescription("第 1 页").assertExists()
            compose.onNode(hasScrollToIndexAction()).assertExists()
            revealControls()
            compose.onNodeWithContentDescription("更多阅读选项").performClick()
            compose.onNodeWithText("移除书签").assertExists()
            compose.onNodeWithText("恢复原始大小").performClick()
            revealControls()
            compose.onNodeWithContentDescription("更多阅读选项").performClick()
            compose.onNodeWithText("移除书签").performClick()
            compose.onNodeWithText("上下连续").performClick()
            compose.onNodeWithText("左右翻页").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-viewport").performTouchInput {
                pinch(Offset(width * .35f, height * .4f), Offset(width * .65f, height * .4f),
                    Offset(width * .15f, height * .4f), Offset(width * .85f, height * .4f))
            }
            compose.onNodeWithContentDescription("关闭放大视图").assertDoesNotExist()
            compose.onNodeWithTag("reader-viewport").performTouchInput { doubleClick() }
            revealControls()
            compose.onNodeWithText("左右翻页").performClick()
            compose.onNodeWithText("上下连续").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 6 页").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("第 6 页").assertExists()
            revealControls() // Leave with visible controls: the next entry must still start clean.
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("墨屿").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(book.title)[0].performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 6 页").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("更多阅读选项").assertDoesNotExist()
            compose.onNodeWithText("缩略图").assertDoesNotExist()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("墨屿").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("设置").assertDoesNotExist()
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("默认阅读方式").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
            compose.onNodeWithText("深色").performClick()
            compose.onNodeWithText("浅色").performClick()
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.waitForIdle()
            val restored = runBlocking { repository.getBook(book.id)!! }
            assertEquals(5, restored.currentPage)
            assertEquals("vertical", restored.mode)
        } finally {
            runBlocking { repository.deleteBook(book.id) }
            source.delete()
        }
    }
}
