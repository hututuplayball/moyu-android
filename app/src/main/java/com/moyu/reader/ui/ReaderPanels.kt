package com.moyu.reader.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moyu.reader.book.TextLineSpacing
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.book.TextTheme
import com.moyu.reader.book.TextThemes
import com.moyu.reader.data.READER_BRIGHTNESS_FOLLOW_SYSTEM
import com.moyu.reader.page.PageSource
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/** The panels the reader toolbar can open; the enum keeps titles and routing in one place. */
internal enum class ReaderPanel(val title: String) {
    Bookmarks("书签"),
    Pages("页面"),
    Mode("阅读方式"),
    Brightness("阅读亮度"),
    Typesetting("排版"),
    Toc("目录"),
    View("阅读版本"),
}

/** A PDF's two reading surfaces: the original pages, or the text layer laid out as a book. */
internal data class PdfViewState(
    val textView: Boolean,
    val available: Boolean,
    val preparing: Boolean,
    val notice: String?,
    /** The stored verdict came with an explanation, so the reader may ask for a new probe. */
    val retryable: Boolean = false,
)

/** Tool strip under the slider; text books trade the thumbnail grid for contents and typesetting. */
@Composable
internal fun ReaderToolRow(
    bookmarked: Boolean,
    isText: Boolean,
    mode: String,
    brightness: Float,
    onPanel: (ReaderPanel) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ReaderTool(Icons.Rounded.Bookmark, "书签", bookmarked, Modifier.weight(1f)) {
            onPanel(ReaderPanel.Bookmarks)
        }
        if (isText) {
            ReaderTool(Icons.AutoMirrored.Rounded.MenuBook, "目录", false, Modifier.weight(1f)) {
                onPanel(ReaderPanel.Toc)
            }
            ReaderTool(Icons.Rounded.TextFields, "排版", false, Modifier.weight(1f)) {
                onPanel(ReaderPanel.Typesetting)
            }
        } else {
            ReaderTool(Icons.Rounded.GridView, "页面", false, Modifier.weight(1f)) {
                onPanel(ReaderPanel.Pages)
            }
        }
        ReaderTool(if (mode == "vertical") Icons.Rounded.SwapVert else Icons.Rounded.SwapHoriz,
            "阅读方式", false, Modifier.weight(1f)) { onPanel(ReaderPanel.Mode) }
        ReaderTool(Icons.Rounded.BrightnessMedium, "亮度", brightness >= 0f, Modifier.weight(1f)) {
            onPanel(ReaderPanel.Brightness)
        }
    }
}

/**
 * The PDF view switch for the reader's top bar: same sliding highlight as the settings picker, sized
 * to sit opposite the back button. While the text layer is missing it becomes a chip that opens the
 * full explanation instead of pretending to switch.
 */
@Composable
internal fun ReaderViewSwitch(state: PdfViewState, onPick: (Boolean) -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalMoyuReduceMotion.current
    val shape = RoundedCornerShape(11.dp)
    if (!state.available) {
        Box(
            Modifier.height(32.dp).clip(shape).background(colors.surfaceContainer)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClickLabel = "阅读版本") { onOpen() }
                .semantics { contentDescription = "阅读版本" }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (state.preparing) "识别中" else "文字版",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        return
    }
    val gap = 2.dp
    BoxWithConstraints(Modifier.width(112.dp).height(32.dp).clip(shape).background(colors.surfaceContainer).padding(gap)) {
        val segment = ((maxWidth - gap) / 2).coerceAtLeast(1.dp)
        val offset by animateDpAsState(
            targetValue = if (state.textView) segment + gap else 0.dp,
            animationSpec = if (reduceMotion) snap() else tween(240),
            label = "reader view highlight",
        )
        Box(Modifier.offset(x = offset).width(segment).fillMaxHeight().clip(RoundedCornerShape(9.dp))
            .background(colors.primaryContainer))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(false to "原版", true to "文字版").forEach { (textView, label) ->
                val selected = state.textView == textView
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.RadioButton, onClickLabel = label) { onPick(textView) }
                        .semantics { this.selected = selected; contentDescription = label }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Original pages keep their exact layout; the text version flows and re-paginates. */
@Composable
internal fun ViewPanel(state: PdfViewState, onPick: (Boolean) -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        PaperSheetRow(Icons.Rounded.PictureAsPdf, "原版 PDF",
            { onPick(false) }, selected = !state.textView)
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        PaperSheetRow(Icons.Rounded.Article, "文字版",
            { onPick(true) }, selected = state.textView, enabled = state.available,
            trailing = when {
                state.textView -> null
                state.preparing -> "正在识别"
                else -> null
            })
        if (state.retryable) {
            // The verdict may be stale: the file can be re-imported under the same book id.
            PaperDivider(Modifier.padding(horizontal = 20.dp))
            PaperSheetRow(Icons.Rounded.Refresh, "重新识别文字层", { onRetry() })
        }
        val hint = state.notice ?: when {
            state.preparing -> "正在识别这本书的文字层，稍后就能切换。"
            state.available -> "文字版按当前排版重排，翻页更流畅；原版保留书中的版式与插图。"
            else -> null
        }
        if (hint != null) PanelHint(hint)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun BookmarksPanel(
    bookmarks: List<Int>,
    currentMark: Int,
    currentMarked: Boolean,
    onToggle: (Int) -> Unit,
    onJump: (Int) -> Unit,
    label: (Int) -> String = { page -> "第 ${page + 1} 页" },
) {
    Column(Modifier.fillMaxWidth()) {
        PaperSheetRow(
            if (currentMarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            if (currentMarked) "移除当前位置书签 · ${label(currentMark)}"
            else "添加当前位置书签 · ${label(currentMark)}",
            { onToggle(currentMark) },
        )
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        if (bookmarks.isEmpty()) {
            PanelHint("暂无书签。翻到想留住的位置，可在这里添加。")
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(bookmarks, key = { it }) { page ->
                    PaperSheetRow(Icons.Rounded.Bookmark, label(page), { onJump(page) },
                        trailingIcon = Icons.Rounded.Close, trailingLabel = "移除${label(page)}书签",
                        onTrailingClick = { onToggle(page) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun PageGridPanel(session: PageSource, currentPage: Int, onJump: (Int) -> Unit) {
    val thumbState = rememberLazyGridState(initialFirstVisibleItemIndex = currentPage)
    LazyVerticalGrid(columns = GridCells.Fixed(3), state = thumbState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(session.pageCount, key = { it }) { page ->
            PageThumbnail(session, page, page == currentPage) { onJump(page) }
        }
    }
}

@Composable
internal fun ReadingModePanel(mode: String, rightToLeft: Boolean, onPick: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        PaperSheetRow(Icons.Rounded.SwapVert, "上下连续",
            { onPick("vertical", rightToLeft) }, selected = mode == "vertical")
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        // Right-to-left paging is no longer offered; old books that still store it simply read
        // left-to-right once a mode is picked here.
        PaperSheetRow(Icons.Rounded.SwapHoriz, "左右翻页",
            { onPick("horizontal", false) }, selected = mode == "horizontal")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun BrightnessPanel(brightness: Float, systemBrightness: Float, onBrightness: (Float) -> Unit) {
    val follow = brightness < 0f
    val shown = if (follow) systemBrightness else brightness
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BrightnessLow, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (follow) "跟随系统" else "${(shown * 100).roundToInt()}%",
                Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.padding(horizontal = 24.dp)) {
            PaperSlider(
                value = shown,
                // The system slider reaches the panel's own minimum, so reading brightness does too.
                onValueChange = { onBrightness(it.coerceIn(0f, 1f)) },
                onValueChangeFinished = {},
                valueRange = 0f..1f,
                enabled = true,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            PaperAction("跟随系统", { onBrightness(READER_BRIGHTNESS_FOLLOW_SYSTEM) },
                Modifier.fillMaxWidth(), enabled = !follow)
        }
    }
}

/** Font size, paper theme and line spacing — every change repaginates through [onChange]. */
@Composable
internal fun TypesettingPanel(
    settings: TextReadingSettings,
    onChange: ((TextReadingSettings) -> TextReadingSettings) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PaperIconAction(Icons.Rounded.TextDecrease, "缩小字号",
                { onChange { it.stepped(-1) } },
                enabled = settings.fontSizeSp > TextReadingSettings.MIN_FONT_SIZE)
            Text("${settings.fontSizeSp} 号字", Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium)
            PaperIconAction(Icons.Rounded.TextIncrease, "增大字号",
                { onChange { it.stepped(1) } },
                enabled = settings.fontSizeSp < TextReadingSettings.MAX_FONT_SIZE)
        }
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextThemes.all.forEach { theme ->
                TextThemeDot(theme, theme.id == settings.theme.id) { onChange { it.copy(theme = theme) } }
            }
        }
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        Text("行距", Modifier.padding(start = 24.dp, top = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextLineSpacing.entries.forEach { spacing ->
                PaperChoice(spacing.label, settings.lineSpacing == spacing,
                    { onChange { it.copy(lineSpacing = spacing) } }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun ChapterPanel(source: TextPageSource, activeChapter: Int, onJump: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val count = source.chapterCount()
        if (count <= 1) {
            PanelHint("本书没有可用的目录。")
        } else {
            // Open the table of contents on the chapter being read, not on chapter one.
            val state = rememberLazyListState(
                initialFirstVisibleItemIndex = activeChapter.coerceIn(0, count - 1),
            )
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items((0 until count).toList(), key = { it }) { index ->
                    PaperSheetRow(Icons.AutoMirrored.Rounded.MenuBook, source.chapterTitle(index),
                        { onJump(index) }, selected = index == activeChapter)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun TocPanel(
    entries: List<TextPageSource.TocEntry>,
    currentPage: Int,
    onJump: (Int) -> Unit,
    showPages: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        if (entries.isEmpty()) {
            PanelHint("本书没有可用的目录。")
        } else {
            // The chapter holding the current page stays highlighted, like a table of contents marker.
            val active = entries.indexOfLast { it.page <= currentPage }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                itemsIndexed(entries) { index, entry ->
                    PaperSheetRow(Icons.AutoMirrored.Rounded.MenuBook, entry.title,
                        { onJump(entry.page) },
                        trailing = if (showPages) "第 ${entry.page + 1} 页" else null,
                        selected = index == active)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PanelHint(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReaderTool(icon: ImageVector, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) colors.surfaceContainerLow else Color.Transparent,
        animationSpec = tween(120), label = "tool press",
    )
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(background)
            .clickable(interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (active) colors.primary else colors.onSurfaceVariant)
    }
}

/** Round swatch previewing a reading theme; the check mark is tinted with the theme's own text colour. */
@Composable
private fun TextThemeDot(theme: TextTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color(theme.bg))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.outlineVariant,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "选择${theme.label}主题", onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, "已选中${theme.label}主题", Modifier.size(18.dp), tint = Color(theme.text))
        }
    }
}

@Composable
private fun PageThumbnail(session: PageSource, page: Int, selected: Boolean, onClick: () -> Unit) {
    var bitmap by remember(session, page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, page) {
        try { bitmap = session.render(page, 192, cache = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bitmap = null }
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(.7f).background(Color.White), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap!!.asImageBitmap(), "跳转第 ${page + 1} 页", Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit)
                } else {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Text("${page + 1}", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
