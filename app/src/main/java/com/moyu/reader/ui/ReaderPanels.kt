package com.moyu.reader.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moyu.reader.book.TextLineSpacing
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.book.TextTheme
import com.moyu.reader.book.TextThemes
import com.moyu.reader.data.READER_BRIGHTNESS_FOLLOW_SYSTEM
import com.moyu.reader.page.PageSource
import com.moyu.reader.speech.SpeechEngineInfo
import com.moyu.reader.speech.SpeechReader
import com.moyu.reader.speech.SpeechVoice
import java.util.Locale
import kotlin.math.abs
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
    Speech("朗读"),
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
    speechAvailable: Boolean,
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
        // Reading aloud needs a speech engine that can read Chinese; without one the tool stays out.
        if (speechAvailable) {
            ReaderTool(Icons.Rounded.Headphones, "朗读", false, Modifier.weight(1f)) {
                onPanel(ReaderPanel.Speech)
            }
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

/**
 * Reading aloud: the engine, the voice and the speed, plus the control to start and stop.
 *
 * Only engines that can read Chinese are offered; the list comes from the device itself, so there
 * is nothing to fall back to when it is empty.
 */
@Composable
internal fun SpeechPanel(
    engines: List<SpeechEngineInfo>,
    engine: String?,
    voices: List<SpeechVoice>,
    voice: String?,
    rate: Float,
    status: SpeechReader.Status,
    reading: String?,
    failure: String?,
    background: Boolean,
    sleepMinutes: Int,
    sleepRemaining: () -> Int,
    onEngine: (String) -> Unit,
    onVoice: (String?) -> Unit,
    onRateFinished: (Float) -> Unit,
    onBackground: (Boolean) -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    // The slider follows the finger immediately, but the engine is only told when the finger lifts:
    // a rate call per pointer move made the thumb stutter, and engines cannot change a rate mid-sentence anyway.
    var dragging by remember { mutableStateOf<Float?>(null) }
    // The value just released: keep showing it until the stored rate arrives, otherwise the thumb
    // jumps back to the old speed for a frame while the preference is being written.
    var released by remember { mutableStateOf<Float?>(null) }
    val shownRate = dragging ?: released ?: rate
    LaunchedEffect(rate) {
        val pending = released ?: return@LaunchedEffect
        if (abs(rate - pending) < 0.005f) released = null
    }
    // The custom timer is a collapsible row, so the panel keeps its height when it is closed.
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    // The panel is taller than the screen on small devices once the custom timer opens. Scrolling
    // keeps every row at full height instead of letting the column squeeze the last one.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperAction(
                label = when (status) {
                    SpeechReader.Status.Playing -> "暂停"
                    SpeechReader.Status.Paused -> "继续"
                    else -> "开始朗读"
                },
                onClick = onToggle,
                modifier = Modifier.weight(1f),
                primary = status != SpeechReader.Status.Playing,
            )
            PaperAction("停止", onStop, Modifier.weight(1f), enabled = status != SpeechReader.Status.Idle)
        }
        if (failure != null) {
            Text(failure, Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        }
        if (reading != null) {
            // The page is hidden behind this panel, so the sentence being read is repeated here.
            Text(reading, Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        Text("语音引擎", Modifier.padding(start = 24.dp, top = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        engines.forEach { info ->
            PaperSheetRow(Icons.Rounded.RecordVoiceOver, info.label, { onEngine(info.packageName) },
                selected = info.packageName == engine)
        }
        if (voices.isNotEmpty()) {
            PaperDivider(Modifier.padding(horizontal = 20.dp))
            Text("音色", Modifier.padding(start = 24.dp, top = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            voices.forEach { item ->
                PaperSheetRow(Icons.Rounded.GraphicEq, item.label, { onVoice(item.key) },
                    selected = item.key == voice)
            }
        }
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("语速", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.ROOT, "%.1fx", shownRate), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.padding(horizontal = 24.dp)) {
            PaperSlider(
                value = shownRate,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val value = dragging
                    dragging = null
                    if (value != null) {
                        released = value
                        onRateFinished(value)
                    }
                },
                valueRange = 0.5f..2f,
                enabled = true,
            )
        }
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        SpeechSwitchRow(
            title = "后台播放",
            description = "切后台、锁屏后继续朗读，通知栏可随时停止",
            checked = background,
            onCheckedChange = onBackground,
        )
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        val customActive = sleepMinutes > 0 && sleepMinutes !in SLEEP_PRESETS
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("定时关闭", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    sleepMinutes <= 0 -> "不设"
                    sleepRemaining() > 0 -> "剩余 ${formatCountdown(sleepRemaining())}"
                    else -> "$sleepMinutes 分钟"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (sleepMinutes > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PaperAction(
                "自定义",
                {
                    customOpen = !customOpen
                    if (customOpen && customText.isBlank()) {
                        customText = if (customActive) sleepMinutes.toString() else ""
                    }
                },
                primary = customOpen || customActive,
            )
        }
        // Three presets fit one phone row without squeezing a label; any other value is typed in.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "不设", 30 to "30 分钟", 60 to "60 分钟")
                .forEach { (minutes, label) ->
                    PaperAction(label, {
                        customOpen = false
                        onSleepMinutes(minutes)
                    }, Modifier.weight(1f), primary = !customOpen && sleepMinutes == minutes)
                }
        }
        if (customOpen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The field's own Row fills the width, so the weight belongs to this wrapper;
                // giving the weight to PaperTextField would measure the confirm button to nothing.
                Box(Modifier.weight(1f)) {
                    PaperTextField(
                        value = customText,
                        onValueChange = { value -> customText = value.filter { it.isDigit() }.take(4) },
                        label = "自定义分钟数",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                PaperAction(
                    "确定",
                    {
                        val minutes = customText.toIntOrNull()
                        if (minutes != null && minutes > 0) {
                            onSleepMinutes(minutes.coerceAtMost(999))
                            customOpen = false
                        }
                    },
                    Modifier.width(96.dp),
                    enabled = customText.toIntOrNull()?.let { it > 0 } == true,
                    primary = true,
                )
            }
        }
        // Keeps the slider off the screen edge: it sits at the very bottom of the panel and an
        // accidental press there used to change the reading speed.
        Spacer(Modifier.height(16.dp))
    }
}

/** One on/off setting; the whole row toggles, the switch itself is decoration. */
@Composable
private fun SpeechSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalMoyuReduceMotion.current
    val trackColor by animateColorAsState(
        if (checked) colors.primary else colors.outlineVariant,
        animationSpec = if (reduceMotion) snap() else tween(180), label = "switch track",
    )
    val thumbOffset by animateDpAsState(if (checked) 14.dp else 0.dp,
        animationSpec = if (reduceMotion) snap() else tween(180), label = "switch thumb")
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange,
                interactionSource = remember { MutableInteractionSource() }, indication = null)
            .semantics { stateDescription = if (checked) "已开启" else "已关闭" }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        // Accessibility exposes only the row's switch action, not the decoration.
        Box(Modifier.width(38.dp).heightIn(min = 24.dp).clip(RoundedCornerShape(12.dp))
            .background(trackColor).padding(3.dp).clearAndSetSemantics {}) {
            Box(Modifier.offset { IntOffset(thumbOffset.roundToPx(), 0) }.size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) colors.onPrimary else colors.surface))
        }
    }
}

/** "mm:ss", or "h:mm:ss" for a timer longer than an hour. */
private fun formatCountdown(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, secs)
}

/** The timer values offered as one-tap buttons; anything else goes through the custom field. */
private val SLEEP_PRESETS = listOf(30, 60)

/** The line being read, shown over the page while the machine reads. */
@Composable
internal fun SpeechBar(
    text: String,
    status: SpeechReader.Status,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            PaperIconAction(
                if (status == SpeechReader.Status.Playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (status == SpeechReader.Status.Playing) "暂停朗读" else "继续朗读",
                onToggle,
            )
            PaperIconAction(Icons.Rounded.Stop, "停止朗读", onStop)
        }
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
