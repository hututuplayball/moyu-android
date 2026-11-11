package com.moyu.reader.ui.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moyu.reader.ui.PaperDivider
import com.moyu.reader.ui.PaperIconAction
import com.moyu.reader.ui.PdfViewState
import com.moyu.reader.ui.ReaderPanel
import com.moyu.reader.ui.ReaderViewSwitch

/**
 * The reader's top bar: the way back, the book's name, and the view switch for PDFs.
 *
 * The page counter is only printed for page-based books; flowing text carries its chapter heading
 * on the page itself, and printing it twice reads as a duplicate title.
 */
@Composable
internal fun ReaderTopBar(
    visible: Boolean,
    bookTitle: String,
    pageLabel: String?,
    viewState: PdfViewState?,
    motionDuration: Int,
    onBack: () -> Unit,
    onPickView: (Boolean) -> Unit,
    onOpenViewPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fully qualified: inside a Column, the bare name resolves to the ColumnScope overload.
    androidx.compose.animation.AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = fadeIn(tween(motionDuration)) + slideInVertically(tween(motionDuration)) { -it },
        exit = fadeOut(tween(motionDuration)) + slideOutVertically(tween(motionDuration)) { -it },
    ) {
        Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .97f), shadowElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PaperIconAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架", onBack)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (pageLabel != null) {
                        Text(
                            pageLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // The PDF view switch belongs with the back button, not a toolbar page away.
                if (viewState != null && (viewState.available || viewState.preparing || viewState.retryable)) {
                    ReaderViewSwitch(viewState, onPick = onPickView, onOpen = onOpenViewPanel)
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}

/**
 * The sheet that carries the reading controls. It owns the handle, the panel header and the
 * animation; what goes inside is the caller's, so the panel dispatch stays next to the state it
 * switches on.
 */
@Composable
internal fun ReaderBottomSheet(
    visible: Boolean,
    panel: ReaderPanel?,
    motionDuration: Int,
    onCollapse: () -> Unit,
    onDismissPanel: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = fadeIn(tween(motionDuration)) + slideInVertically(tween(motionDuration)) { it },
        exit = fadeOut(tween(motionDuration)) + slideOutVertically(tween(motionDuration)) { it },
    ) {
        Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            shadowElevation = 10.dp) {
            // One panel only: sub views replace the tools in place instead of stacking a dialog on top.
            Column(Modifier.fillMaxWidth().navigationBarsPadding().animateContentSize(tween(motionDuration))) {
                Box(
                    Modifier.fillMaxWidth().height(if (panel == null) 38.dp else 30.dp)
                        .pointerInput(panel) {
                            var dx = 0f
                            var dy = 0f
                            detectDragGestures(
                                onDragStart = { dx = 0f; dy = 0f },
                                onDragEnd = { if (dx > 70f || dy > 70f) onCollapse() },
                            ) { change, drag ->
                                dx += drag.x
                                dy += drag.y
                                change.consume()
                            }
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = "收起阅读面板", onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant))
                }
                if (panel != null) {
                    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        PaperIconAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回阅读工具栏", onDismissPanel)
                        Text(panel.title, Modifier.weight(1f).padding(start = 2.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    PaperDivider(Modifier.padding(horizontal = 20.dp))
                }
                content()
            }
        }
    }
}
