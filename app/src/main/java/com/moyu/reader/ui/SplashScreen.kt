package com.moyu.reader.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyu.reader.R
import kotlinx.coroutines.delay

/**
 * Launch screen: the app mark and wordmark settle in as soon as the page composes, without waiting
 * for the shelf. The skip pill counts two seconds down to zero, holds that zero for a beat, then
 * the shelf fades in.
 */
private const val COUNTDOWN_SECONDS = 2

@Composable
fun SplashScreen(
    ready: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val still = LocalMoyuReduceMotion.current
    // Draw the mark immediately: waiting for the database left a blank paper page on screen.
    var shown by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        // 2 -> 1 -> 0, one second each, so the final zero is also held for a full second.
        for (value in COUNTDOWN_SECONDS downTo 0) {
            seconds = value
            delay(1_000)
        }
        onFinished()
    }

    val ease = FastOutSlowInEasing
    // The mark is opaque on the very first frame; fading it in made the paper page read as a blank
    // white flash for the first half second of every cold start.
    val iconScale by animateFloatAsState(if (shown || still) 1f else 0.96f,
        tween(if (still) 0 else 480, easing = ease), label = "splash icon scale")
    val titleAlpha by animateFloatAsState(if (shown || still) 1f else 0f,
        tween(if (still) 0 else 340, easing = ease), label = "splash title")
    val titleLift by animateFloatAsState(if (shown || still) 0f else 10f,
        tween(if (still) 0 else 360, easing = ease), label = "splash title lift")
    val ruleWidth by animateFloatAsState(if (shown || still) 46f else 0f,
        tween(if (still) 0 else 480, delayMillis = if (still) 0 else 140, easing = ease), label = "splash rule")
    val taglineAlpha by animateFloatAsState(if (shown || still) 1f else 0f,
        tween(if (still) 0 else 380, delayMillis = if (still) 0 else 220, easing = ease), label = "splash tagline")

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            // Swallow taps so the library underneath cannot be triggered through the launch screen.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painterResource(R.drawable.ic_moyu),
                contentDescription = null,
                modifier = Modifier.size(76.dp).graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "墨屿",
                // Letter spacing also trails the last glyph, so nudge the mark back to true centre.
                modifier = Modifier.padding(start = 10.dp).graphicsLayer {
                    alpha = titleAlpha
                    translationY = titleLift.dp.toPx()
                },
                color = colors.onBackground,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 40.sp,
                letterSpacing = 10.sp,
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.width(ruleWidth.dp).height(1.dp).background(colors.primary.copy(alpha = 0.34f)))
            Spacer(Modifier.height(14.dp))
            Text(
                "方寸之间，自有山海。",
                modifier = Modifier.graphicsLayer { alpha = taglineAlpha },
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
        }

        Box(
            Modifier.align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(50))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(50))
                // The "跳过" label is a magnet for ad-skipper accessibility services, which would
                // close the launch screen instantly. Tap handling stays local and the pill carries
                // no clickable node for them to press.
                .clearAndSetSemantics { }
                .pointerInput(Unit) { detectTapGestures { onFinished() } }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "跳过 $seconds",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.sp,
            )
        }
    }
}
