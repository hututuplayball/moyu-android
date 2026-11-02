package com.moyu.reader.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyu.reader.R

/**
 * All copy for the two about sections lives here, so the words can be rewritten without touching
 * any layout: one name, a few paragraphs, a WeChat account, and where to find books.
 */
internal object MoyuAuthor {
    const val name = "胡图图打篮球"
    const val wechatName = "效率工具铺"
    const val tagline = "把书留在岸上，把灯留给你。"
    const val signature = "—— 胡图图打篮球"
    const val wechatHint = "微信扫一扫，或搜索「效率工具铺」"

    val paragraphs = listOf(
        "做墨屿的时候，我只想要一件事——本地阅读也可以有纸的温度。书是你自己带来的，" +
            "读到第几页只有你知道；没有推送，没有推荐，也没有谁在云端看你读什么。",
        "谢谢把它装进口袋。愿你在方寸之间，看见自己的山海。",
    )
}

internal data class ReadingResource(
    val title: String,
    val address: String,
    val url: String,
    val icon: ImageVector,
    // The official account has no page to open, so it shows its QR code inside the app instead.
    val opensWechat: Boolean = false,
)

internal val MoyuResources = listOf(
    ReadingResource(
        title = "Z-Library 中文站",
        address = "zh.zlib.bz",
        url = "https://zh.zlib.bz/",
        icon = Icons.AutoMirrored.Outlined.MenuBook,
    ),
    ReadingResource(
        title = "公众号 · ${MoyuAuthor.wechatName}",
        address = "微信",
        url = "",
        icon = Icons.Outlined.QrCode2,
        opensWechat = true,
    ),
)

/** The shelf-side teaser: a seal, a name, and the promise of a story behind the tap. */
@Composable
internal fun AuthorSettingsSection(onOpen: () -> Unit) {
    SettingsSection("关于作者") {
        val colors = MaterialTheme.colorScheme
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val background by animateColorAsState(
            if (pressed) colors.surfaceContainerLow else Color.Transparent,
            animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(120),
            label = "author press",
        )
        Row(
            Modifier.fillMaxWidth().background(background)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = "关于作者，${MoyuAuthor.name}" }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MoyuAppIcon(size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(MoyuAuthor.name, style = MaterialTheme.typography.titleMedium)
                Text("关于作者，与墨屿的来处", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp),
                tint = colors.onSurfaceVariant)
        }
    }
}

/** Two doors out of the app, dressed as part of the paper shelf rather than a link dump. */
@Composable
internal fun ResourceSettingsSection(onOpenWechat: () -> Unit) {
    val context = LocalContext.current
    val open: (ReadingResource) -> Unit = { resource ->
        if (resource.opensWechat) {
            onOpenWechat()
        } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resource.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isFailure) {
                Toast.makeText(context, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show()
            }
        }
    }
    SettingsSection("阅读资源") {
        MoyuResources.forEachIndexed { index, resource ->
            if (index > 0) PaperDivider(Modifier.padding(horizontal = 16.dp))
            ResourceRow(resource, open)
        }
    }
}

@Composable
private fun ResourceRow(resource: ReadingResource, onOpen: (ReadingResource) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) colors.surfaceContainerLow else Color.Transparent,
        animationSpec = if (LocalMoyuReduceMotion.current) snap() else tween(120),
        label = "resource press",
    )
    Row(
        Modifier.fillMaxWidth().background(background)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onOpen(resource) }
            .semantics(mergeDescendants = true) { contentDescription = "${resource.title}，${resource.address}" }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(colors.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(resource.icon, null, Modifier.size(20.dp), tint = colors.onTertiaryContainer)
        }
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(resource.title, style = MaterialTheme.typography.titleSmall)
            Text(resource.address, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.75f))
        }
        Icon(
            if (resource.opensWechat) Icons.Outlined.QrCode2 else Icons.AutoMirrored.Outlined.OpenInNew,
            null, Modifier.size(16.dp), tint = colors.primary.copy(alpha = 0.8f),
        )
    }
}

/** The author's own page: a seal, a short letter, and the motto the shelf opens with. */
@Composable
internal fun AuthorPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置", onBack)
            Text("关于作者", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        PaperDivider(Modifier.padding(horizontal = 20.dp))
        LazyColumn(
            Modifier.fillMaxSize().testTag("author-page"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "hero") {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MoyuAppIcon(size = 96.dp)
                    Text(MoyuAuthor.name, style = MaterialTheme.typography.titleLarge)
                    Text(MoyuAuthor.tagline, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            item(key = "letter") {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MoyuAuthor.paragraphs.forEach { paragraph ->
                            Text(
                                paragraph,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 27.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(MoyuAuthor.signature, Modifier.fillMaxWidth(), textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "wechat") { WechatQrCard() }
        }
    }
}

/** The follow card: the code sits on white so it still scans in dark mode. */
@Composable
private fun WechatQrCard() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.primaryContainer.copy(alpha = 0.55f)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("公众号", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                color = colors.onPrimaryContainer.copy(alpha = 0.7f))
            Text(MoyuAuthor.wechatName, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = colors.onPrimaryContainer)
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White) {
                Image(
                    painterResource(R.drawable.moyu_wechat_qr), "公众号二维码",
                    Modifier.padding(10.dp).size(148.dp),
                )
            }
            Text(MoyuAuthor.wechatHint, style = MaterialTheme.typography.bodySmall,
                color = colors.onPrimaryContainer.copy(alpha = 0.75f), textAlign = TextAlign.Center)
        }
    }
}

/** Same code in a modal, for the reading-resource row that has no URL to open. */
@Composable
internal fun WechatQrModal(onDismiss: () -> Unit) {
    PaperModal(
        title = MoyuAuthor.wechatName,
        onDismiss = onDismiss,
        actions = { PaperAction("关闭", onDismiss, Modifier.fillMaxWidth()) },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White) {
                Image(
                    painterResource(R.drawable.moyu_wechat_qr), "公众号二维码",
                    Modifier.padding(10.dp).size(184.dp),
                )
            }
            Text(MoyuAuthor.wechatHint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** The app's own icon, used wherever the author needs a face. */
@Composable
internal fun MoyuAppIcon(size: Dp) {
    Image(painterResource(R.drawable.ic_moyu), null, Modifier.size(size))
}
