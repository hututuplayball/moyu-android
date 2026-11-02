package com.moyu.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag as dragPointer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyu.reader.R
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.FORMAT_ARCHIVE
import com.moyu.reader.data.FORMAT_EPUB
import com.moyu.reader.data.FORMAT_FOLDER
import com.moyu.reader.data.FORMAT_PDF
import com.moyu.reader.data.FORMAT_TEXT
import com.moyu.reader.data.GroupEntity
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.data.READ_VIEW_TEXT
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALL_BOOKS = "::all"
private const val UNGROUPED = "::ungrouped"
private const val ROOT = "::root"
// Long press, drag and release produce one trailing click; ignore it for a moment.
private const val TAP_BLOCK_MS = 260L

private data class CoverDrag(
    val book: BookEntity,
    val grab: Offset,
    val width: Int,
    val scope: String,
    val order: LibraryOrder,
)

private data class GroupDrag(
    val group: GroupEntity,
    val grab: Offset,
    val width: Int,
    val height: Int,
    val order: LibraryOrder,
)

// Pointer travel lives outside the drag descriptors so a moving finger only redraws the lifted
// card instead of recomposing the whole shelf.
private const val DRAG_REPEAT_MS = 90L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<BookEntity>,
    importing: Boolean,
    onImport: () -> Unit,
    onOpen: (BookEntity) -> Unit,
    onSettings: () -> Unit,
    groups: List<GroupEntity>,
    onCreateGroup: (String, Int) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onMoveBooks: (List<String>, String?) -> Unit,
    onReorderBooks: suspend (List<String>) -> Boolean,
    onReorderGroups: suspend (List<String>) -> Boolean,
    onDeleteBooks: (List<String>) -> Unit,
    unavailable: Set<String> = emptySet(),
    kind: Int = KIND_COMIC,
    onKind: (Int) -> Unit = {},
    onImportEbook: () -> Unit = onImport,
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var requestSearchFocus by remember { mutableStateOf(false) }
    // The shelf already says what it holds, so importing follows the open tab instead of asking again.
    val openImport = { if (kind == KIND_EBOOK) onImportEbook() else onImport() }
    var query by rememberSaveable { mutableStateOf("") }
    var scopeId by rememberSaveable { mutableStateOf<String?>(null) }
    var organizing by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteIds by remember { mutableStateOf<List<String>?>(null) }
    var moving by remember { mutableStateOf(false) }
    var groupActionsId by remember { mutableStateOf<String?>(null) }
    var deleteGroupId by remember { mutableStateOf<String?>(null) }
    var nameEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    var drag by remember { mutableStateOf<CoverDrag?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var lastDragTarget by remember { mutableStateOf<String?>(null) }
    var lastDragMoveAt by remember { mutableLongStateOf(0L) }
    var groupDrag by remember { mutableStateOf<GroupDrag?>(null) }
    var groupPointer by remember { mutableStateOf(Offset.Zero) }
    var lastGroupTarget by remember { mutableStateOf<String?>(null) }
    var lastGroupMoveAt by remember { mutableLongStateOf(0L) }
    var orderScope by remember { mutableStateOf<String?>(null) }
    // A long press owns the following up event, so the tap that clickable reports must be ignored.
    var tapBlockedUntil by remember { mutableLongStateOf(0L) }
    val groupOrder = remember { LibraryOrder() }
    var toolbarHeight by remember { mutableStateOf(0) }
    var pageWidth by remember { mutableStateOf(0) }
    // The shelf area's own height, kept so the empty page does not resize while it slides in.
    var contentHeight by remember { mutableStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var firstSlide by remember { mutableStateOf(true) }
    var firstKind by remember { mutableStateOf(true) }
    // A kind switch drops the open shelf; that scope reset must not also slide the whole page.
    var skipScopeSlide by remember { mutableStateOf(false) }
    val pageSlide = remember { Animatable(0f) }
    val reduceMotion = LocalMoyuReduceMotion.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val comicRootGridState = rememberLazyGridState()
    val ebookRootGridState = rememberLazyGridState()
    val shelfGridState = rememberLazyGridState()
    // The two shelves are pages of one book: dragging the shelf moves the whole page.
    val kindPagerState = rememberPagerState(
        initialPage = if (kind == KIND_EBOOK) 1 else 0,
        pageCount = { 2 },
    )
    val density = LocalDensity.current
    val coverDir = LocalContext.current.filesDir
    val edgeSize = with(density) { 64.dp.toPx() }
    val gridSidePadding = with(density) { 20.dp.toPx() }
    val order = remember { LibraryOrder() }
    val coroutineScope = rememberCoroutineScope()

    val scopedBooks = remember(books, kind) { books.filter { it.kind == kind } }
    val scopedGroups = remember(groups, kind) { groups.filter { it.kind == kind } }
    val comicCount = remember(books) { books.count { it.kind == KIND_COMIC } }
    val ebookCount = remember(books) { books.count { it.kind == KIND_EBOOK } }
    val scopeKey = scopeId ?: ROOT
    val activeGroup = scopedGroups.firstOrNull { it.id == scopeId }
    val sortedGroups = remember(scopedGroups) {
        scopedGroups.sortedWith(compareBy<GroupEntity> { it.sortOrder }.thenBy { it.id })
    }
    val sortedBooks = remember(scopedBooks) {
        scopedBooks.sortedWith(compareBy<BookEntity> { it.sortOrder }.thenBy { it.importedAt }.thenBy { it.id })
    }
    val ungroupedBooks = remember(sortedBooks) { sortedBooks.filter { it.groupId == null } }
    val previewsByGroup = remember(sortedBooks) { sortedBooks.filter { it.groupId != null }.groupBy { it.groupId!! } }
    val groupIds = remember(sortedGroups) { sortedGroups.map { it.id } }
    val orderedGroupIds = groupOrder.visible(groupIds)
    val orderedGroups = remember(orderedGroupIds, sortedGroups) {
        orderedGroupIds.mapNotNull { id -> sortedGroups.firstOrNull { it.id == id } }
    }
    val shelfBooks = remember(sortedBooks, scopeId) {
        when (scopeId) {
            null -> emptyList()
            ALL_BOOKS -> sortedBooks
            UNGROUPED -> ungroupedBooks
            else -> sortedBooks.filter { it.groupId == scopeId }
        }
    }
    val shelfIds = remember(shelfBooks) { shelfBooks.map { it.id } }
    val byId = remember(shelfBooks) { shelfBooks.associateBy { it.id } }
    val visibleIds = if (orderScope == scopeKey) order.visible(shelfIds) else shelfIds
    val visibleBooks = visibleIds.mapNotNull(byId::get)
    val searchResults = remember(sortedBooks, query) {
        val term = query.trim()
        if (term.isEmpty()) emptyList() else sortedBooks.filter { it.title.contains(term, ignoreCase = true) }
    }
    val latestSource by rememberUpdatedState(shelfIds)
    val latestGroupSource by rememberUpdatedState(groupIds)
    val reorderAllowed = scopeId != null && !order.busy
    // Reading recency is independent of the hand-arranged shelf.
    val continuing = remember(scopedBooks) {
        scopedBooks.sortedByDescending { it.updatedAt }.firstOrNull { it.hasStarted() && !it.isFinished() }
    }
    val title = when (scopeId) {
        ALL_BOOKS -> if (kind == KIND_EBOOK) "全部电子书" else "全部漫画"
        UNGROUPED -> "未分组"
        null -> ""
        else -> activeGroup?.name ?: "分组"
    }
    val gestureScope = scopeKey

    fun persistOrder(result: List<String>) {
        coroutineScope.launch {
            var success = false
            try {
                success = onReorderBooks(result)
            } finally {
                order.acknowledge(success)
                order.reconcile(latestSource)
            }
        }
    }

    fun cancelDrag() {
        val active = drag != null
        drag?.order?.cancel()
        drag = null
        dragPointer = Offset.Zero
        lastDragTarget = null
        lastDragMoveAt = 0L
        // Only a real drag leaves a trailing click to swallow.
        if (active) tapBlockedUntil = SystemClock.uptimeMillis() + TAP_BLOCK_MS
    }

    fun persistGroupOrder(result: List<String>) {
        coroutineScope.launch {
            var success = false
            try {
                success = onReorderGroups(result)
            } finally {
                groupOrder.acknowledge(success)
                groupOrder.reconcile(latestGroupSource)
            }
        }
    }

    fun cancelGroupDrag() {
        val active = groupDrag != null
        groupDrag?.order?.cancel()
        groupDrag = null
        groupPointer = Offset.Zero
        lastGroupTarget = null
        lastGroupMoveAt = 0L
        if (active) tapBlockedUntil = SystemClock.uptimeMillis() + TAP_BLOCK_MS
    }

    fun leaveOrganize() {
        cancelDrag()
        organizing = false
        selection = emptySet()
    }

    fun closeSearch() {
        query = ""
        searchVisible = false
        requestSearchFocus = false
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun navigate(target: String?) {
        if (target == scopeId) return
        leaveOrganize()
        closeSearch()
        forward = target != null
        scopeId = target
    }

    fun selectBook(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun tapAllowed(): Boolean = SystemClock.uptimeMillis() >= tapBlockedUntil

    val gridState = if (scopeId == null) {
        if (kind == KIND_EBOOK) ebookRootGridState else comicRootGridState
    } else shelfGridState
    // The organizer bar is an overlay, so the grid reserves room for it. The empty page has to give up
    // the same room; otherwise it is taller than the viewport and starts scrolling behind the bar.
    val gridBottomPadding = if (organizing) with(density) { toolbarHeight.toDp() } + 16.dp else 24.dp
    // LazyGridItemScope has no fillParentMaxHeight; derive it from the shelf area minus vertical padding.
    // Reading the area's height instead of the grid's viewport keeps it stable: a kind switch swaps the
    // grid state, whose viewport reads as zero for the first frame and made the empty page jump.
    val emptyAreaMinHeight = with(density) {
        (contentHeight - 12.dp.roundToPx() - gridBottomPadding.roundToPx()).coerceAtLeast(0).toDp()
    }

    fun reorderAt(position: Offset) {
        val current = drag ?: return
        if (current.scope != gestureScope || !reorderAllowed) {
            cancelDrag()
            return
        }
        // Lazy-grid item offsets exclude content padding; pointer coordinates do not.
        val contentPosition = position - Offset(gridSidePadding, gridState.layoutInfo.beforeContentPadding.toFloat())
        val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val key = item.key as? String
            key?.startsWith("book:") == true &&
                contentPosition.x >= item.offset.x && contentPosition.x <= item.offset.x + item.size.width &&
                contentPosition.y >= item.offset.y && contentPosition.y <= item.offset.y + item.size.height
        }?.key?.toString()?.removePrefix("book:") ?: return
        if (target == current.book.id) {
            // The dragged cover now owns this cell, so the next hover may swap back.
            lastDragTarget = null
            return
        }
        val now = SystemClock.uptimeMillis()
        // A cell can still report the previous item for one layout pass after a swap. Only let the
        // same cell move again once the shelf has settled, which keeps quick back-and-forth smooth.
        if (target == lastDragTarget && now - lastDragMoveAt < DRAG_REPEAT_MS) return
        if (current.order.move(current.book.id, target)) {
            lastDragTarget = target
            lastDragMoveAt = now
        }
    }

    // Root grid only carries groups; shelf grids carry books, so the two drags never overlap.
    fun groupReorderAt(position: Offset) {
        val current = groupDrag ?: return
        if (scopeId != null) { cancelGroupDrag(); return }
        val contentPosition = position - Offset(gridSidePadding, gridState.layoutInfo.beforeContentPadding.toFloat())
        val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val key = item.key.toString()
            (key.startsWith("group:") || key == "ungrouped") &&
                contentPosition.x >= item.offset.x && contentPosition.x <= item.offset.x + item.size.width &&
                contentPosition.y >= item.offset.y && contentPosition.y <= item.offset.y + item.size.height
        }?.key?.toString()?.removePrefix("group:") ?: return
        if (target == current.group.id) {
            lastGroupTarget = null
            return
        }
        val now = SystemClock.uptimeMillis()
        if (target == lastGroupTarget && now - lastGroupMoveAt < DRAG_REPEAT_MS) return
        val moved = if (target == "ungrouped") {
            current.order.moveToEnd(current.group.id)
        } else if (target in groupIds) {
            current.order.move(current.group.id, target)
        } else {
            false
        }
        if (moved) {
            lastGroupTarget = target
            lastGroupMoveAt = now
        }
    }

    fun accessibleReorder(id: String, step: Int): Boolean {
        if (!organizing || !reorderAllowed || drag != null) return false
        val index = visibleIds.indexOf(id)
        val target = visibleIds.getOrNull(index + step) ?: return false
        val result = moveBookInOrder(visibleIds, id, target)
        if (!order.stage(result, shelfIds)) return false
        orderScope = scopeKey
        persistOrder(result)
        return true
    }

    // Grid ownership and updated callbacks survive lazy-item removal and preview changes.
    val canDrag by rememberUpdatedState(
        if (scopeId == null) query.isBlank() && !groupOrder.busy else reorderAllowed,
    )
    val startDrag by rememberUpdatedState<(Offset) -> Boolean>({ position ->
        val contentPadding = Offset(gridSidePadding, gridState.layoutInfo.beforeContentPadding.toFloat())
        val contentPosition = position - contentPadding
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key.toString().startsWith("book:") &&
                contentPosition.x >= item.offset.x && contentPosition.x <= item.offset.x + item.size.width &&
                contentPosition.y >= item.offset.y && contentPosition.y <= item.offset.y + item.size.height
        }
        val book = item?.key?.toString()?.removePrefix("book:")?.let(byId::get)
        if (item == null || book == null || !reorderAllowed || !order.begin(shelfIds)) false else {
            orderScope = scopeKey
            tapBlockedUntil = Long.MAX_VALUE
            lastDragTarget = null
            lastDragMoveAt = 0L
            dragPointer = position
            drag = CoverDrag(book, contentPosition - Offset(item.offset.x.toFloat(), item.offset.y.toFloat()), item.size.width, gestureScope, order)
            focusManager.clearFocus()
            keyboard?.hide()
            true
        }
    })
    val startGroupDrag by rememberUpdatedState<(Offset) -> Boolean>({ position ->
        val contentPadding = Offset(gridSidePadding, gridState.layoutInfo.beforeContentPadding.toFloat())
        val contentPosition = position - contentPadding
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key.toString().startsWith("group:") &&
                contentPosition.x >= item.offset.x && contentPosition.x <= item.offset.x + item.size.width &&
                contentPosition.y >= item.offset.y && contentPosition.y <= item.offset.y + item.size.height
        }
        val group = item?.key?.toString()?.removePrefix("group:")?.let { id -> scopedGroups.firstOrNull { it.id == id } }
        if (item == null || group == null || !groupOrder.begin(groupIds)) false else {
            tapBlockedUntil = Long.MAX_VALUE
            lastGroupTarget = null
            lastGroupMoveAt = 0L
            groupPointer = position
            groupDrag = GroupDrag(group,
                contentPosition - Offset(item.offset.x.toFloat(), item.offset.y.toFloat()), item.size.width, item.size.height, groupOrder)
            focusManager.clearFocus()
            keyboard?.hide()
            true
        }
    })
    val updateDrag by rememberUpdatedState<(Offset) -> Unit>({ position ->
        if (scopeId == null) {
            if (groupDrag != null) {
                groupPointer = position
                groupReorderAt(position)
            }
        } else if (drag != null) {
            dragPointer = position
            reorderAt(position)
        }
    })
    val finishDrag by rememberUpdatedState<() -> Unit>({
        if (scopeId == null) {
            val current = groupDrag
            if (current != null) {
                val result = current.order.complete(groupIds)
                groupDrag = null
                lastGroupTarget = null
                tapBlockedUntil = SystemClock.uptimeMillis() + TAP_BLOCK_MS
                result?.let(::persistGroupOrder)
            } else cancelGroupDrag()
        } else {
            val current = drag
            if (current != null && current.scope == gestureScope && reorderAllowed) {
                val result = current.order.complete(shelfIds)
                drag = null
                lastDragTarget = null
                tapBlockedUntil = SystemClock.uptimeMillis() + TAP_BLOCK_MS
                // Long press only ever reorders; selecting stays a tap inside organize mode.
                result?.let(::persistOrder)
            } else cancelDrag()
        }
    })
    val abortDrag by rememberUpdatedState<() -> Unit>({
        if (scopeId == null) cancelGroupDrag() else cancelDrag()
    })
    val updateDragTarget by rememberUpdatedState<() -> Unit>({
        if (scopeId == null) {
            if (groupDrag != null) groupReorderAt(groupPointer)
        } else if (drag != null) {
            reorderAt(dragPointer)
        }
    })

    LaunchedEffect(latestSource) { order.reconcile(latestSource) }
    LaunchedEffect(latestGroupSource) { groupOrder.reconcile(latestGroupSource) }
    // Warm both shelves' covers while the library sits still: the first switch otherwise stalls for
    // ~100 ms decoding the other shelf's JPEGs on the way in.
    LaunchedEffect(books, coverDir) {
        val pending = books.mapNotNull { book ->
            val path = coverPath(coverDir, book)
            val key = coverKey(path, book.importedAt)
            if (coverCache.get(key) != null) null else key to path
        }
        if (pending.isEmpty()) return@LaunchedEffect
        delay(500)
        withContext(Dispatchers.IO) {
            pending.forEach { (key, path) ->
                if (coverCache.get(key) == null) decodeCover(path)?.let { coverCache.put(key, it) }
            }
        }
    }
    LaunchedEffect(scopeKey) {
        if (firstSlide) {
            firstSlide = false
            pageSlide.snapTo(0f)
            return@LaunchedEffect
        }
        if (skipScopeSlide) {
            skipScopeSlide = false
            pageSlide.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduceMotion || pageWidth == 0) {
            pageSlide.snapTo(0f)
            return@LaunchedEffect
        }
        val shift = pageWidth * 0.22f
        pageSlide.snapTo(if (forward) shift else -shift)
        pageSlide.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(kind) {
        // The first composition is a launch, not a switch: nothing to reset and nothing to move.
        if (firstKind) {
            firstKind = false
            return@LaunchedEffect
        }
        leaveOrganize()
        closeSearch()
        selection = emptySet()
        if (scopeId != null) {
            skipScopeSlide = true
            scopeId = null
        }
    }
    LaunchedEffect(kind) {
        val target = if (kind == KIND_EBOOK) 1 else 0
        if (kindPagerState.currentPage != target) kindPagerState.animateScrollToPage(target)
    }
    LaunchedEffect(kindPagerState.settledPage) {
        val pageKind = if (kindPagerState.settledPage == 0) KIND_COMIC else KIND_EBOOK
        if (pageKind != kind) onKind(pageKind)
    }
    LaunchedEffect(scopeId) {
        if (scopeId != null) shelfGridState.scrollToItem(0)
    }
    LaunchedEffect(gestureScope, organizing, shelfIds) {
        if (drag?.let { it.scope != gestureScope || !organizing || it.book.id !in shelfIds } == true) cancelDrag()
        selection = selection.intersect(shelfIds.toSet())
    }
    LaunchedEffect(scopedGroups, scopeId) {
        if (scopeId != null && scopeId != ALL_BOOKS && scopeId != UNGROUPED &&
            scopedGroups.none { it.id == scopeId }) {
            navigate(null)
        }
    }
    LaunchedEffect(drag?.book?.id, groupDrag?.group?.id) {
        var previousFrame = withFrameNanos { it }
        while (drag != null || groupDrag != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.032f)
            previousFrame = frame
            val pointer = when {
                drag != null -> dragPointer
                groupDrag != null -> groupPointer
                else -> break
            }
            val top = 0f
            val bottom = (gridState.layoutInfo.viewportSize.height - toolbarHeight).toFloat().coerceAtLeast(edgeSize * 2)
            val speed = when {
                pointer.y < top + edgeSize -> -((top + edgeSize - pointer.y) / edgeSize).coerceIn(0f, 1f)
                pointer.y > bottom - edgeSize -> ((pointer.y - bottom + edgeSize) / edgeSize).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) gridState.scrollBy(speed * edgeSize * 7f * seconds)
            updateDragTarget()
        }
    }

    val modalOpen = deleteIds != null || moving || groupActionsId != null || deleteGroupId != null || nameEditorId != null
    BackHandler(enabled = !modalOpen && (organizing || searchVisible || scopeId != null)) {
        when {
            organizing -> leaveOrganize()
            searchVisible -> closeSearch()
            else -> navigate(null)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).onSizeChanged { pageWidth = it.width }) {
            Column(Modifier.fillMaxSize().graphicsLayer { translationX = pageSlide.value }) {
                if (scopeId == null) {
                    RootHeader(
                        searchVisible = searchVisible,
                        query = query,
                        importing = importing,
                        requestFocus = requestSearchFocus,
                        onQuery = { query = it },
                        onSearchToggle = {
                            if (searchVisible) closeSearch() else {
                                searchVisible = true
                                requestSearchFocus = true
                            }
                        },
                        onFocusHandled = { requestSearchFocus = false },
                        onImport = openImport,
                        onSettings = onSettings,
                    )
                    LibraryKindSwitch(
                        pagerState = kindPagerState,
                        comicCount = comicCount,
                        ebookCount = ebookCount,
                        onSelect = { if (it != kind) onKind(it) },
                    )
                } else {
                    ShelfHeader(
                        title = title,
                        count = shelfBooks.size,
                        organizing = organizing,
                        orderBusy = order.busy,
                        hasGroup = activeGroup != null,
                        onBack = { navigate(null) },
                        onActions = { groupActionsId = activeGroup?.id },
                        onToggleOrganize = {
                            if (organizing) leaveOrganize() else {
                                organizing = true
                                focusManager.clearFocus()
                                keyboard?.hide()
                            }
                        },
                    )
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .onSizeChanged { contentHeight = it.height },
                ) {
                    HorizontalPager(
                        state = kindPagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = scopeId == null && !organizing && drag == null && groupDrag == null,
                        // Compose the neighbouring shelf ahead: the page being dragged in already
                        // has its covers, so the first frame of the swipe is not an empty grey.
                        // Inside a group there is only one shelf page, so the extra one is not kept.
                        beyondViewportPageCount = if (scopeId == null) 1 else 0,
                    ) { page ->
                        val pageKind = if (page == 0) KIND_COMIC else KIND_EBOOK
                        // Each page filters its own shelf; the neighbouring kind is composed ahead
                        // so the incoming page has real tiles to slide in, not an empty frame.
                        val pageBooks = remember(books, pageKind) { books.filter { it.kind == pageKind } }
                        val pageGroups = remember(groups, pageKind) { groups.filter { it.kind == pageKind } }
                        val pageSortedGroups = remember(pageGroups) {
                            pageGroups.sortedWith(compareBy<GroupEntity> { it.sortOrder }.thenBy { it.id })
                        }
                        val pageGroupIds = remember(pageSortedGroups) { pageSortedGroups.map { it.id } }
                        val pageOrderedGroupIds = groupOrder.visible(pageGroupIds)
                        val pageOrderedGroups = remember(pageOrderedGroupIds, pageSortedGroups) {
                            pageOrderedGroupIds.mapNotNull { id -> pageSortedGroups.firstOrNull { it.id == id } }
                        }
                        val pageSortedBooks = remember(pageBooks) {
                            pageBooks.sortedWith(compareBy<BookEntity> { it.sortOrder }.thenBy { it.importedAt }.thenBy { it.id })
                        }
                        val pageUngroupedBooks = remember(pageSortedBooks) {
                            pageSortedBooks.filter { it.groupId == null }
                        }
                        val pagePreviewsByGroup = remember(pageSortedBooks) {
                            pageSortedBooks.filter { it.groupId != null }.groupBy { it.groupId!! }
                        }
                        val pageContinuing = remember(pageBooks) {
                            pageBooks.sortedByDescending { it.updatedAt }
                                .firstOrNull { it.hasStarted() && !it.isFinished() }
                        }
                        val pageSearchResults = remember(pageSortedBooks, query) {
                            val term = query.trim()
                            if (term.isEmpty()) emptyList() else pageSortedBooks.filter {
                                it.title.contains(term, ignoreCase = true)
                            }
                        }
                        val pageGridState = when {
                            scopeId != null -> shelfGridState
                            pageKind == KIND_EBOOK -> ebookRootGridState
                            else -> comicRootGridState
                        }
                        val offscreen = page != kindPagerState.currentPage
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2), state = pageGridState,
                        modifier = Modifier.fillMaxSize()
                            // The pre-composed neighbouring page must stay out of the accessibility
                            // tree and tests; only the page under the finger speaks.
                            .then(if (offscreen) Modifier.clearAndSetSemantics {} else Modifier)
                            .testTag(if (offscreen) "library-grid-offscreen" else "library-grid")
                            .pointerInput(scopeId) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!canDrag) return@awaitEachGesture
                                val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                val started = if (scopeId == null) startGroupDrag(hold.position) else startDrag(hold.position)
                                if (!started) return@awaitEachGesture
                                hold.consume()
                                var finished = false
                                try {
                                    val completed = dragPointer(hold.id) { change ->
                                        updateDrag(change.position)
                                        change.consume()
                                    }
                                    if (completed) {
                                        currentEvent.changes.forEach { it.consume() }
                                        finishDrag()
                                        finished = true
                                    }
                                } finally {
                                    if (!finished) abortDrag()
                                }
                            }
                        },
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp,
                            bottom = gridBottomPadding),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (importing) item(key = "importing", span = { GridItemSpan(maxLineSpan) }) { ImportStatus() }
                        if (scopeId == null) {
                            if (query.isNotBlank()) {
                                if (pageSearchResults.isEmpty()) {
                                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                        LibraryEmptyState(false, true, importing, openImport, { query = "" }, false,
                                            kind = pageKind)
                                    }
                                } else {
                                    item(key = "search-label", span = { GridItemSpan(maxLineSpan) }) {
                                        Text("搜索结果 · ${pageSearchResults.size} 本", Modifier.padding(start = 4.dp),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    items(pageSearchResults, key = { "book:${it.id}" }, contentType = { "book" }) { book ->
                                        BookTile(book, false, false, book.id in unavailable, kind = pageKind,
                                            onClick = { onOpen(book) })
                                    }
                                }
                            } else if (pageBooks.isEmpty() && pageGroups.isEmpty()) {
                                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                    LibraryEmptyState(true, false, importing, openImport, {}, false,
                                        Modifier.heightIn(min = emptyAreaMinHeight), kind = pageKind)
                                }
                            } else {
                                pageContinuing?.let { book ->
                                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                                        ContinueReadingCard(book, pageKind, { onOpen(book) })
                                    }
                                }
                                item(key = "all-books", span = { GridItemSpan(maxLineSpan) }) {
                                    AllBooksCard(pageBooks, pageKind, { navigate(ALL_BOOKS) })
                                }
                                item(key = "groups-header", span = { GridItemSpan(maxLineSpan) }) { GroupSectionHeader() }
                                items(pageOrderedGroups, key = { "group:${it.id}" }, contentType = { "group" }) { group ->
                                    ShelfCard(
                                        name = group.name,
                                        count = pageBooks.count { it.groupId == group.id },
                                        previews = pagePreviewsByGroup[group.id].orEmpty().take(3),
                                        onClick = { if (tapAllowed()) navigate(group.id) },
                                        modifier = Modifier.testTag("group:${group.id}")
                                            .animateItem(placementSpec = if (reduceMotion || groupDrag != null) null else tween(180))
                                            .graphicsLayer { alpha = if (groupDrag?.group?.id == group.id) 0.18f else 1f },
                                    )
                                }
                                if (pageUngroupedBooks.isNotEmpty()) {
                                    item(key = "ungrouped", contentType = "group") {
                                        ShelfCard(
                                            name = "未分组",
                                            count = pageUngroupedBooks.size,
                                            previews = pageUngroupedBooks.take(3),
                                            onClick = { if (tapAllowed()) navigate(UNGROUPED) },
                                            modifier = Modifier.testTag("group:ungrouped")
                                                .animateItem(placementSpec = if (reduceMotion || groupDrag != null) null else tween(180)),
                                        )
                                    }
                                }
                                item(key = "new-group", contentType = "group") {
                                    NewGroupTile { nameDraft = ""; nameEditorId = "" }
                                }
                            }
                        } else if (visibleBooks.isEmpty()) {
                            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                LibraryEmptyState(false, false, importing, openImport, { navigate(null) }, true,
                                    Modifier.heightIn(min = emptyAreaMinHeight), kind = pageKind)
                            }
                        } else {
                            items(visibleBooks, key = { "book:${it.id}" }, contentType = { "book" }) { book ->
                                BookTile(
                                    book, organizing, book.id in selection, book.id in unavailable, kind = pageKind,
                                    onClick = {
                                        if (!tapAllowed()) return@BookTile
                                        if (organizing) selectBook(book.id) else onOpen(book)
                                    },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = if (reduceMotion) null else tween(180),
                                        // Let covers step under the finger while dragging; placement
                                        // animations re-introduce the lag the gesture is fighting.
                                        placementSpec = if (reduceMotion || drag != null) null else tween(180),
                                        fadeOutSpec = if (reduceMotion) null else tween(120),
                                    ).graphicsLayer { alpha = if (drag?.book?.id == book.id) 0.18f else 1f }
                                        .semantics {
                                            if (organizing && reorderAllowed) customActions = buildList {
                                                if (visibleIds.firstOrNull() != book.id) add(CustomAccessibilityAction("向前移动") { accessibleReorder(book.id, -1) })
                                                if (visibleIds.lastOrNull() != book.id) add(CustomAccessibilityAction("向后移动") { accessibleReorder(book.id, 1) })
                                            }
                                        },
                                )
                            }
                        }
                    }
                    }
                    drag?.let { lifted ->
                        val liftScale by animateFloatAsState(if (reduceMotion) 1f else 1.03f, label = "lifted cover")
                        PdfCover(lifted.book, Modifier.width(with(density) { lifted.width.toDp() }).aspectRatio(0.72f)
                            .graphicsLayer {
                                translationX = dragPointer.x - lifted.grab.x
                                translationY = dragPointer.y - lifted.grab.y
                                scaleX = liftScale
                                scaleY = liftScale
                                shadowElevation = if (reduceMotion) 0f else 12.dp.toPx()
                                shape = RoundedCornerShape(10.dp)
                            }.clearAndSetSemantics {})
                    }
                    groupDrag?.let { lifted ->
                        val liftScale by animateFloatAsState(if (reduceMotion) 1f else 1.03f, label = "lifted group")
                        ShelfCard(
                            name = lifted.group.name,
                            count = scopedBooks.count { it.groupId == lifted.group.id },
                            previews = previewsByGroup[lifted.group.id].orEmpty().take(3),
                            onClick = {},
                            modifier = Modifier.width(with(density) { lifted.width.toDp() })
                                .graphicsLayer {
                                    translationX = groupPointer.x - lifted.grab.x
                                    translationY = groupPointer.y - lifted.grab.y
                                    scaleX = liftScale
                                    scaleY = liftScale
                                    shadowElevation = if (reduceMotion) 0f else 14.dp.toPx()
                                    shape = RoundedCornerShape(18.dp)
                                }.clearAndSetSemantics {},
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = organizing, modifier = Modifier.align(Alignment.BottomCenter),
                        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 3 },
                    ) {
                        OrganizeBar(
                            selected = selection.size,
                            allSelected = shelfIds.isNotEmpty() && selection.containsAll(shelfIds),
                            canSelect = shelfIds.isNotEmpty(),
                            canAct = selection.isNotEmpty(),
                            onToggleAll = {
                                selection = if (shelfIds.isNotEmpty() && selection.containsAll(shelfIds)) emptySet() else shelfIds.toSet()
                            },
                            onMove = { moving = true },
                            onRemove = { deleteIds = shelfIds.filter { it in selection } },
                            onHeight = { toolbarHeight = it },
                        )
                    }
                }
            }
        }
    }

    deleteIds?.let { ids ->
        PaperConfirmation(
            "从书架移除 ${ids.size} 本书？",
            "将移除这 ${ids.size} 本书的封面与阅读记录，不会删除原始文件。",
            "移出书架", { deleteIds = null }, {
                deleteIds = null
                selection = selection - ids.toSet()
                onDeleteBooks(ids)
            }, dismissLabel = "保留", destructive = true, confirmOnLeft = true,
        )
    }
    if (moving) {
        val ids = shelfIds.filter { it in selection }
        PaperModal("移动 ${ids.size} 本书到", { moving = false }, actions = {
            PaperAction("取消", { moving = false }, Modifier.fillMaxWidth())
        }) {
            PaperAction("未分组", { moving = false; selection = emptySet(); onMoveBooks(ids, null) }, Modifier.fillMaxWidth())
            sortedGroups.forEach { group ->
                PaperAction(group.name, { moving = false; selection = emptySet(); onMoveBooks(ids, group.id) }, Modifier.fillMaxWidth())
            }
        }
    }
    scopedGroups.firstOrNull { it.id == groupActionsId }?.let { group ->
        PaperModal(group.name, { groupActionsId = null }, actions = {
            PaperAction("取消", { groupActionsId = null }, Modifier.fillMaxWidth())
        }) {
            PaperAction("重命名分组", { groupActionsId = null; nameDraft = group.name; nameEditorId = group.id }, Modifier.fillMaxWidth())
            PaperAction("解散分组", { groupActionsId = null; deleteGroupId = group.id }, Modifier.fillMaxWidth())
        }
    }
    scopedGroups.firstOrNull { it.id == deleteGroupId }?.let { group ->
        PaperConfirmation(
            "解散「${group.name}」？", "分组内的 ${scopedBooks.count { it.groupId == group.id }} 本书将回到未分组，书籍与阅读记录会保留。",
            "解散分组", { deleteGroupId = null }, {
                deleteGroupId = null
                if (scopeId == group.id) navigate(null)
                onDeleteGroup(group.id)
            },
        )
    }
    nameEditorId?.let { id ->
        PaperModal(if (id.isEmpty()) "新建分组" else "重命名分组", { nameEditorId = null }, actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperAction("取消", { nameEditorId = null }, Modifier.weight(1f))
                PaperAction("保存", {
                    if (validGroupName(nameDraft)) {
                        val name = nameDraft.trim()
                        nameEditorId = null
                        if (id.isEmpty()) onCreateGroup(name, kind) else onRenameGroup(id, name)
                    }
                }, Modifier.weight(1f), enabled = validGroupName(nameDraft), primary = true)
            }
        }) {
            PaperTextField(nameDraft, { nameDraft = it }, "分组名称")
            if (nameDraft.trim().length > 80) Text("名称最多 80 个字符", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RootHeader(
    searchVisible: Boolean,
    query: String,
    importing: Boolean,
    requestFocus: Boolean,
    onQuery: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onFocusHandled: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The title row and the search row share one fixed height, so opening search never shifts the shelf.
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchVisible) {
            PaperIconAction(Icons.AutoMirrored.Outlined.ArrowBack, "关闭搜索", onSearchToggle)
            PaperTextField(query, onQuery, "搜索书名", Modifier.weight(1f).focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                trailing = { if (query.isNotEmpty()) PaperIconAction(Icons.Outlined.Close, "清空搜索", { onQuery("") }) },
            )
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("墨屿", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif, fontSize = 28.sp))
                Text("方寸之间，自有山海。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            PaperIconAction(Icons.Outlined.Search, "搜索书籍", onSearchToggle)
            PaperIconAction(Icons.Outlined.Add, "导入书籍", onImport, enabled = !importing)
            PaperIconAction(Icons.Outlined.Settings, "设置", onSettings)
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            onFocusHandled()
        }
    }
}

/** Two shelves in one library: comics and ebooks keep their own content, not their own app. */
@Composable
private fun LibraryKindSwitch(
    pagerState: PagerState,
    comicCount: Int,
    ebookCount: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val gap = 3.dp
    // Read the pager here, not in LibraryScreen: the highlight can follow every frame without
    // recomposing both whole shelves.
    val progress = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
    Surface(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).padding(gap)) {
            // The highlight is driven by the pager's own progress, not by the settled kind:
            // it therefore travels with the finger instead of waiting for the page to stop.
            val segment = ((maxWidth - gap) / 2).coerceAtLeast(1.dp)
            val offset = (segment + gap) * progress.coerceIn(0f, 1f)
            Box(
                Modifier.offset(x = offset).width(segment).fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp)).background(colors.primaryContainer),
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                KindTab("漫画", comicCount, progress < 0.5f,
                    Modifier.weight(1f).testTag("library-kind:comic")) { onSelect(KIND_COMIC) }
                KindTab("电子书", ebookCount, progress >= 0.5f,
                    Modifier.weight(1f).testTag("library-kind:ebook")) { onSelect(KIND_EBOOK) }
            }
        }
    }
}

@Composable
private fun KindTab(
    label: String,
    count: Int,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val foreground by animateColorAsState(
        if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(120),
        label = "shelf kind text",
    )
    Row(
        modifier.fillMaxHeight().clip(RoundedCornerShape(11.dp))
            .clickable(role = Role.Tab, interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClick)
            .semantics { selected = isSelected; contentDescription = "$label，$count 本" }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = foreground, maxLines = 1)
        if (count > 0) {
            Text(" $count", style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = 0.72f), maxLines = 1)
        }
    }
}

@Composable
private fun ShelfHeader(
    title: String,
    count: Int,
    organizing: Boolean,
    orderBusy: Boolean,
    hasGroup: Boolean,
    onBack: () -> Unit,
    onActions: () -> Unit,
    onToggleOrganize: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperIconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回书架", onBack)
            // The count sits beside the name instead of becoming a second line of the header.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif, fontSize = 24.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("$count 本", Modifier.padding(start = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasGroup && !organizing) PaperIconAction(Icons.Outlined.MoreHoriz, "分组操作", onActions)
            HeaderAction(if (organizing) "完成" else "整理", onToggleOrganize,
                Modifier.padding(start = 2.dp), enabled = organizing || count > 0, filled = organizing)
        }
        if (organizing && orderBusy) Text(
            "正在保存排序…",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 14.dp, bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        PaperDivider(Modifier.padding(top = 2.dp))
    }
}

/** A lighter header action than the 48dp outline pills used inside sheets. */
@Composable
private fun HeaderAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (filled && enabled) colors.primary else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                filled -> colors.onPrimary
                else -> colors.primary
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun ContinueReadingCard(book: BookEntity, kind: Int, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
        Row(
            Modifier.testTag("continue-reading").fillMaxWidth().clickable(
                role = Role.Button, onClickLabel = "继续阅读${book.title}", onClick = onOpen,
            ).padding(16.dp).semantics { contentDescription = "正在阅读 ${book.title}，第 ${book.labeledPage} 页，共 ${book.labeledPageCount} 页" },
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PdfCover(book, Modifier.width(52.dp).aspectRatio(0.72f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("正在阅读", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f))
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimary)
                Text(
                    when {
                        book.isTextLayout() ->
                            "电子书 · ${book.formatLabel()} · ${(book.progress() * 100).roundToInt()}%"
                        kind == KIND_EBOOK -> "电子书 · 第 ${book.labeledPage} 页 / 共 ${book.labeledPageCount} 页"
                        else -> "第 ${book.labeledPage} 页 / 共 ${book.labeledPageCount} 页"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
                LinearProgressIndicator(
                    progress = { book.progress() },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.onPrimary,
                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f),
                    gapSize = 0.dp, drawStopIndicator = {},
                )
            }
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "继续阅读", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** Same geometry as [ContinueReadingCard], so the two entries read as one pair. */
@Composable
private fun AllBooksCard(books: List<BookEntity>, kind: Int, onClick: () -> Unit) {
    val ebook = kind == KIND_EBOOK
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
        Row(
            Modifier.testTag("all-books").fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (ebook) "打开全部电子书" else "打开全部漫画", onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverStack(books, Modifier.width(52.dp).aspectRatio(0.72f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (ebook) "电子书书架" else "我的书架", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (ebook) "全部电子书" else "全部漫画", style = MaterialTheme.typography.titleMedium)
                Text("${books.size} 本 · 可按分组整理", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Mirrors the reading progress bar so both entries share the same height.
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)))
            }
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Up to three covers fanned inside the footprint of a single cover. */
@Composable
private fun CoverStack(books: List<BookEntity>, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = books.take(3)
        if (shown.isEmpty()) {
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            shown.forEachIndexed { index, book ->
                val shift = 7.dp * (index - (shown.size - 1) / 2f)
                PdfCover(book, Modifier.fillMaxHeight(0.9f).aspectRatio(0.72f).graphicsLayer {
                    translationX = shift.toPx()
                    rotationZ = (index - (shown.size - 1) / 2f) * 6f
                })
            }
        }
    }
}

@Composable
private fun GroupSectionHeader() {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("分组", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShelfCard(
    name: String,
    count: Int,
    previews: List<BookEntity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
        // A long press belongs to the grid, which lifts the card and reorders groups.
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开$name", onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.5f), contentAlignment = Alignment.Center) {
                val shown = previews.take(3)
                if (shown.isEmpty()) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                } else {
                    shown.indices.reversed().forEach { index ->
                        val shift = index - (shown.size - 1) / 2f
                        PdfCover(shown[index], Modifier.fillMaxHeight(if (index == 0) 0.94f else 0.86f).aspectRatio(0.72f)
                            .graphicsLayer {
                                translationX = shift * 10.dp.toPx()
                                translationY = if (index == 0) 0f else 3.dp.toPx()
                                rotationZ = shift * 5f
                            })
                    }
                }
                // The book count sits on the covers so the caption row stays a single line.
                Surface(Modifier.align(Alignment.TopEnd), shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))) {
                    Text("$count 本", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NewGroupTile(onClick: () -> Unit) {
    Surface(
        Modifier.testTag("new-group"), shape = RoundedCornerShape(18.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
    ) {
        // Same padding, cover ratio and caption line as ShelfCard, so the two cells match in height.
        Column(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "新建分组" }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.5f), contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text("新建分组", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun OrganizeBar(
    selected: Int,
    allSelected: Boolean,
    canSelect: Boolean,
    canAct: Boolean,
    onToggleAll: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    onHeight: (Int) -> Unit,
) {
    Surface(
        Modifier.testTag("organize-bar").fillMaxWidth().onSizeChanged { onHeight(it.height) }.padding(12.dp),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        shadowElevation = 10.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append("已选 ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                        append("$selected")
                    }
                    append(" 本")
                },
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            BarAction(if (allSelected) "取消全选" else "全选", onToggleAll, enabled = canSelect)
            BarAction("移动到…", onMove, enabled = canAct, primary = true)
            BarAction("移除", onRemove, enabled = canAct, danger = true)
        }
    }
}

@Composable
private fun BarAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> colors.error
        primary -> colors.onPrimary
        else -> colors.primary
    }
    Box(
        modifier = modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(9.dp))
            .background(if (primary && enabled) colors.primary else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (primary) 14.dp else 9.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = foreground, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: BookEntity,
    organizing: Boolean,
    isSelected: Boolean,
    isUnavailable: Boolean,
    kind: Int = KIND_COMIC,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalMoyuReduceMotion.current
    val selectionScale by animateFloatAsState(
        if (organizing && isSelected) 0.96f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(160), label = "selected book",
    )
    Column(
        // Long press belongs to the grid recognizer, which lifts the cover and reorders it directly.
        modifier.fillMaxWidth().testTag("book:${book.id}").clip(RoundedCornerShape(12.dp)).clickable(
            role = if (organizing) Role.Checkbox else Role.Button,
            onClickLabel = if (organizing) "选择${book.title}" else "打开${book.title}",
            onClick = onClick,
        ).semantics { if (organizing) selected = isSelected }.padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            PdfCover(book, Modifier.fillMaxWidth().aspectRatio(0.72f).graphicsLayer {
                scaleX = selectionScale
                scaleY = selectionScale
            })
            if (organizing) {
                Surface(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp), shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                ) {
                    if (isSelected) Icon(Icons.Outlined.Check, null, Modifier.padding(4.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else if (isUnavailable) {
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("unavailable:${book.id}"),
                    shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text("原文件失效", Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else if (book.isFinished()) {
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)) {
                    Text("已读完", Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Text(
            book.title,
            style = if (kind == KIND_EBOOK) MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif)
            else MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (kind == KIND_EBOOK) {
            val format = when (book.format) {
                FORMAT_ARCHIVE -> "CBZ"
                FORMAT_FOLDER -> "文件夹"
                FORMAT_TEXT -> "TXT"
                FORMAT_EPUB -> "EPUB"
                else -> "PDF"
            }
            Text(
                when {
                    // Flowing text has no meaningful page count: percent is the stable progress.
                    book.isTextLayout() ->
                        "电子书 · $format · ${(book.progress() * 100).roundToInt()}%"
                    book.hasStarted() ->
                        "电子书 · $format · ${(book.progress() * 100).roundToInt()}% · ${book.labeledPageCount} 页"
                    else -> "电子书 · $format · 共 ${book.labeledPageCount} 页"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { book.progress() },
                modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                gapSize = 0.dp, drawStopIndicator = {},
            )
        } else {
            Text(
                if (book.hasStarted()) "${book.labeledPageCount} 页 · ${(book.progress() * 100).roundToInt()}%"
                else "${book.labeledPageCount} 页 · 未开始",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportStatus() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("正在导入，请稍候…", style = MaterialTheme.typography.bodyMedium)
            if (!LocalMoyuReduceMotion.current) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

/**
 * Decoded covers outlive a shelf. Switching kinds drops the whole grid, and without a cache the same
 * JPEGs were decoded and uploaded again on every switch — the dropped frames at the start of the slide.
 */
private val coverCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(4 * 1024),
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

/** Drops decoded covers when the process is hidden or the system is running low on memory. */
internal fun clearCoverCache() {
    coverCache.evictAll()
}

/** The cover file's timestamp is part of the key, so re-importing a book refreshes its artwork. */
private fun coverKey(path: String, importedAt: Long): String {
    val modified = runCatching { File(path).lastModified() }.getOrDefault(0L)
    return "$path@$importedAt@$modified"
}

private fun coverPath(dir: File, book: BookEntity): String =
    File(File(dir, "covers"), "${book.id}.jpg").absolutePath

private fun decodeCover(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    // Covers are drawn at most ~150dp wide; 640px is still sharp there and a third of the texture
    // upload a 1000px bitmap costs on this class of device.
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 640) sampleSize *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}.getOrNull()

@Composable
private fun PdfCover(book: BookEntity, modifier: Modifier = Modifier) {
    val filesDir = LocalContext.current.filesDir
    val path = remember(filesDir, book.id) { coverPath(filesDir, book) }
    // Recompute the key on composition: a re-import rewrites the file and must invalidate its decode.
    val cacheKey = coverKey(path, book.importedAt)
    var bitmap by remember(cacheKey) { mutableStateOf(coverCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { decodeCover(path) }
        if (decoded != null) coverCache.put(cacheKey, decoded)
        bitmap = decoded
    }
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
        val cover = bitmap
        if (cover != null) {
            Image(cover.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    when (book.format) {
                        FORMAT_FOLDER -> "文件夹"
                        FORMAT_ARCHIVE -> "ZIP"
                        FORMAT_TEXT -> "TXT"
                        FORMAT_EPUB -> "EPUB"
                        else -> "PDF"
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    emptyLibrary: Boolean,
    searching: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onReset: () -> Unit,
    scoped: Boolean,
    modifier: Modifier = Modifier,
    kind: Int = KIND_COMIC,
) {
    val ebook = kind == KIND_EBOOK
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        when {
            // Each shelf keeps its own invitation until that shelf itself has books.
            emptyLibrary -> Image(painterResource(R.drawable.ic_moyu), null, Modifier.size(64.dp))
            else -> Icon(if (searching) Icons.Outlined.Search else Icons.AutoMirrored.Outlined.MenuBook,
                null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        }
        Text(when {
            emptyLibrary -> if (ebook) "让第一本电子书，住进墨屿" else "让第一本漫画书，住进墨屿"
            searching -> "没有找到匹配的书"
            scoped -> "一角山海，尚未入墨。"
            else -> "书架暂时空着"
        }, style = MaterialTheme.typography.titleMedium)
        when {
            emptyLibrary -> PaperAction(
                if (importing) "正在导入…" else if (ebook) "导入电子书" else "导入漫画",
                onImport, enabled = !importing, primary = true,
            )
            searching -> PaperAction("清除搜索", onReset)
            scoped -> PaperAction("返回书架", onReset)
        }
    }
}

// The shelf labels a book with the layout it was last read in: PDF pages, or its flowing text pages.
private fun BookEntity.isFinished(): Boolean =
    if (isTextLayout()) textProgress >= 100
    else hasStarted() && labeledPageCount > 0 && labeledPage == labeledPageCount

private fun BookEntity.isTextLayout(): Boolean =
    format == FORMAT_TEXT || format == FORMAT_EPUB ||
        (format == FORMAT_PDF && hasTextView && readView == READ_VIEW_TEXT)

private fun BookEntity.formatLabel(): String = when (format) {
    FORMAT_ARCHIVE -> "CBZ"
    FORMAT_FOLDER -> "文件夹"
    FORMAT_TEXT -> "TXT"
    FORMAT_EPUB -> "EPUB"
    else -> "PDF"
}
private fun BookEntity.hasStarted(): Boolean = mode.isNotBlank() || currentPage > 0 || pageOffset > 0 || updatedAt > importedAt
// Text layouts report a character percentage; PDF pages keep the page-based fraction.
private fun BookEntity.progress(): Float = when {
    // Pages are a chapter window now, so page fields must never stand in for text progress:
    // the first page of an 11-page chapter would otherwise be shown as 9% of the whole book.
    isTextLayout() -> textProgress / 100f
    labeledPageCount <= 0 || !hasStarted() -> 0f
    else -> labeledPage.toFloat() / labeledPageCount
}