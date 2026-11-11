package com.moyu.reader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag as dragPointer
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.GroupEntity
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.ui.LocalMoyuReduceMotion
import com.moyu.reader.ui.LibraryOrder
import com.moyu.reader.ui.moveBookInOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALL_BOOKS = "::all"
private const val UNGROUPED = "::ungrouped"
private const val ROOT = "::root"

/**
 * The bookshelf: two shelves (comics, ebooks) as pages of one pager, groups as cards on the root
 * page, books as tiles inside a group.
 *
 * This file keeps the shelf's own state and its layout. The pieces that are not about layout live
 * beside it: [ShelfDragController] owns the drag gesture, [ShelfDialogs] the modal layer,
 * [BookShelfLabels] what a tile says about a book, and the headers, cards and grid items are plain
 * rendering with no state of their own.
 */
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
    val dialogs = rememberShelfDialogs()
    val dragger = remember { ShelfDragController() }
    var orderScope by remember { mutableStateOf<String?>(null) }
    var toolbarHeight by remember { mutableStateOf(0) }
    var pageWidth by remember { mutableStateOf(0) }
    // The shelf area's own height, kept so the empty page does not resize while it slides in.
    var contentHeight by remember { mutableStateOf(0) }
    var firstSlide by remember { mutableStateOf(true) }
    var firstKind by remember { mutableStateOf(true) }
    // A kind switch drops the open shelf; that scope reset must not also slide the whole page.
    var skipScopeSlide by remember { mutableStateOf(false) }
    var pageSlide by remember { mutableStateOf(0f) }
    val slideAnimation = remember { Animatable(0f) }
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
    val groupOrder = remember { LibraryOrder() }
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
    // The organizer bar is an overlay, so the grid reserves room for it. The empty page has to give up
    // the same room; otherwise it is taller than the viewport and starts scrolling behind the bar.
    val gridBottomPadding = if (organizing) with(density) { toolbarHeight.toDp() } + 16.dp else 24.dp
    val gridState = if (scopeId == null) {
        if (kind == KIND_EBOOK) ebookRootGridState else comicRootGridState
    } else shelfGridState

    fun beforeContentPadding(): Float = gridState.layoutInfo.beforeContentPadding.toFloat()

    fun hitTest(position: Offset, matches: (String) -> Boolean): GridItemHit? =
        gridState.draggableHits().hitTest(position, gridSidePadding, beforeContentPadding(), matches)

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

    fun leaveOrganize() {
        dragger.cancel()
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
        // Set the entry offset in the same frame as the scope change. Snapping inside
        // LaunchedEffect used to show one frame at 0 first, which read as a jitter.
        pageSlide = if (!firstSlide && !reduceMotion && pageWidth > 0) {
            pageWidth * 0.22f * if (target != null) 1f else -1f
        } else 0f
        // A group opens at its first row. Requesting the reset before the scope change lets the
        // first composition measure there, instead of showing the old scroll offset for a frame.
        if (target != null) shelfGridState.requestScrollToItem(0)
        scopeId = target
    }

    fun selectBook(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    // LazyGridItemScope has no fillParentMaxHeight; derive it from the shelf area minus vertical padding.
    // Reading the area's height instead of the grid's viewport keeps it stable: a kind switch swaps the
    // grid state, whose viewport reads as zero for the first frame and made the empty page jump.
    val emptyAreaMinHeight = with(density) {
        (contentHeight - 12.dp.roundToPx() - gridBottomPadding.roundToPx()).coerceAtLeast(0).toDp()
    }

    fun reorderAt(position: Offset) {
        val current = dragger.drag ?: return
        if (current.scope != gestureScope || !reorderAllowed) {
            dragger.cancel()
            return
        }
        val target = hitTest(position) { it.startsWith("book:") } ?: return
        dragger.hover(target.id) { id -> current.order.move(current.id, id) }
    }

    // Root grid only carries groups; shelf grids carry books, so the two drags never overlap.
    fun groupReorderAt(position: Offset) {
        val current = dragger.drag ?: return
        if (scopeId != null) {
            dragger.cancel()
            return
        }
        val target = hitTest(position) { it.startsWith("group:") || it == "ungrouped" } ?: return
        dragger.hover(target.id) { id ->
            when {
                id == "ungrouped" -> current.order.moveToEnd(current.id)
                id in groupIds -> current.order.move(current.id, id)
                else -> false
            }
        }
    }

    fun accessibleReorder(id: String, step: Int): Boolean {
        if (!organizing || !reorderAllowed || dragger.drag != null) return false
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
        val hit = hitTest(position) { it.startsWith("book:") }
        val book = hit?.id?.let(byId::get)
        if (book == null || !reorderAllowed ||
            !dragger.begin(position, hit, gestureScope, groups = false, source = shelfIds, order = order)
        ) false else {
            orderScope = scopeKey
            focusManager.clearFocus()
            keyboard?.hide()
            true
        }
    })
    val startGroupDrag by rememberUpdatedState<(Offset) -> Boolean>({ position ->
        val hit = hitTest(position) { it.startsWith("group:") }
        val group = hit?.id?.let { id -> scopedGroups.firstOrNull { it.id == id } }
        if (group == null ||
            !dragger.begin(position, hit, scopeKey, groups = true, source = groupIds, order = groupOrder)
        ) false else {
            focusManager.clearFocus()
            keyboard?.hide()
            true
        }
    })
    val updateDrag by rememberUpdatedState<(Offset) -> Unit>({ position ->
        dragger.moveTo(position)
        if (dragger.drag?.groups == true) groupReorderAt(position) else reorderAt(position)
    })
    val updateDragTarget by rememberUpdatedState<() -> Unit>({
        if (dragger.drag?.groups == true) groupReorderAt(dragger.pointer) else reorderAt(dragger.pointer)
    })
    val finishDrag by rememberUpdatedState<() -> Unit>({
        val current = dragger.drag
        when {
            current == null -> Unit
            current.groups -> if (scopeId == null) {
                dragger.finish(groupIds)?.let { (_, result) -> persistGroupOrder(result) }
            } else dragger.cancel()
            current.scope != gestureScope || !reorderAllowed -> dragger.cancel()
            // Long press only ever reorders; selecting stays a tap inside organize mode.
            else -> dragger.finish(shelfIds)?.let { (_, result) -> persistOrder(result) }
        }
    })
    val abortDrag by rememberUpdatedState<() -> Unit>({ dragger.cancel() })

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
            pageSlide = 0f
            return@LaunchedEffect
        }
        if (skipScopeSlide) {
            skipScopeSlide = false
            pageSlide = 0f
            return@LaunchedEffect
        }
        if (reduceMotion || pageWidth == 0) {
            pageSlide = 0f
            return@LaunchedEffect
        }
        if (pageSlide == 0f) return@LaunchedEffect
        slideAnimation.snapTo(pageSlide)
        slideAnimation.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) {
            pageSlide = value
        }
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
            pageSlide = 0f
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
    LaunchedEffect(gestureScope, organizing, shelfIds) {
        // A group drag is not part of organize mode, so only a book drag is dropped here.
        val bookDrag = dragger.drag?.takeIf { !it.groups }
        if (bookDrag != null && (bookDrag.scope != gestureScope || !organizing || bookDrag.id !in shelfIds)) {
            dragger.cancel()
        }
        selection = selection.intersect(shelfIds.toSet())
    }
    LaunchedEffect(scopedGroups, scopeId) {
        if (scopeId != null && scopeId != ALL_BOOKS && scopeId != UNGROUPED &&
            scopedGroups.none { it.id == scopeId }) {
            navigate(null)
        }
    }
    LaunchedEffect(dragger.drag?.id) {
        var previousFrame = withFrameNanos { it }
        while (dragger.drag != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.032f)
            previousFrame = frame
            val pointer = dragger.pointer
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

    BackHandler(enabled = !dialogs.open && (organizing || searchVisible || scopeId != null)) {
        when {
            organizing -> leaveOrganize()
            searchVisible -> closeSearch()
            else -> navigate(null)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).onSizeChanged { pageWidth = it.width }) {
            Column(Modifier.fillMaxSize().graphicsLayer {
                translationX = pageSlide
                // A scope change swaps a short shelf header for the taller root header in one
                // frame. Fading the incoming page while it slides keeps that swap from reading
                // as a jolt, even on a slow device.
                val slide = if (pageWidth > 0) pageSlide / (pageWidth * 0.22f) else 0f
                alpha = 1f - (slide * slide).coerceIn(0f, 1f) * 0.35f
            }) {
                // The headers differ in height. Animating that height moves the grid smoothly
                // instead of dropping it by a header's worth the instant the scope changes.
                Box(
                    Modifier.fillMaxWidth()
                        .animateContentSize(if (reduceMotion) snap() else tween(240, easing = FastOutSlowInEasing))
                        .clipToBounds(),
                ) {
                    if (scopeId == null) {
                        Column(Modifier.fillMaxWidth()) {
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
                        }
                    } else {
                        ShelfHeader(
                            title = title,
                            count = shelfBooks.size,
                            organizing = organizing,
                            orderBusy = order.busy,
                            hasGroup = activeGroup != null,
                            onBack = { navigate(null) },
                            onActions = { dialogs.groupActionsId = activeGroup?.id },
                            onToggleOrganize = {
                                if (organizing) leaveOrganize() else {
                                    organizing = true
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                }
                            },
                        )
                    }
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .onSizeChanged { contentHeight = it.height },
                ) {
                    HorizontalPager(
                        state = kindPagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = scopeId == null && !organizing && dragger.drag == null,
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
                                .firstOrNull { it.hasStarted() }
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
                                            onClick = { if (dragger.tapAllowed()) navigate(group.id) },
                                            modifier = Modifier.testTag("group:${group.id}")
                                                .animateItem(placementSpec = if (reduceMotion || dragger.drag != null) null else tween(180))
                                                .graphicsLayer { alpha = if (dragger.drag?.id == group.id) 0.18f else 1f },
                                        )
                                    }
                                    if (pageUngroupedBooks.isNotEmpty()) {
                                        item(key = "ungrouped", contentType = "group") {
                                            ShelfCard(
                                                name = "未分组",
                                                count = pageUngroupedBooks.size,
                                                previews = pageUngroupedBooks.take(3),
                                                onClick = { if (dragger.tapAllowed()) navigate(UNGROUPED) },
                                                modifier = Modifier.testTag("group:ungrouped")
                                                    .animateItem(placementSpec = if (reduceMotion || dragger.drag != null) null else tween(180)),
                                            )
                                        }
                                    }
                                    item(key = "new-group", contentType = "group") {
                                        NewGroupTile { dialogs.createGroup() }
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
                                            if (!dragger.tapAllowed()) return@BookTile
                                            if (organizing) selectBook(book.id) else onOpen(book)
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = if (reduceMotion) null else tween(180),
                                            // Let covers step under the finger while dragging; placement
                                            // animations re-introduce the lag the gesture is fighting.
                                            placementSpec = if (reduceMotion || dragger.drag != null) null else tween(180),
                                            fadeOutSpec = if (reduceMotion) null else tween(120),
                                        ).graphicsLayer { alpha = if (dragger.drag?.id == book.id && dragger.drag?.groups == false) 0.18f else 1f }
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
                    // The lifted card follows the finger above the grid, so it is drawn once here
                    // instead of inside every page of the pager.
                    dragger.drag?.let { lifted ->
                        val liftScale by animateFloatAsState(if (reduceMotion) 1f else 1.03f, label = "lifted item")
                        if (lifted.groups) {
                            ShelfCard(
                                name = scopedGroups.firstOrNull { it.id == lifted.id }?.name ?: "分组",
                                count = scopedBooks.count { it.groupId == lifted.id },
                                previews = previewsByGroup[lifted.id].orEmpty().take(3),
                                onClick = {},
                                modifier = Modifier.width(with(density) { lifted.width.toDp() })
                                    .graphicsLayer {
                                        translationX = dragger.pointer.x - lifted.grab.x
                                        translationY = dragger.pointer.y - lifted.grab.y
                                        scaleX = liftScale
                                        scaleY = liftScale
                                        shadowElevation = if (reduceMotion) 0f else 14.dp.toPx()
                                        shape = RoundedCornerShape(18.dp)
                                    }.clearAndSetSemantics {},
                            )
                        } else {
                            byId[lifted.id]?.let { book ->
                                PdfCover(book, Modifier.width(with(density) { lifted.width.toDp() }).aspectRatio(0.72f)
                                    .graphicsLayer {
                                        translationX = dragger.pointer.x - lifted.grab.x
                                        translationY = dragger.pointer.y - lifted.grab.y
                                        scaleX = liftScale
                                        scaleY = liftScale
                                        shadowElevation = if (reduceMotion) 0f else 12.dp.toPx()
                                        shape = RoundedCornerShape(10.dp)
                                    }.clearAndSetSemantics {})
                            }
                        }
                    }
                    // Fully qualified: inside a Column, the bare name resolves to the ColumnScope
                    // overload, which cannot take a Box alignment.
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
                            onMove = { dialogs.moving = true },
                            onRemove = { dialogs.deleteIds = shelfIds.filter { it in selection } },
                            onHeight = { toolbarHeight = it },
                        )
                    }
                }
            }
        }
    }

    ShelfDialogHost(
        dialogs = dialogs,
        shelfIds = shelfIds,
        selection = selection,
        sortedGroups = sortedGroups,
        scopedGroups = scopedGroups,
        scopedBooks = scopedBooks,
        scopeId = scopeId,
        kind = kind,
        onSelection = { selection = it },
        onLeaveScope = { navigate(null) },
        onDeleteBooks = onDeleteBooks,
        onMoveBooks = onMoveBooks,
        onDeleteGroup = onDeleteGroup,
        onCreateGroup = onCreateGroup,
        onRenameGroup = onRenameGroup,
    )
}
