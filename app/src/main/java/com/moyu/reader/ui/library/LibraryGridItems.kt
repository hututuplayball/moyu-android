package com.moyu.reader.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.moyu.reader.R
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.ui.LocalMoyuReduceMotion
import com.moyu.reader.ui.PaperAction
import kotlin.math.roundToInt

@Composable
internal fun BookTile(
    book: BookEntity,
    organizing: Boolean,
    isSelected: Boolean,
    isUnavailable: Boolean,
    kind: Int = 0,
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
            val format = book.formatLabel()
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
internal fun ImportStatus() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("正在导入，请稍候…", style = MaterialTheme.typography.bodyMedium)
            if (!LocalMoyuReduceMotion.current) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
internal fun OrganizeBar(
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

@Composable
internal fun LibraryEmptyState(
    emptyLibrary: Boolean,
    searching: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onReset: () -> Unit,
    scoped: Boolean,
    modifier: Modifier = Modifier,
    kind: Int = 0,
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
