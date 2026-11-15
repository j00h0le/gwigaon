package com.coral.gwigaon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect design tokens, connect-s values (research/findings/pleos-figma-kit.md). Names follow the kit:
 * background/surface/text/button/field/divider/informative. Regulation colors stay unused (telltales only).
 */
@Immutable
data class GwigaonColors(
    val backgroundPrimary: Color,
    val backgroundSecondary: Color,
    val backgroundPopup: Color,
    val surfaceBasic: Color,
    val surfaceHigh: Color,
    val surfaceAccent: Color,
    /** Text colors (kit text/dark): primary, secondary (84 %), tertiary (64 %), quaternary (32 %, disabled only). */
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,
    /** text/light/primary: text on filled buttons. */
    val textOnFilled: Color,
    val buttonFilled: Color,
    val buttonFilledPressed: Color,
    val buttonFilledDisabled: Color,
    val buttonBasic: Color,
    val buttonBasicPressed: Color,
    val fieldBackground: Color,
    val dividerSecondary: Color,
    val dividerTertiary: Color,
    /** informative/active (green): safe arrival. */
    val active: Color,
    /** informative/positive (blue): selection and links. */
    val positive: Color,
    /** informative/negative (red): SOS and errors. */
    val negative: Color,
    /** color/informative/error (amber): caution accents only, never as text on light backgrounds (low contrast). */
    val caution: Color,
    /** Text on [negative] (SOS red): ink in both themes, ≥ 5:1 where white on red is only ~3.6:1. */
    val textOnRed: Color,
)

private val Ink = Color(0xFF131417)

val DayColors = GwigaonColors(
    backgroundPrimary = Color(0xFFF7F8FA),
    backgroundSecondary = Color(0xFFFFFFFF),
    backgroundPopup = Color(0xFFFFFFFF),
    surfaceBasic = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2),
    surfaceAccent = Color(0xFFE0E1E6),
    textPrimary = Ink,
    textSecondary = Ink.copy(alpha = 0.84f),
    textTertiary = Ink.copy(alpha = 0.64f),
    textQuaternary = Ink.copy(alpha = 0.32f),
    textOnFilled = Color.White,
    buttonFilled = Color(0xFF44464E),
    buttonFilledPressed = Color(0xFF313236),
    buttonFilledDisabled = Ink.copy(alpha = 0.05f),
    buttonBasic = Ink.copy(alpha = 0.05f),
    buttonBasicPressed = Ink.copy(alpha = 0.10f),
    fieldBackground = Color(0xFFFFFFFF),
    dividerSecondary = Ink.copy(alpha = 0.10f),
    dividerTertiary = Ink.copy(alpha = 0.20f),
    active = Color(0xFF02C265),
    positive = Color(0xFF0064FF),
    negative = Color(0xFFFE3D16),
    caution = Color(0xFFFFA202),
    textOnRed = Ink,
)

val NightColors = GwigaonColors(
    backgroundPrimary = Color(0xFF1D1E21),
    backgroundSecondary = Color(0xFF131417),
    backgroundPopup = Color(0xFF313236),
    surfaceBasic = Color(0xFF1D1E21),
    surfaceHigh = Color(0xFF313236),
    surfaceAccent = Color(0xFF44464E),
    textPrimary = Color.White,
    textSecondary = Color.White.copy(alpha = 0.84f),
    textTertiary = Color.White.copy(alpha = 0.64f),
    textQuaternary = Color.White.copy(alpha = 0.32f),
    textOnFilled = Ink,
    buttonFilled = Color(0xFFE0E1E6),
    buttonFilledPressed = Color(0xFFC7C9CE),
    buttonFilledDisabled = Color.White.copy(alpha = 0.05f),
    buttonBasic = Color.White.copy(alpha = 0.08f),
    buttonBasicPressed = Color.White.copy(alpha = 0.14f),
    fieldBackground = Ink,
    dividerSecondary = Color.White.copy(alpha = 0.10f),
    dividerTertiary = Color.White.copy(alpha = 0.20f),
    active = Color(0xFF00E074),
    positive = Color(0xFF0082FF),
    negative = Color(0xFFFF4C28),
    caution = Color(0xFFFFA202),
    textOnRed = Ink,
)

/**
 * Pleos type scale (sp, size/line height). Asta Sans is the kit's font; until its files are added
 * the platform font renders Hangul.
 */
object GwigaonType {
    private fun style(size: Int, line: Int, weight: FontWeight) =
        TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

    val headlineSmall = style(48, 64, FontWeight.Bold)
    val titleLarge = style(40, 52, FontWeight.Bold)
    val titleMedium = style(36, 44, FontWeight.Bold)
    val titleSmall = style(32, 40, FontWeight.Bold)
    val bodyLargeStrong = style(30, 38, FontWeight.Bold)
    val bodyLarge = style(30, 38, FontWeight.Normal)
    val bodyMediumStrong = style(28, 36, FontWeight.Bold)
    val bodyMedium = style(28, 36, FontWeight.Normal)
    val bodySmall = style(26, 34, FontWeight.Normal)
    val labelMedium = style(24, 28, FontWeight.Normal)
}

/** Component sizes from the kit (connect-s, dp). Parked UI: nothing below Small (64), 24 dp apart. */
object GwigaonDimens {
    val buttonLarge = 100.dp
    val buttonMedium = 80.dp
    val buttonSmall = 64.dp
    val radiusLarge = 32.dp
    val radiusMedium = 24.dp
    val radiusSmall = 16.dp
    val fieldHeight = 100.dp
    val fieldBorder = 3.dp
    val gap = 24.dp
    val sectionGap = 48.dp
    val screenPadding = 40.dp
}

private val LocalColors = staticCompositionLocalOf { DayColors }

object Gwigaon {
    val colors: GwigaonColors
        @Composable @ReadOnlyComposable
        get() = LocalColors.current
}

/** Day/night follows the vehicle's UI mode (system dark theme). */
@Composable
fun GwigaonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val c = if (darkTheme) NightColors else DayColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.buttonFilled, onPrimary = c.textOnFilled, background = c.backgroundPrimary,
            onBackground = c.textPrimary, surface = c.surfaceBasic, onSurface = c.textPrimary, error = c.negative,
        )
    } else {
        lightColorScheme(
            primary = c.buttonFilled, onPrimary = c.textOnFilled, background = c.backgroundPrimary,
            onBackground = c.textPrimary, surface = c.surfaceBasic, onSurface = c.textPrimary, error = c.negative,
        )
    }
    val typography = Typography(
        headlineSmall = GwigaonType.headlineSmall,
        titleLarge = GwigaonType.titleLarge,
        titleMedium = GwigaonType.titleMedium,
        titleSmall = GwigaonType.titleSmall,
        bodyLarge = GwigaonType.bodyLarge,
        bodyMedium = GwigaonType.bodyMedium,
        bodySmall = GwigaonType.bodySmall,
        labelLarge = GwigaonType.bodyMediumStrong,
        labelMedium = GwigaonType.labelMedium,
    )
    CompositionLocalProvider(
        LocalColors provides c,
        LocalPalette provides if (darkTheme) NightPalette else DayPalette,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
