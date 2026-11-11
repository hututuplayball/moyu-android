package com.moyu.reader.ui.reader

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Everything the reader does to the window instead of to its own layout: the reading brightness,
 * the screen-on flag, and the system bars.
 *
 * All three are borrowed and given back: leaving the reader (or changing a setting) must restore
 * what the app had before, or the next screen inherits a screen that never dims.
 */
@Composable
internal fun ReaderWindowEffects(
    activity: Activity,
    view: View,
    brightness: Float,
    keepScreenOn: Boolean,
    chrome: Boolean,
    light: Boolean,
) {
    // Reading uses its own brightness; leaving the reader hands the window back to the system.
    DisposableEffect(brightness) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        val params = window.attributes
        params.screenBrightness = if (brightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else brightness
        window.attributes = params
        onDispose {
            val restored = window.attributes
            restored.screenBrightness = previous
            window.attributes = restored
        }
    }
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(chrome, light) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
