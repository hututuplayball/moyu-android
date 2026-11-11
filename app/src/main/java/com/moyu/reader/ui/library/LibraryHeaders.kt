package com.moyu.reader.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.ui.LocalMoyuReduceMotion
import com.moyu.reader.ui.PaperDivider
import com.moyu.reader.ui.PaperIconAction
import com.moyu.reader.ui.PaperTextField

/** The shelf root's title row, which becomes the search row without changing height. */
@Composable
internal fun RootHeader(
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
internal fun LibraryKindSwitch(
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

/** A group's own header: back, name, and the organize entry. */
@Composable
internal fun ShelfHeader(
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
