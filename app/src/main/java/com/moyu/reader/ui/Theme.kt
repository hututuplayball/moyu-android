package com.moyu.reader.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.moyu.reader.data.ReaderPreferences

internal val LocalMoyuReduceMotion = staticCompositionLocalOf { false }

private val PaperColors = lightColorScheme(
    primary = Color(0xFF526947),
    onPrimary = Color(0xFFFFFEFA),
    primaryContainer = Color(0xFFE6EBDE),
    onPrimaryContainer = Color(0xFF30412A),
    secondary = Color(0xFF69705F),
    onSecondary = Color(0xFFFFFEFA),
    secondaryContainer = Color(0xFFEBEDE4),
    onSecondaryContainer = Color(0xFF383F32),
    tertiary = Color(0xFF8B6D40),
    onTertiary = Color(0xFFFFFEFA),
    tertiaryContainer = Color(0xFFF2E6D2),
    onTertiaryContainer = Color(0xFF574321),
    background = Color(0xFFF8F7F3),
    onBackground = Color(0xFF292E29),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF292E29),
    surfaceVariant = Color(0xFFEEEFE7),
    onSurfaceVariant = Color(0xFF697064),
    surfaceTint = Color(0xFF526947),
    outline = Color(0xFF858A7F),
    outlineVariant = Color(0xFFDCDFD4),
    // Muted clay rather than stock red: destructive actions sit on the same paper palette.
    error = Color(0xFF9C5B49),
    onError = Color(0xFFFFF7F3),
    errorContainer = Color(0xFFF3E3DA),
    onErrorContainer = Color(0xFF66372A),
    inverseSurface = Color(0xFF30352F),
    inverseOnSurface = Color(0xFFF5F5EF),
    inversePrimary = Color(0xFFBBCFA4),
    surfaceDim = Color(0xFFE1E2D9),
    surfaceBright = Color(0xFFFFFEFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F0),
    surfaceContainer = Color(0xFFF0F1E9),
    surfaceContainerHigh = Color(0xFFEAEBE2),
    surfaceContainerHighest = Color(0xFFE4E6DC),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFFBBCFA4),
    onPrimary = Color(0xFF273A21),
    primaryContainer = Color(0xFF35472E),
    onPrimaryContainer = Color(0xFFDAE8CC),
    secondary = Color(0xFFC1C9B5),
    onSecondary = Color(0xFF30382A),
    secondaryContainer = Color(0xFF3B4435),
    onSecondaryContainer = Color(0xFFE0E6D7),
    tertiary = Color(0xFFE0BF89),
    onTertiary = Color(0xFF443315),
    tertiaryContainer = Color(0xFF574425),
    onTertiaryContainer = Color(0xFFF7DDB2),
    background = Color(0xFF191A19),
    onBackground = Color(0xFFE6E8DF),
    surface = Color(0xFF232522),
    onSurface = Color(0xFFE6E8DF),
    surfaceVariant = Color(0xFF32382E),
    onSurfaceVariant = Color(0xFFB1B8A8),
    surfaceTint = Color(0xFFBBCFA4),
    outline = Color(0xFF858E7C),
    outlineVariant = Color(0xFF3C4238),
    error = Color(0xFFD9A594),
    onError = Color(0xFF42271F),
    errorContainer = Color(0xFF4A2E26),
    onErrorContainer = Color(0xFFF4D8CF),
    inverseSurface = Color(0xFFE6E8DF),
    inverseOnSurface = Color(0xFF30352F),
    inversePrimary = Color(0xFF526947),
    surfaceDim = Color(0xFF191A19),
    surfaceBright = Color(0xFF373B34),
    surfaceContainerLowest = Color(0xFF141613),
    surfaceContainerLow = Color(0xFF20231E),
    surfaceContainer = Color(0xFF272B24),
    surfaceContainerHigh = Color(0xFF30352C),
    surfaceContainerHighest = Color(0xFF393E35),
)

private val MoyuTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 46.sp,
        letterSpacing = 2.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 25.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun MoyuTheme(preferences: ReaderPreferences, content: @Composable () -> Unit) {
    val dark = when (preferences.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalMoyuReduceMotion provides !ValueAnimator.areAnimatorsEnabled(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) NightColors else PaperColors,
            typography = MoyuTypography,
            content = content,
        )
    }
}
