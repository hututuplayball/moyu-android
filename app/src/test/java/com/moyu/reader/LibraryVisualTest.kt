package com.moyu.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.GroupEntity
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.data.ReaderPreferences
import com.moyu.reader.ui.library.LibraryScreen
import com.moyu.reader.ui.MoyuTheme
import com.moyu.reader.ui.SettingsScreen
import com.moyu.reader.ui.SplashScreen
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun renderShelfAndOrganizing() {
        val first = BookEntity("a".repeat(64), "海边来信", "a.pdf", 180, currentPage = 41, mode = "vertical",
            importedAt = 10, updatedAt = 20, sortOrder = 0)
        val second = BookEntity("b".repeat(64), "群山回声", "b.pdf", 96, importedAt = 10, updatedAt = 10, sortOrder = 1)
        compose.setContent {
            MoyuTheme(ReaderPreferences()) {
                LibraryScreen(books = listOf(first, second), importing = false, onImport = {}, onOpen = {},
                    onSettings = {}, groups = listOf(GroupEntity("islands", "海岛", 0)),
                    onCreateGroup = { _, _ -> }, onRenameGroup = { _, _ -> }, onDeleteGroup = {},
                    onMoveBooks = { _, _ -> }, onReorderBooks = { true }, onReorderGroups = { true },
                    onDeleteBooks = {})
            }
        }
        save("shelf")
        compose.onNodeWithTag("all-books").performClick()
        save("all-books")
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithTag("book:${first.id}").performClick()
        save("organizing")
        compose.onNodeWithText("移除").performClick()
        compose.onNodeWithText("从书架移除 1 本书？").assertExists()
        compose.onNodeWithText("保留").performClick()
        compose.onNodeWithText("已选 1 本").assertExists()
    }

    @Test fun renderGroupPage() {
        val books = List(4) { index ->
            BookEntity(('a' + index).toString().repeat(64), listOf("海边来信", "群山回声", "潮汐手记", "灯塔以北")[index],
                "${index}.pdf", 120 + index * 20, currentPage = index * 7, mode = if (index < 2) "vertical" else "",
                importedAt = 10, updatedAt = 10 + index.toLong(), groupId = "islands", sortOrder = index.toLong())
        }
        compose.setContent {
            MoyuTheme(ReaderPreferences()) {
                LibraryScreen(books = books, importing = false, onImport = {}, onOpen = {},
                    onSettings = {}, groups = listOf(GroupEntity("islands", "海岛", 0)),
                    onCreateGroup = { _, _ -> }, onRenameGroup = { _, _ -> }, onDeleteGroup = {},
                    onMoveBooks = { _, _ -> }, onReorderBooks = { true }, onReorderGroups = { true },
                    onDeleteBooks = {})
            }
        }
        compose.onNodeWithText("海岛").performClick()
        save("group")
    }

    @Test fun renderEbookShelf() {
        val comics = listOf(
            BookEntity("a".repeat(64), "海边来信", "a.pdf", 180, currentPage = 41, mode = "vertical",
                importedAt = 10, updatedAt = 20, sortOrder = 0),
        )
        val ebooks = listOf(
            BookEntity("1".repeat(64), "山月记", "1.pdf", 96, currentPage = 30, mode = "vertical",
                importedAt = 20, updatedAt = 30, groupId = "essays", sortOrder = 0, kind = KIND_EBOOK),
            BookEntity("2".repeat(64), "人类简史", "2.pdf", 220, currentPage = 20, mode = "vertical",
                importedAt = 20, updatedAt = 25, groupId = "history", sortOrder = 1, kind = KIND_EBOOK),
            BookEntity("3".repeat(64), "长安的荔枝", "3.pdf", 64,
                importedAt = 20, updatedAt = 20, groupId = "fiction", sortOrder = 2, kind = KIND_EBOOK),
        )
        compose.setContent {
            MoyuTheme(ReaderPreferences()) {
                LibraryScreen(books = comics + ebooks, importing = false, onImport = {}, onOpen = {},
                    onSettings = {}, groups = listOf(
                        GroupEntity("islands", "海岛", 0),
                        GroupEntity("essays", "随笔", 1, kind = KIND_EBOOK),
                        GroupEntity("history", "历史", 2, kind = KIND_EBOOK),
                        GroupEntity("fiction", "小说", 3, kind = KIND_EBOOK),
                    ),
                    onCreateGroup = { _, _ -> }, onRenameGroup = { _, _ -> }, onDeleteGroup = {},
                    onMoveBooks = { _, _ -> }, onReorderBooks = { true }, onReorderGroups = { true },
                    onDeleteBooks = {}, kind = KIND_EBOOK)
            }
        }
        save("ebook-shelf")
        compose.onNodeWithTag("all-books").performClick()
        save("ebook-all")
    }

    @Test fun renderSearch() {
        val first = BookEntity("a".repeat(64), "海边来信", "a.pdf", 180, currentPage = 41, mode = "vertical",
            importedAt = 10, updatedAt = 20, sortOrder = 0)
        val second = BookEntity("b".repeat(64), "群山回声", "b.pdf", 96, importedAt = 10, updatedAt = 10, sortOrder = 1)
        compose.setContent {
            MoyuTheme(ReaderPreferences()) {
                LibraryScreen(books = listOf(first, second), importing = false, onImport = {}, onOpen = {},
                    onSettings = {}, groups = listOf(GroupEntity("islands", "海岛", 0)),
                    onCreateGroup = { _, _ -> }, onRenameGroup = { _, _ -> }, onDeleteGroup = {},
                    onMoveBooks = { _, _ -> }, onReorderBooks = { true }, onReorderGroups = { true },
                    onDeleteBooks = {})
            }
        }
        compose.onNodeWithContentDescription("搜索书籍").performClick()
        compose.onNodeWithContentDescription("搜索书名").performTextInput("海")
        save("search")
    }

    @Test fun renderSplash() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MoyuTheme(ReaderPreferences()) {
                SplashScreen(ready = true, onFinished = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_400)
        save("splash")
        compose.mainClock.autoAdvance = true
    }

    @Test fun renderSettings() {
        compose.setContent {
            MoyuTheme(ReaderPreferences()) {
                SettingsScreen(ReaderPreferences(), {}, {}, {}, {}, {}, version = "0.9.0")
            }
        }
        save("settings")
    }

    @Test fun renderSettingsSectionsAndAuthorLetter() {
        compose.setContent {
            MoyuTheme(ReaderPreferences(prefetchRadius = 4)) {
                SettingsScreen(ReaderPreferences(prefetchRadius = 4), {}, {}, {}, {}, {}, version = "0.9.0")
            }
        }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("1 页"))
        save("settings-reading")
        compose.onNodeWithText("4 页").performClick()
        save("prefetch-custom")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("关于作者"))
        save("settings-about")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("阅读资源"))
        save("settings-resources")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("胡图图打篮球"))
        compose.onNodeWithText("胡图图打篮球").performClick()
        save("author")
        compose.onNodeWithTag("author-page").performScrollToNode(hasText("微信扫一扫，或搜索「效率工具铺」"))
        save("author-qr")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("公众号 · 效率工具铺"))
        compose.onNodeWithText("公众号 · 效率工具铺").performClick()
        save("wechat-modal")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/ui-previews/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
