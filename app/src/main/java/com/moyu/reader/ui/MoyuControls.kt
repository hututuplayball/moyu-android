package com.moyu.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PaperAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(13.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && enabled) 0.975f else 1f,
        animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(110), label = "action press")
    // Outline pills instead of stacked filled blocks: closer to the paper shelf, less like stock Material.
    val background = when {
        !enabled -> Color.Transparent
        destructive -> colors.errorContainer
        primary -> colors.primary
        else -> Color.Transparent
    }
    val stroke = when {
        !enabled -> BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f))
        primary || destructive -> null
        else -> BorderStroke(1.dp, colors.outlineVariant)
    }
    val foreground = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.45f)
        destructive -> colors.onErrorContainer
        primary -> colors.onPrimary
        else -> colors.primary
    }
    Box(
        modifier = modifier.graphicsLayer { scaleX = press; scaleY = press }
            .heightIn(min = 48.dp).clip(shape)
            .background(background)
            .then(if (stroke != null) Modifier.border(stroke, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = foreground)
            Text(label, style = MaterialTheme.typography.labelLarge, color = foreground, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun PaperIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed && enabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(120),
        label = "icon press",
    )
    Box(
        modifier = modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(background)
            .clickable(interactionSource = interaction, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

@Composable
internal fun PaperChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) colors.primaryContainer else Color.Transparent,
        animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(180),
        label = "paper choice",
    )
    Box(
        modifier = modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(background)
            .selectable(selected = selected, role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Segmented choices whose highlight slides from one side to the other. Two backgrounds fading in
 * and out over each other read as a blink — especially while the whole theme changes underneath.
 */
@Composable
internal fun PaperSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalMoyuReduceMotion.current
    val gap = 3.dp
    val height = 48.dp
    val shape = RoundedCornerShape(14.dp)
    val segmentShape = RoundedCornerShape(11.dp)
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer)
            .padding(gap).selectableGroup(),
    ) {
        val segment = ((maxWidth - gap * (options.size - 1)) / options.size).coerceAtLeast(1.dp)
        val offset by animateDpAsState(
            (segment + gap) * index.toFloat(),
            animationSpec = if (reduceMotion) snap() else tween(240),
            label = "segment highlight",
        )
        Box(Modifier.offset(x = offset).width(segment).height(height).clip(segmentShape)
            .background(colors.primaryContainer))
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(segmentShape)
                        .selectable(selected = isSelected, role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { onSelect(value) })
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        color = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun PaperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val stroke by animateColorAsState(
        if (focused) colors.primary.copy(alpha = 0.7f) else colors.outlineVariant.copy(alpha = 0.75f),
        animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(160),
        label = "field stroke",
    )
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (focused) colors.surface else colors.surfaceContainerLow)
            .border(1.dp, stroke, shape)
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.weight(1f).heightIn(min = 50.dp).padding(vertical = 14.dp)
                .semantics { contentDescription = label },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { inner ->
                // Both the hint and the real text are centred in the same box, so the hint never drifts up.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.85f))
                    inner()
                }
            },
        )
        trailing?.invoke()
    }
}

@Composable
internal fun PaperDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Paper-toned snackbar row; replaces the stock Material pill. */
@Composable
internal fun PaperSnackbar(message: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 8.dp) {
        Text(message, Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}

@Composable
internal fun PaperSheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed && enabled) colors.surfaceContainerLow else Color.Transparent,
        animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(120),
        label = "sheet row press",
    )
    Row(
        Modifier.fillMaxWidth().background(background)
            .clickable(interactionSource = interaction, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp),
            tint = if (selected) colors.primary else colors.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.5f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant)
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = colors.primary)
        if (trailingIcon != null && onTrailingClick != null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onTrailingClick)
                .semantics { contentDescription = trailingLabel ?: label },
                contentAlignment = Alignment.Center) {
                Icon(trailingIcon, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** Thin ink track with a paper-ringed thumb; tap or drag anywhere on the row. */
@Composable
internal fun PaperSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var width by remember { mutableStateOf(1) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    fun valueAt(x: Float): Float = valueRange.start + (x / width).coerceIn(0f, 1f) * span
    Box(
        modifier.fillMaxWidth().height(44.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(enabled, span) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onValueChange(valueAt(down.position.x))
                    down.consume()
                    drag(down.id) { change ->
                        change.consume()
                        onValueChange(valueAt(change.position.x))
                    }
                    onValueChangeFinished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val centerY = size.height / 2
            val stroke = 3.dp.toPx()
            drawLine(colors.outlineVariant, Offset(0f, centerY), Offset(size.width, centerY),
                strokeWidth = stroke, cap = StrokeCap.Round)
            val x = fraction * size.width
            drawLine(colors.primary, Offset(0f, centerY), Offset(x, centerY),
                strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(colors.surface, radius = 9.dp.toPx(), center = Offset(x, centerY))
            drawCircle(colors.primary, radius = 7.dp.toPx(), center = Offset(x, centerY))
        }
    }
}

@Composable
internal fun PaperModal(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalMoyuReduceMotion.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val reveal by animateFloatAsState(
        if (entered) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(180),
        label = "paper modal",
    )
    val focusManager = LocalFocusManager.current
    // An in-window overlay instead of a platform Dialog: the extra window made the status bar
    // repaint (a visible flash near the top) before its chrome caught up with the activity.
    LaunchedEffect(Unit) { focusManager.clearFocus() }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * reveal))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .clearAndSetSemantics { },
        )
        Surface(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().safeDrawingPadding().imePadding()
                .padding(horizontal = 24.dp, vertical = 12.dp).graphicsLayer {
                    alpha = if (reduceMotion) 1f else reveal
                    translationY = if (reduceMotion) 0f else (1f - reveal) * 12.dp.toPx()
                },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Keep actions reachable while the keyboard or large text reduces the body viewport.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    content()
                }
                actions()
            }
        }
    }
}

@Composable
internal fun PaperConfirmation(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
    confirmOnLeft: Boolean = false,
) {
    PaperModal(title, onDismiss, actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (confirmOnLeft) {
                PaperAction(confirmLabel, onConfirm, Modifier.weight(1f), primary = true, destructive = destructive)
                PaperAction(dismissLabel, onDismiss, Modifier.weight(1f))
            } else {
                PaperAction(dismissLabel, onDismiss, Modifier.weight(1f))
                PaperAction(confirmLabel, onConfirm, Modifier.weight(1f), primary = true, destructive = destructive)
            }
        }
    }) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
