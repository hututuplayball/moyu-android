package com.moyu.reader

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.GroupEntity
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.data.READ_VIEW_PDF
import com.moyu.reader.data.READ_VIEW_TEXT
import com.moyu.reader.data.ReaderPreferences
import com.moyu.reader.data.TEXT_READY
import com.moyu.reader.ui.LibraryScreen
import com.moyu.reader.ui.MoyuTheme
import com.moyu.reader.ui.PaperConfirmation
import com.moyu.reader.ui.ReadingModePanel
import com.moyu.reader.ui.SettingsScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class LibraryUiTest {
    @get:Rule val compose = createComposeRule()

    private val sea = book("a", "海边来信", 0)
    private val mountain = book("b", "群山回声", 1)
    private val islands = GroupEntity("islands", "海岛", 0, updatedAt = 10)
    private val travel = GroupEntity("travel", "远行", 1, updatedAt = 10)
    private var dispatchBack: () -> Unit = {}

    @Test fun emptyLibraryImportsAndOpensSettings() {
        var imports = 0
        var settings = 0
        showLibrary(onImport = { imports++ }, onSettings = { settings++ })
        compose.onNodeWithText("墨屿").assertIsDisplayed()
        compose.onNodeWithText("方寸之间，自有山海。").assertExists()
        compose.onNodeWithText("让第一本漫画书，住进墨屿").assertExists()
        // The open shelf decides the format, so no second "导入" sheet may appear.
        compose.onNodeWithText("导入漫画").performClick()
        compose.onNodeWithText("漫画文件").assertDoesNotExist()
        assertEquals(1, imports)
        compose.onNodeWithContentDescription("设置").performClick()
        assertEquals(1, settings)

        compose.onNodeWithContentDescription("导入书籍").performClick()
        assertEquals(2, imports)
    }

    @Test fun switchingToEbookShowsItsOwnEmptyShelfAndImport() {
        val selected = mutableStateOf(KIND_COMIC)
        var ebookImports = 0
        showLibrary(
            books = { listOf(sea) },
            groups = { listOf(islands) },
            kind = { selected.value },
            onKind = { selected.value = it },
            onImportEbook = { ebookImports++ },
        )
        compose.onNodeWithTag("library-kind:ebook").performClick()
        compose.onNodeWithText("让第一本电子书，住进墨屿").assertExists()
        // The ebook tab imports ebooks straight away, with no format sheet in between.
        compose.onNodeWithText("导入电子书").performClick()
        compose.onNodeWithText("电子书文件").assertDoesNotExist()
        assertEquals(1, ebookImports)
        compose.onNodeWithTag("library-kind:comic").performClick()
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithTag("book:${sea.id}").assertExists()
    }

    @Test fun swipingTheShelfSwitchesKinds() {
        val selected = mutableStateOf(KIND_COMIC)
        showLibrary(
            books = { listOf(sea, sea.copy(id = "ebook", title = "电子书", kind = KIND_EBOOK)) },
            kind = { selected.value },
            onKind = { selected.value = it },
        )
        val grid = compose.onNodeWithTag("library-grid")
        grid.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(KIND_EBOOK, selected.value)
        grid.performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(KIND_COMIC, selected.value)
    }

    @Test fun ebookShelfKeepsComicBooksOutAndShowsEbookTiles() {
        val ebook = sea.copy(kind = KIND_EBOOK)
        showLibrary(books = { listOf(sea, ebook) }, kind = { KIND_EBOOK })
        compose.onNodeWithTag("book:${sea.id}").assertDoesNotExist()
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithTag("book:${ebook.id}").assertExists()
        compose.onNodeWithText("电子书 · PDF · 共 18 页").assertExists()
    }

    @Test fun ebookShelfLabelsAPdfWithTheTextViewItWasLastReadIn() {
        val flow = sea.copy(kind = KIND_EBOOK, textState = TEXT_READY, readView = READ_VIEW_TEXT,
            textPage = 26, textPageCount = 52, textProgress = 52, currentPage = 12, mode = "horizontal", updatedAt = 20)
        val shelf = mutableStateOf(listOf(flow))
        showLibrary(books = { shelf.value }, kind = { KIND_EBOOK })
        // The continue-reading card labels flowing text by percent, without a page count.
        compose.onNodeWithText("电子书 · PDF · 52%").assertExists()
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithText("电子书 · PDF · 52%").assertExists()
        // Reading the original pages again labels the book with the PDF's own page count.
        shelf.value = listOf(flow.copy(readView = READ_VIEW_PDF))
        compose.waitForIdle()
        compose.onNodeWithText("电子书 · PDF · 72% · 18 页").assertExists()
    }

    @Test fun importingDisablesBothImportActions() {
        var imports = 0
        showLibrary(importing = true, onImport = { imports++ })
        compose.onNodeWithContentDescription("导入书籍").assertIsNotEnabled()
        compose.onNodeWithText("正在导入…").assertIsNotEnabled()
        compose.onNodeWithText("正在导入，请稍候…").assertExists()
        assertEquals(0, imports)
    }

    @Test fun rootShowsContinueReadingAboveAllBooksAndOpensIt() {
        val reading = book("r", "海边旧信", 2).copy(currentPage = 5, mode = "vertical", updatedAt = 30)
        var opened: BookEntity? = null
        showLibrary(books = { listOf(reading, sea.copy(groupId = islands.id)) }, groups = { listOf(islands) },
            onOpen = { opened = it })
        compose.onNodeWithTag("continue-reading").assertIsDisplayed()
        compose.onNodeWithText("正在阅读").assertExists()
        compose.onNodeWithText("第 6 页 / 共 18 页").assertExists()
        compose.onNodeWithTag("all-books").assertExists()
        compose.onNodeWithText("海岛").assertExists()
        compose.onNodeWithText("未分组").assertExists()
        compose.onNodeWithTag("book:${reading.id}").assertDoesNotExist()
        compose.onNodeWithTag("continue-reading").performClick()
        assertEquals(reading.id, opened?.id)
    }

    @Test fun rootSearchFindsBooksAcrossGroupsWithoutChangingLibrary() {
        showLibrary(books = { listOf(sea, mountain.copy(groupId = islands.id)) }, groups = { listOf(islands) })
        compose.onNodeWithTag("all-books").assertExists()
        compose.onNodeWithContentDescription("搜索书籍").performClick()
        compose.onNodeWithContentDescription("搜索书名").performTextReplacement("群山")
        compose.onNodeWithTag("book:${mountain.id}").assertExists()
        compose.onNodeWithTag("book:${sea.id}").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索书名").performTextReplacement("没有这本书")
        compose.onNodeWithText("没有找到匹配的书").assertExists()
        compose.onNodeWithContentDescription("关闭搜索").performClick()
        compose.onNodeWithTag("book:${mountain.id}").assertDoesNotExist()
    }

    @Test fun shelvesIsolateBooksAndBackUnwindsOneLevel() {
        val loose = book("c", "海边手记", 2)
        showLibrary(
            books = { listOf(sea.copy(groupId = islands.id), mountain.copy(groupId = travel.id), loose) },
            groups = { listOf(islands, travel) },
        )
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithTag("book:${sea.id}").assertIsDisplayed()
        compose.onNodeWithTag("book:${mountain.id}").assertExists()
        compose.onNodeWithTag("book:${loose.id}").assertExists()
        compose.runOnIdle { dispatchBack() }
        compose.onNodeWithText("海岛").performClick()
        compose.onNodeWithTag("book:${sea.id}").assertIsDisplayed()
        compose.onNodeWithTag("book:${mountain.id}").assertDoesNotExist()
        compose.onNodeWithTag("book:${loose.id}").assertDoesNotExist()
        compose.runOnIdle { dispatchBack() }
        compose.onNodeWithText("未分组").performClick()
        scrollToBook(loose)
        compose.onNodeWithTag("book:${loose.id}").assertIsDisplayed()
        compose.onNodeWithTag("book:${sea.id}").assertDoesNotExist()
        compose.runOnIdle { dispatchBack() }
        compose.onNodeWithTag("all-books").assertExists()
        compose.onNodeWithText("未分组").assertExists()
    }

    @Test fun twoBookRemovalCanBeCancelledThenConfirmed() {
        val books = mutableStateOf(listOf(sea, mountain))
        val deleted = mutableListOf<List<String>>()
        showLibrary(books = { books.value }, onDeleteBooks = { ids ->
            deleted += ids
            books.value = books.value.filterNot { it.id in ids }
        })
        compose.onNodeWithTag("all-books").performClick()
        selectBoth()
        compose.onNodeWithTag("book:${sea.id}").assertIsSelected()
        compose.onNodeWithTag("book:${mountain.id}").assertIsSelected()
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()
        compose.onNodeWithText("移除").performClick()
        compose.onNodeWithText("不会删除原始文件。", substring = true).assertExists()
        compose.onNodeWithText("保留").performClick()
        assertTrue(deleted.isEmpty())
        assertEquals(2, books.value.size)
        compose.onNodeWithText("已选 2 本").assertExists()
        compose.onNodeWithText("移除").performClick()
        compose.onNodeWithText("移出书架").performClick()
        assertEquals(listOf(listOf(sea.id, mountain.id)), deleted)
        compose.onNodeWithText("已选 0 本").assertExists()
        compose.onNodeWithText("移除").assertIsNotEnabled()
    }

    @Test fun removalDialogPlacesRemoveLeftOfKeep() {
        showLibrary(books = { listOf(sea) })
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithTag("book:${sea.id}").performClick()
        compose.onNodeWithText("移除").performClick()
        val remove = compose.onNodeWithText("移出书架").fetchSemanticsNode().boundsInRoot
        val keep = compose.onNodeWithText("保留").fetchSemanticsNode().boundsInRoot
        assertTrue(remove.left < keep.left)
    }

    @Test fun bookWithMissingOriginalCarriesAWarningTag() {
        showLibrary(books = { listOf(sea) }, unavailable = setOf(sea.id))
        compose.onNodeWithTag("all-books").performClick()
        scrollToBook(sea)
        compose.onNodeWithTag("unavailable:${sea.id}", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("原文件失效").assertExists()
    }

    @Test fun selectedBooksMoveIntoGroupAndDisappearFromUngrouped() {
        val books = mutableStateOf(listOf(sea, mountain))
        val moves = mutableListOf<Pair<List<String>, String?>>()
        showLibrary(books = { books.value }, groups = { listOf(islands) }, onMoveBooks = { ids, groupId ->
            moves += ids to groupId
            books.value = books.value.map { if (it.id in ids) it.copy(groupId = groupId) else it }
        })
        compose.onNodeWithText("未分组").performClick()
        selectBoth()
        compose.onNodeWithText("移动到…").performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(moves.isEmpty())
        compose.onNodeWithText("已选 2 本").assertExists()
        compose.onNodeWithText("移动到…").performClick()
        compose.onNodeWithText(islands.name).performClick()
        assertEquals(listOf(listOf(sea.id, mountain.id) to islands.id), moves)
        compose.onNodeWithText("已选 0 本").assertExists()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("一角山海，尚未入墨。").assertExists()
        compose.runOnIdle { dispatchBack() }
        compose.onNodeWithText(islands.name).performClick()
        compose.onNodeWithTag("book:${sea.id}").assertExists()
        compose.onNodeWithTag("book:${mountain.id}").assertExists()
    }

    @Test fun groupCreationValidatesBlankAndLongNamesAndTrimsSavedName() {
        val groups = mutableStateOf(emptyList<GroupEntity>())
        val created = mutableListOf<String>()
        showLibrary(books = { listOf(sea) }, groups = { groups.value }, onCreateGroup = { name, _ ->
            created += name
            groups.value = listOf(islands.copy(name = name))
        })
        compose.onNodeWithContentDescription("新建分组").performClick()
        assertNameValidation()
        compose.onNodeWithContentDescription("分组名称").performTextReplacement("不会保存")
        compose.onNodeWithText("取消").performClick()
        assertTrue(created.isEmpty())
        compose.onNodeWithContentDescription("新建分组").performClick()
        compose.onNodeWithContentDescription("分组名称").performTextReplacement("  海岛  ")
        compose.onNodeWithText("保存").performClick()
        assertEquals(listOf("海岛"), created)
        compose.onNodeWithText("海岛").assertExists()
    }

    @Test fun renameAndDissolveGroupKeepBooksAndSupportCancellation() {
        val groups = mutableStateOf(listOf(islands))
        val books = mutableStateOf(listOf(sea.copy(groupId = islands.id), mountain.copy(groupId = islands.id)))
        val renamed = mutableListOf<Pair<String, String>>()
        val dissolved = mutableListOf<String>()
        showLibrary(books = { books.value }, groups = { groups.value }, onRenameGroup = { id, name ->
            renamed += id to name
            groups.value = groups.value.map { if (it.id == id) it.copy(name = name) else it }
        }, onDeleteGroup = { id ->
            dissolved += id
            groups.value = groups.value.filterNot { it.id == id }
            books.value = books.value.map { if (it.groupId == id) it.copy(groupId = null) else it }
        })
        compose.onNodeWithText(islands.name).performClick()
        compose.onNodeWithContentDescription("分组操作").performClick()
        compose.onNodeWithText("重命名分组").performClick()
        assertNameValidation()
        compose.onNodeWithText("取消").performClick()
        assertTrue(renamed.isEmpty())
        compose.onNodeWithContentDescription("分组操作").performClick()
        compose.onNodeWithText("重命名分组").performClick()
        compose.onNodeWithContentDescription("分组名称").performTextReplacement("  岛屿  ")
        compose.onNodeWithText("保存").performClick()
        assertEquals(listOf(islands.id to "岛屿"), renamed)
        compose.onNodeWithText("岛屿").assertExists()
        compose.onNodeWithContentDescription("分组操作").performClick()
        compose.onNodeWithText("解散分组").performClick()
        compose.onNodeWithText("分组内的 2 本书将回到未分组，书籍与阅读记录会保留。").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertTrue(dissolved.isEmpty())
        compose.onNodeWithContentDescription("分组操作").performClick()
        compose.onNodeWithText("解散分组").performClick()
        compose.onNodeWithText("解散分组").performClick()
        assertEquals(listOf(islands.id), dissolved)
        assertEquals(listOf(sea, mountain), books.value)
        compose.onNodeWithContentDescription("返回书架").assertDoesNotExist()
        compose.onNodeWithText("未分组").performClick()
        compose.onNodeWithTag("book:${sea.id}").assertExists()
        compose.onNodeWithTag("book:${mountain.id}").assertExists()
    }

    @Test fun settingsChoicesAndTogglesUpdatePreferences() {
        val prefs = mutableStateOf(ReaderPreferences())
        var returned = false
        showSettings(preferences = { prefs.value }, onBack = { returned = true },
            onTheme = { prefs.value = prefs.value.copy(theme = it) },
            onDefaultMode = { prefs.value = prefs.value.copy(defaultMode = it) },
            onRightToLeft = { prefs.value = prefs.value.copy(rightToLeft = it) },
            onKeepScreenOn = { prefs.value = prefs.value.copy(keepScreenOn = it) },
            onPrefetchRadius = { prefs.value = prefs.value.copy(prefetchRadius = it) })
        compose.onNodeWithText("左右翻页").performClick().assertIsSelected()
        assertEquals("horizontal", prefs.value.defaultMode)
        assertFalse(prefs.value.rightToLeft)
        // Right-to-left paging is no longer offered anywhere in the settings.
        compose.onNodeWithText("左右翻页 · 从右到左").assertDoesNotExist()
        compose.onNodeWithText("上下连续").performClick().assertIsSelected()
        assertEquals("vertical", prefs.value.defaultMode)
        // The warm radius sits at the bottom of the first card; one page each way is the narrowest.
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("1 页"))
        compose.onNodeWithText("1 页").performClick().assertIsSelected()
        assertEquals(1, prefs.value.prefetchRadius)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("阅读时保持屏幕常亮"))
        compose.onNodeWithText("阅读时保持屏幕常亮").performClick().assertIsOff()
        assertFalse(prefs.value.keepScreenOn)
        compose.onNodeWithTag("settings-list").performScrollToIndex(1)
        compose.onNodeWithText("深色").performClick().assertIsSelected()
        assertEquals("dark", prefs.value.theme)
        compose.onNodeWithContentDescription("返回书架").performClick()
        assertTrue(returned)
    }

    @Test fun settingsLetterPageOpensAndBackReturnsToTheList() {
        var leftSettings = false
        showSettings(onBack = { leftSettings = true })
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("胡图图打篮球"))
        compose.onNodeWithText("胡图图打篮球").performClick()
        compose.onNodeWithText("把书留在岸上，把灯留给你。").assertExists()
        compose.onNodeWithText("谢谢把它装进口袋。愿你在方寸之间，看见自己的山海。").assertExists()
        // The account code lives at the bottom of the letter, on white so it still scans.
        compose.onNodeWithTag("author-page").performScrollToNode(hasText("微信扫一扫，或搜索「效率工具铺」"))
        compose.onNodeWithContentDescription("公众号二维码").assertExists()
        // The hardware back closes the letter first; the shelf only hears about it from the list.
        compose.runOnIdle { dispatchBack() }
        compose.onNodeWithText("把书留在岸上，把灯留给你。").assertDoesNotExist()
        assertFalse(leftSettings)
        compose.onNodeWithContentDescription("返回书架").performClick()
        assertTrue(leftSettings)
    }

    @Test fun settingsResourcesOpenTheLinksAndTheAccountCodeWithoutCrashing() {
        showSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("阅读资源"))
        compose.onNodeWithText("Z-Library 中文站").assertExists()
        compose.onNodeWithText("zh.zlib.bz").assertExists()
        compose.onNodeWithText("公众号 · 效率工具铺").assertExists()
        // A phone without a browser must swallow the failure, never crash the settings page.
        compose.onNodeWithText("Z-Library 中文站").performClick()
        compose.onNodeWithText("Z-Library 中文站").assertExists()
        // The account row has no URL: it opens the code inside the app instead.
        compose.onNodeWithText("公众号 · 效率工具铺").performClick()
        compose.onNodeWithContentDescription("公众号二维码").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("公众号二维码").assertDoesNotExist()
    }

    @Test fun customPrefetchDepthStepsWithinRange() {
        val prefs = mutableStateOf(ReaderPreferences(prefetchRadius = 3, lastCustomPrefetchRadius = 4))
        showSettings(preferences = { prefs.value },
            onPrefetchRadius = { prefs.value = prefs.value.copy(prefetchRadius = it) })
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("自定义"))
        compose.onNodeWithText("自定义").performClick()
        // The stepper reopens where the last custom pick left off, not at a fixed four.
        compose.onNodeWithText("4 页").assertExists()
        compose.onNodeWithContentDescription("增加").performClick()
        compose.onNodeWithText("5 页").assertExists()
        compose.onNodeWithContentDescription("减少").performClick()
        compose.onNodeWithText("4 页").assertExists()
        compose.onNodeWithText("确定").performClick()
        assertEquals(4, prefs.value.prefetchRadius)
        // The custom segment now carries its real depth instead of a generic label.
        compose.onNodeWithText("4 页").assertIsSelected()
        compose.onNodeWithText(
            "以当前页为中心，向前后各预渲染 4 页。",
            substring = true,
        ).assertExists()
        // Picking a preset again leaves the custom mode behind but remembers the custom depth.
        compose.onNodeWithText("2 页").performClick().assertIsSelected()
        assertEquals(2, prefs.value.prefetchRadius)
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithText("4 页").assertExists()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun customPrefetchDepthStopsAtTen() {
        val prefs = mutableStateOf(ReaderPreferences(prefetchRadius = 10, lastCustomPrefetchRadius = 10))
        showSettings(preferences = { prefs.value },
            onPrefetchRadius = { prefs.value = prefs.value.copy(prefetchRadius = it) })
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("10 页"))
        compose.onNodeWithText("10 页").assertIsSelected()
        compose.onNodeWithText("10 页").performClick()
        // Ten is the ceiling, so the dialog can open straight on it and save without clamping.
        compose.onNodeWithText("确定").performClick()
        assertEquals(10, prefs.value.prefetchRadius)
        compose.onNodeWithText(
            "以当前页为中心，向前后各预渲染 10 页。",
            substring = true,
        ).assertExists()
    }

    @Test fun readingModePanelOffersOnlyTwoDirections() {
        var picked: Pair<String, Boolean>? = null
        compose.setContent {
            MoyuTheme(ReaderPreferences()) {
                ReadingModePanel(mode = "horizontal", rightToLeft = true) { mode, rtl ->
                    picked = mode to rtl
                }
            }
        }
        // Even a book that stored right-to-left is shown only the horizontal mode there is.
        compose.onNodeWithText("左右翻页").assertExists()
        compose.onNodeWithText("左右翻页 · 从右到左").assertDoesNotExist()
        compose.onNodeWithText("左右翻页").performClick()
        assertEquals("horizontal" to false, picked)
    }

    @Test fun settingsHasNoBackupControlsAndEndsWithAVerse() {
        showSettings()
        compose.onNodeWithText("记录备份").assertDoesNotExist()
        compose.onNodeWithText("立即备份").assertDoesNotExist()
        compose.onNodeWithText("停止自动备份").assertDoesNotExist()
        compose.onNodeWithText("下载/墨屿", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("settings-list").performScrollToIndex(4)
        compose.onNodeWithText("方寸之间，自有山海。").assertExists()
        compose.onNodeWithText("你读到哪一页，哪一页就为你亮着灯。", substring = true).assertExists()
    }

    @Test fun reorderFailureRestoresSourceAndSuccessWaitsForSourceWithoutOverlappingWrites() {
        val books = mutableStateOf(listOf(sea, mountain))
        var outcome = CompletableDeferred<Boolean>()
        val requests = mutableListOf<List<String>>()
        showLibrary(books = { books.value }, onReorder = { ids -> requests += ids; outcome.await() })
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithText("整理").performClick()
        val retryStaleAction = compose.onNodeWithTag("book:${sea.id}").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == "向后移动" }.action
        compose.runOnIdle {
            assertTrue(retryStaleAction())
            assertFalse(retryStaleAction())
        }
        compose.onNodeWithText("正在保存排序…").assertExists()
        assertEquals(listOf(listOf(mountain.id, sea.id)), requests)
        assertLeftOf(mountain, sea)
        compose.runOnIdle { outcome.complete(false) }
        compose.onNodeWithText("正在保存排序…").assertDoesNotExist()
        assertLeftOf(sea, mountain)
        compose.runOnIdle { outcome = CompletableDeferred() }
        val retry = compose.onNodeWithTag("book:${sea.id}").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == "向后移动" }.action
        compose.runOnIdle { assertTrue(retry()) }
        compose.runOnIdle { outcome.complete(true) }
        compose.onNodeWithText("正在保存排序…").assertExists()
        compose.runOnIdle { assertFalse(retryStaleAction()) }
        assertEquals(2, requests.size)
        compose.runOnIdle { books.value = listOf(mountain.copy(sortOrder = 0), sea.copy(sortOrder = 1)) }
        compose.onNodeWithText("正在保存排序…").assertDoesNotExist()
        assertLeftOf(mountain, sea)
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("整理").performClick()
        assertLeftOf(mountain, sea)
    }

    @Test fun normalModeLongPressIsReorderingOnly() {
        val requests = mutableListOf<List<String>>()
        var opens = 0
        showLibrary(books = { listOf(sea, mountain) }, onOpen = { opens++ }, onReorder = { ids -> requests += ids; false })
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithTag("book:${sea.id}").performTouchInput {
            down(center)
            moveTo(center, delayMillis = 650)
            up()
        }
        // A long press never turns into organize mode or a selection on its own.
        compose.onNodeWithTag("organize-bar").assertDoesNotExist()
        assertTrue(requests.isEmpty())
        assertEquals(0, opens)
    }

    @Test fun gridLongPressDragCommitsExactlyOnceWithoutOpeningOrSelectingBook() {
        val requests = mutableListOf<List<String>>()
        var opens = 0
        showLibrary(books = { listOf(sea, mountain) }, onOpen = { opens++ }, onReorder = { ids -> requests += ids; false })
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithText("整理").performClick()
        assertFalse(compose.onNodeWithTag("book:${sea.id}").fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        dragFirstBook(shouldCancel = false)
        compose.waitForIdle()
        assertEquals(listOf(listOf(mountain.id, sea.id)), requests)
        assertEquals(0, opens)
        compose.onNodeWithText("已选 0 本").assertExists()
        assertLeftOf(sea, mountain)
    }

    @Test fun cancelledGridDragDoesNotPersistOrSelect() {
        val requests = mutableListOf<List<String>>()
        showLibrary(books = { listOf(sea, mountain) }, onReorder = { ids -> requests += ids; false })
        compose.onNodeWithTag("all-books").performClick()
        compose.onNodeWithText("整理").performClick()
        dragFirstBook(shouldCancel = true)
        compose.waitForIdle()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("已选 0 本").assertExists()
        assertLeftOf(sea, mountain)
    }

    @Test fun normalModeLongPressDragsCoverWithoutEnteringOrganizeFirst() {
        val requests = mutableListOf<List<String>>()
        showLibrary(books = { listOf(sea, mountain) }, onReorder = { ids -> requests += ids; false })
        compose.onNodeWithTag("all-books").performClick()
        // No tap on 整理: the first long press is the drag itself and never opens the organize bar.
        dragFirstBook(shouldCancel = false)
        compose.waitForIdle()
        assertEquals(listOf(listOf(mountain.id, sea.id)), requests)
        compose.onNodeWithTag("organize-bar").assertDoesNotExist()
    }

    @Test fun allBooksEntryMatchesContinueCardSize() {
        val reading = book("r", "海边旧信", 2).copy(currentPage = 5, mode = "vertical", updatedAt = 30)
        showLibrary(books = { listOf(reading, sea) })
        val now = compose.onNodeWithTag("continue-reading").fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithTag("all-books").fetchSemanticsNode().boundsInRoot
        assertEquals(now.height.toDouble(), all.height.toDouble(), 0.5)
        assertEquals(now.width.toDouble(), all.width.toDouble(), 0.5)
    }

    @Test fun newGroupTileMatchesGroupCardHeight() {
        showLibrary(groups = { listOf(islands) })
        val card = compose.onNodeWithTag("group:${islands.id}").fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithTag("new-group").fetchSemanticsNode().boundsInRoot
        assertEquals(card.height.toDouble(), tile.height.toDouble(), 0.5)
    }

    @Test fun groupLongPressDragReordersGroups() {
        val requests = mutableListOf<List<String>>()
        val groups = mutableStateOf(listOf(islands, travel))
        showLibrary(groups = { groups.value }, onReorderGroups = { ids ->
            requests += ids
            groups.value = ids.mapIndexed { index, id ->
                groups.value.first { it.id == id }.copy(sortOrder = index.toLong())
            }
            true
        })
        val grid = compose.onNodeWithTag("library-grid").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("group:${islands.id}").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("group:${travel.id}").fetchSemanticsNode().boundsInRoot
        val start = Offset(first.center.x - grid.left, first.center.y - grid.top)
        val end = Offset(second.center.x - grid.left, second.center.y - grid.top)
        compose.onNodeWithTag("library-grid").performTouchInput {
            down(start)
            moveTo(start, delayMillis = 650)
            moveTo(end, delayMillis = 32)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(listOf(travel.id, islands.id)), requests)
        val movedFirst = compose.onNodeWithTag("group:${travel.id}").fetchSemanticsNode().boundsInRoot
        val movedSecond = compose.onNodeWithTag("group:${islands.id}").fetchSemanticsNode().boundsInRoot
        assertTrue(movedFirst.center.x < movedSecond.center.x)
    }

    @Test fun groupDroppedOnUngroupedMovesToEndInsteadOfCrashing() {
        val requests = mutableListOf<List<String>>()
        val groups = mutableStateOf(listOf(islands, travel))
        showLibrary(
            books = { listOf(sea) },
            groups = { groups.value },
            onReorderGroups = { ids -> requests += ids; true },
        )
        val grid = compose.onNodeWithTag("library-grid").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("group:${islands.id}").fetchSemanticsNode().boundsInRoot
        val ungrouped = compose.onNodeWithTag("group:ungrouped").fetchSemanticsNode().boundsInRoot
        val start = Offset(first.center.x - grid.left, first.center.y - grid.top)
        val end = Offset(ungrouped.center.x - grid.left, ungrouped.center.y - grid.top)
        compose.onNodeWithTag("library-grid").performTouchInput {
            down(start)
            moveTo(start, delayMillis = 650)
            // Claim the pointer horizontally first; a pure vertical drag would be read as grid scrolling.
            moveTo(Offset(start.x + 80f, start.y), delayMillis = 32)
            moveTo(end, delayMillis = 32)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(listOf(travel.id, islands.id)), requests)
    }

    @Test fun groupWithBooksLongPressStillStartsReordering() {
        val requests = mutableListOf<List<String>>()
        val groups = mutableStateOf(listOf(islands, travel))
        showLibrary(
            books = { listOf(sea.copy(groupId = islands.id), mountain.copy(groupId = travel.id)) },
            groups = { groups.value },
            onReorderGroups = { ids ->
                requests += ids
                groups.value = ids.mapIndexed { index, id ->
                    groups.value.first { it.id == id }.copy(sortOrder = index.toLong())
                }
                true
            },
        )
        val grid = compose.onNodeWithTag("library-grid").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("group:${islands.id}").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("group:${travel.id}").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("library-grid").performTouchInput {
            down(Offset(first.center.x - grid.left, first.center.y - grid.top))
            moveTo(Offset(first.center.x - grid.left, first.center.y - grid.top), delayMillis = 650)
            moveTo(Offset(second.center.x - grid.left, second.center.y - grid.top), delayMillis = 32)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(listOf(travel.id, islands.id)), requests)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h360dp")
    fun confirmationKeepsActionsVisibleWithLargeTextOnShortScreen() {
        var confirmed = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                MoyuTheme(ReaderPreferences()) {
                    PaperConfirmation("从书架移除？", "只移除导入副本，不会删除原始 PDF 文件。".repeat(20),
                        "移除副本", {}, { confirmed++ }, dismissLabel = "保留")
                }
            }
        }
        compose.onNodeWithText("保留").assertIsDisplayed()
        compose.onNodeWithText("移除副本").assertIsDisplayed().performClick()
        assertEquals(1, confirmed)
    }

    private fun selectBoth() {
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithTag("book:${sea.id}").performClick()
        compose.onNodeWithTag("book:${mountain.id}").performClick()
    }

    private fun assertNameValidation() {
        compose.onNodeWithContentDescription("分组名称").performTextReplacement("   ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithContentDescription("分组名称").performTextReplacement("字".repeat(81))
        compose.onNodeWithText("名称最多 80 个字符").assertExists()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithContentDescription("分组名称").performTextReplacement("字".repeat(80))
        compose.onNodeWithText("保存").assertIsEnabled()
    }

    private fun scrollToBook(book: BookEntity) {
        compose.onNodeWithTag("library-grid").performScrollToNode(hasTestTag("book:${book.id}"))
    }

    private fun assertLeftOf(left: BookEntity, right: BookEntity) {
        val leftBounds = compose.onNodeWithTag("book:${left.id}").fetchSemanticsNode().boundsInRoot
        val rightBounds = compose.onNodeWithTag("book:${right.id}").fetchSemanticsNode().boundsInRoot
        assertTrue("Expected ${left.title} before ${right.title}", leftBounds.center.x < rightBounds.center.x)
    }

    private fun dragFirstBook(shouldCancel: Boolean) {
        val grid = compose.onNodeWithTag("library-grid").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("book:${sea.id}").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("book:${mountain.id}").fetchSemanticsNode().boundsInRoot
        val start = Offset(first.center.x - grid.left, first.top + first.height / 4 - grid.top)
        val end = Offset(second.center.x - grid.left, second.top + second.height / 4 - grid.top)
        compose.onNodeWithTag("library-grid").performTouchInput {
            down(start)
            moveTo(start, delayMillis = 650)
            moveTo(end, delayMillis = 32)
            if (shouldCancel) cancel() else up()
        }
    }

    private fun showLibrary(
        books: () -> List<BookEntity> = { emptyList() },
        groups: () -> List<GroupEntity> = { emptyList() },
        importing: Boolean = false,
        onImport: () -> Unit = {},
        onOpen: (BookEntity) -> Unit = {},
        onSettings: () -> Unit = {},
        onCreateGroup: (String, Int) -> Unit = { _, _ -> },
        onRenameGroup: (String, String) -> Unit = { _, _ -> },
        onDeleteGroup: (String) -> Unit = {},
        onMoveBooks: (List<String>, String?) -> Unit = { _, _ -> },
        onReorder: suspend (List<String>) -> Boolean = { false },
        onReorderGroups: suspend (List<String>) -> Boolean = { false },
        onDeleteBooks: (List<String>) -> Unit = {},
        unavailable: Set<String> = emptySet(),
        kind: () -> Int = { KIND_COMIC },
        onKind: (Int) -> Unit = {},
        onImportEbook: () -> Unit = {},
    ) {
        compose.setContent {
            val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            dispatchBack = { dispatcher.onBackPressed() }
            MoyuTheme(ReaderPreferences()) {
                LibraryScreen(books = books(), importing = importing, onImport = onImport,
                    onOpen = onOpen,
                    onSettings = onSettings, groups = groups(), onCreateGroup = onCreateGroup,
                    onRenameGroup = onRenameGroup, onDeleteGroup = onDeleteGroup, onMoveBooks = onMoveBooks,
                    onReorderBooks = onReorder, onReorderGroups = onReorderGroups, onDeleteBooks = onDeleteBooks,
                    unavailable = unavailable, kind = kind(), onKind = onKind,
                    onImportEbook = onImportEbook)
            }
        }
    }

    private fun showSettings(
        preferences: () -> ReaderPreferences = { ReaderPreferences() },
        onBack: () -> Unit = {},
        onTheme: (String) -> Unit = {},
        onDefaultMode: (String) -> Unit = {},
        onRightToLeft: (Boolean) -> Unit = {},
        onKeepScreenOn: (Boolean) -> Unit = {},
        onPrefetchRadius: (Int) -> Unit = {},
    ) {
        compose.setContent {
            val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            dispatchBack = { dispatcher.onBackPressed() }
            MoyuTheme(preferences()) {
                SettingsScreen(preferences = preferences(), onBack = onBack, onTheme = onTheme,
                    onDefaultMode = onDefaultMode, onRightToLeft = onRightToLeft, onKeepScreenOn = onKeepScreenOn,
                    version = "0.9.0", onPrefetchRadius = onPrefetchRadius)
            }
        }
    }

    private fun book(key: String, title: String, order: Long) = BookEntity(
        key.repeat(64), title, "$key.pdf", 18, importedAt = 10, updatedAt = 10, sortOrder = order,
    )
}