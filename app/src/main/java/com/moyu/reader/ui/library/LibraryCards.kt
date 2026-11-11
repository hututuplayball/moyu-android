package com.moyu.reader.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.KIND_EBOOK
import kotlin.math.roundToInt

@Composable
internal fun ContinueReadingCard(book: BookEntity, kind: Int, onOpen: () -> Unit) {
    // A finished book stays here instead of vanishing: the shelf is where the reader last was.
    val label = if (book.isFinished()) "已读完" else "正在阅读"
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
        Row(
            Modifier.testTag("continue-reading").fillMaxWidth().clickable(
                role = Role.Button, onClickLabel = "继续阅读${book.title}", onClick = onOpen,
            ).padding(16.dp).semantics { contentDescription = "$label ${book.title}，第 ${book.labeledPage} 页，共 ${book.labeledPageCount} 页" },
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PdfCover(book, Modifier.width(52.dp).aspectRatio(0.72f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall,
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
internal fun AllBooksCard(books: List<BookEntity>, kind: Int, onClick: () -> Unit) {
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
internal fun CoverStack(books: List<BookEntity>, modifier: Modifier = Modifier) {
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
internal fun GroupSectionHeader() {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("分组", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ShelfCard(
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
internal fun NewGroupTile(onClick: () -> Unit) {
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
