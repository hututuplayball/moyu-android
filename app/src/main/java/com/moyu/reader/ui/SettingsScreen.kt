package com.moyu.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyu.reader.data.MAX_PREFETCH_RADIUS
import com.moyu.reader.data.MIN_PREFETCH_RADIUS
import com.moyu.reader.data.PRESET_PREFETCH_RADIUS
import com.moyu.reader.data.ReaderPreferences

@Composable
fun SettingsScreen(
    preferences: ReaderPreferences,
    onBack: () -> Unit,
    onTheme: (String) -> Unit,
    onDefaultMode: (String) -> Unit,
    onRightToLeft: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    version: String,
    onPrefetchRadius: (Int) -> Unit = {},
) {
    // The author letter is a page inside settings, not a third route for the shelf to juggle.
    var authorPage by rememberSaveable { mutableStateOf(false) }
    // The account QR is an overlay, so it has to sit above whichever page is showing.
    var wechatQr by rememberSaveable { mutableStateOf(false) }
    // Zero means the custom-depth dialog is closed; prefetch depth itself is never zero.
    var customRadius by rememberSaveable { mutableIntStateOf(0) }
    // Hoisted so returning from the letter lands back on the row that was tapped, not at the top.
    val listState = rememberLazyListState()
    val reduceMotion = LocalMoyuReduceMotion.current
    BackHandler(enabled = authorPage) { authorPage = false }
    BackHandler(enabled = !authorPage && !wechatQr && customRadius == 0, onBack = onBack)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            AnimatedContent(
                targetState = authorPage,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val duration = if (reduceMotion) 0 else 240
                    val direction = if (targetState) 1 else -1
                    (slideInHorizontally(tween(duration)) { it / 4 * direction } + fadeIn(tween(duration)))
                        .togetherWith(slideOutHorizontally(tween(duration)) { -it / 4 * direction } + fadeOut(tween(duration)))
                },
                label = "settings page",
            ) { letter ->
                if (letter) {
                    AuthorPage(onBack = { authorPage = false })
                } else {
                    SettingsList(preferences, onBack, onTheme, onDefaultMode, onRightToLeft, onKeepScreenOn,
                        version, onPrefetchRadius, listState, onOpenAuthor = { authorPage = true },
                        onOpenWechat = { wechatQr = true },
                        onOpenPrefetchCustom = {
                            customRadius = preferences.lastCustomPrefetchRadius
                                .coerceIn(MIN_PREFETCH_RADIUS, MAX_PREFETCH_RADIUS)
                        })
                }
            }
            if (wechatQr) WechatQrModal(onDismiss = { wechatQr = false })
            if (customRadius > 0) {
                PrefetchCustomModal(
                    initial = customRadius,
                    onDismiss = { customRadius = 0 },
                    onConfirm = { value ->
                        customRadius = 0
                        onPrefetchRadius(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsList(
    preferences: ReaderPreferences,
    onBack: () -> Unit,
    onTheme: (String) -> Unit,
    onDefaultMode: (String) -> Unit,
    onRightToLeft: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    version: String,
    onPrefetchRadius: (Int) -> Unit,
    listState: LazyListState,
    onOpenAuthor: () -> Unit,
    onOpenWechat: () -> Unit,
    onOpenPrefetchCustom: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperIconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回书架", onBack)
            Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("settings-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "reading") {
                SettingsSection("阅读") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("默认翻页方式", Modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ModeOption("上下连续", "整本书连成一条长页，连续下滑，适合条漫",
                            preferences.defaultMode == "vertical") { onDefaultMode("vertical") }
                        // Right-to-left paging is gone; picking horizontal always reads left-to-right.
                        ModeOption("左右翻页", "一次翻过一页，常规排版",
                            preferences.defaultMode == "horizontal") {
                            onDefaultMode("horizontal"); onRightToLeft(false)
                        }
                        Text("这里选的是默认值，阅读时每本书都能单独切换。",
                            Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SettingsDivider()
                    PrefetchBlock(preferences.prefetchRadius, onPrefetchRadius, onOpenPrefetchCustom)
                    SettingsDivider()
                    SettingsToggle("阅读时保持屏幕常亮", preferences.keepScreenOn, onKeepScreenOn)
                    Text("字号、行距与纸张配色在阅读页的「排版」面板中调整，对所有电子书生效。",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "appearance") {
                SettingsSection("外观") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("主题", style = MaterialTheme.typography.titleMedium)
                        PaperSegmented(
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                            preferences.theme, onTheme,
                        )
                    }
                }
            }
            item(key = "resources") { ResourceSettingsSection(onOpenWechat) }
            item(key = "author") { AuthorSettingsSection(onOpenAuthor) }
            item(key = "about") {
                SettingsSection("关于墨屿") {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        MoyuAppIcon(size = 52.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("墨屿", style = MaterialTheme.typography.titleMedium)
                            Text("版本 $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("方寸之间，自有山海。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    PaperDivider(Modifier.padding(horizontal = 16.dp))
                    Text(
                        "海把故事推上岸，墨把它们留在纸上。\n你读到哪一页，哪一页就为你亮着灯。",
                        Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Serif, lineHeight = 26.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Depth picker: three presets, then a stepper that reopens at the last custom depth. */
@Composable
private fun PrefetchBlock(radius: Int, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val value = radius.coerceIn(MIN_PREFETCH_RADIUS, MAX_PREFETCH_RADIUS)
    val custom = value > PRESET_PREFETCH_RADIUS
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("页面预渲染", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
        PaperSegmented(
            // Once custom is active the segment carries its real depth, so the value is never hidden.
            listOf("1" to "1 页", "2" to "2 页", "3" to "3 页", "custom" to if (custom) "$value 页" else "自定义"),
            if (custom) "custom" else value.toString(),
            { key -> if (key == "custom") onCustom() else onPick(key.toInt()) },
        )
        Text(
            "适用于漫画与 PDF 原版页：以当前页为中心，向前后各预渲染 $value 页。" +
                "范围越大，翻页越流畅，内存占用也越高；默认 3 页。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrefetchCustomModal(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var draft by remember(initial) {
        mutableIntStateOf(initial.coerceIn(MIN_PREFETCH_RADIUS, MAX_PREFETCH_RADIUS))
    }
    PaperModal(
        title = "自定义预渲染页数",
        onDismiss = onDismiss,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperAction("取消", onDismiss, Modifier.weight(1f))
                PaperAction("确定", { onConfirm(draft) }, Modifier.weight(1f), primary = true)
            }
        },
    ) {
        Text("适用于漫画与 PDF 原版页：以当前页为中心，向前后各预渲染若干页。范围越大，翻页越流畅，内存占用也越高。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIconAction(Icons.Outlined.Remove, "减少", {
                draft = (draft - 1).coerceAtLeast(MIN_PREFETCH_RADIUS)
            }, enabled = draft > MIN_PREFETCH_RADIUS)
            Text("$draft 页", Modifier.widthIn(min = 104.dp),
                style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            PaperIconAction(Icons.Outlined.Add, "增加", {
                draft = (draft + 1).coerceAtMost(MAX_PREFETCH_RADIUS)
            }, enabled = draft < MAX_PREFETCH_RADIUS)
        }
        Text("可调范围 $MIN_PREFETCH_RADIUS–$MAX_PREFETCH_RADIUS 页；缓存不足时，较早的页面将自动释放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** One radio-style reading mode: the whole row is the target, the ring shows the current default. */
@Composable
private fun ModeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick,
                interactionSource = remember { MutableInteractionSource() }, indication = null)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Box(Modifier.size(20.dp).clip(CircleShape)
            .border(1.5.dp, if (selected) colors.primary else colors.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.primary))
        }
    }
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, description: String? = null) {
    val reduceMotion = LocalMoyuReduceMotion.current
    val trackColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = if (reduceMotion) snap() else tween(180), label = "switch track",
    )
    val thumbOffset by animateDpAsState(if (checked) 14.dp else 0.dp,
        animationSpec = if (reduceMotion) snap() else tween(180), label = "switch thumb")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange,
                interactionSource = remember { MutableInteractionSource() }, indication = null)
            .semantics { stateDescription = if (checked) "已开启" else "已关闭" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Exclude decoration so accessibility exposes only the row's switch action.
        Box(Modifier.width(38.dp).heightIn(min = 24.dp).clip(RoundedCornerShape(12.dp)).background(trackColor)
            .padding(3.dp).clearAndSetSemantics {}) {
            Box(Modifier.offset { IntOffset(thumbOffset.roundToPx(), 0) }.size(18.dp).clip(RoundedCornerShape(9.dp))
                .background(if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface))
        }
    }
}

@Composable
private fun SettingsDivider() = PaperDivider(Modifier.padding(horizontal = 16.dp))
