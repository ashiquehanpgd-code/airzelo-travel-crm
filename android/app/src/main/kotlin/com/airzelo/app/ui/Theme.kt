package com.airzelo.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AirZelo design tokens: the desktop app's palette (core/static/app.css), so the phone and the desktop look like one
 * product. Light and dark themes follow the phone's setting.
 */
@Immutable
data class AzColors(
    val bg: Color, val surface: Color, val surface2: Color, val surface3: Color, val border: Color, val borderStrong: Color,
    val text: Color, val text2: Color, val muted: Color,
    val navy: Color, val navy2: Color, val royal: Color, val royal50: Color,
    val cyan: Color, val cyan50: Color, val success: Color, val success50: Color, val warn: Color, val warn50: Color,
    val danger: Color, val danger50: Color, val violet: Color, val violet50: Color,
    val dark: Boolean,
) {
    /** The brand gradient used for hero cards and the lock screen. */
    val hero: Brush get() = Brush.linearGradient(listOf(navy, navy2, Color(0xFF1B3A9A)))
}

val LightAz = AzColors(
    bg = Color(0xFFF3F6FB), surface = Color.White, surface2 = Color(0xFFF7F9FD), surface3 = Color(0xFFEEF2F9),
    border = Color(0xFFE2E8F2), borderStrong = Color(0xFFCDD6E6),
    text = Color(0xFF0D1833), text2 = Color(0xFF33415F), muted = Color(0xFF64738F),
    navy = Color(0xFF0A1A3F), navy2 = Color(0xFF0F2457), royal = Color(0xFF2350E6), royal50 = Color(0xFFEAF0FF),
    cyan = Color(0xFF19B4D8), cyan50 = Color(0xFFE3F7FC), success = Color(0xFF0E9F6B), success50 = Color(0xFFE3F6EE),
    warn = Color(0xFFC27A06), warn50 = Color(0xFFFDF3DF), danger = Color(0xFFD33447), danger50 = Color(0xFFFDE9EC),
    violet = Color(0xFF6B4CE6), violet50 = Color(0xFFEFEBFE), dark = false,
)

val DarkAz = AzColors(
    bg = Color(0xFF070D1D), surface = Color(0xFF0D1630), surface2 = Color(0xFF111C3A), surface3 = Color(0xFF16234A),
    border = Color(0xFF1E2A4C), borderStrong = Color(0xFF2B3A63),
    text = Color(0xFFE7EDFB), text2 = Color(0xFFC3CDE6), muted = Color(0xFF8B98B8),
    navy = Color(0xFF0A1A3F), navy2 = Color(0xFF0F2457), royal = Color(0xFF4C74FF), royal50 = Color(0xFF15214A),
    cyan = Color(0xFF3BCBEB), cyan50 = Color(0xFF0D2A3A), success = Color(0xFF2FC48C), success50 = Color(0xFF0C2A22),
    warn = Color(0xFFF0A93A), warn50 = Color(0xFF2E2210), danger = Color(0xFFFF6476), danger50 = Color(0xFF33141B),
    violet = Color(0xFF9B85FF), violet50 = Color(0xFF1F1A45), dark = true,
)

val LocalAz = staticCompositionLocalOf { LightAz }

/** Shortcut: `Az.colors.royal`. */
object Az {
    val colors: AzColors @Composable get() = LocalAz.current
    val cardShape = RoundedCornerShape(18.dp)
    val fieldShape = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(50)
}

private val AzTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

@Composable
fun AirZeloTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val az = if (dark) DarkAz else LightAz
    val scheme = if (dark) darkColorScheme(primary = az.royal, onPrimary = Color.White, secondary = az.cyan, background = az.bg,
        surface = az.surface, surfaceVariant = az.surface2, onSurface = az.text, onSurfaceVariant = az.muted, outline = az.borderStrong,
        outlineVariant = az.border, error = az.danger, primaryContainer = az.royal50, onPrimaryContainer = az.text,
        surfaceContainer = az.surface, surfaceContainerHigh = az.surface2, surfaceContainerHighest = az.surface3, surfaceContainerLow = az.surface)
    else lightColorScheme(primary = az.royal, onPrimary = Color.White, secondary = az.cyan, background = az.bg,
        surface = az.surface, surfaceVariant = az.surface2, onSurface = az.text, onSurfaceVariant = az.muted, outline = az.borderStrong,
        outlineVariant = az.border, error = az.danger, primaryContainer = az.royal50, onPrimaryContainer = az.text,
        surfaceContainer = az.surface, surfaceContainerHigh = az.surface2, surfaceContainerHighest = az.surface3, surfaceContainerLow = az.surface)
    CompositionLocalProvider(LocalAz provides az) {
        MaterialTheme(colorScheme = scheme, typography = AzTypography,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
            content = content)
    }
}
