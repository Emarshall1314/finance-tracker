package ca.emarshall.finance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A calm, Apple-inspired look: grouped grey background, white (or near-black) cards, one indigo accent,
 * soft gradients for the hero cards. A fixed palette rather than wallpaper colours, so it looks the same
 * everywhere.
 */
@Immutable
data class Palette(
    val background: Color,
    val card: Color,
    val cardSecondary: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val accent: Color,
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val heroGradient: Brush,
    val investGradient: Brush,
    val calmGradient: Brush,
    val chart: List<Color>,
)

private val LightPalette = Palette(
    background = Color(0xFFF2F2F7),
    card = Color.White,
    cardSecondary = Color(0xFFF2F2F7),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x1F3C3C43),
    accent = Color(0xFF5856D6),
    positive = Color(0xFF248A3D),
    negative = Color(0xFFD70015),
    warning = Color(0xFFC93400),
    heroGradient = Brush.linearGradient(listOf(Color(0xFF5E5CE6), Color(0xFF8E5CF7), Color(0xFFD16BA5))),
    investGradient = Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF30B0C7), Color(0xFF34C77B))),
    calmGradient = Brush.linearGradient(listOf(Color(0xFFEEF0FF), Color(0xFFF7EEFB))),
    chart = listOf(Color(0xFF5E5CE6), Color(0xFFFF9F0A), Color(0xFF30B0C7), Color(0xFFFF375F), Color(0xFF34C759), Color(0xFFBF5AF2)),
)

private val DarkPalette = Palette(
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardSecondary = Color(0xFF2C2C2E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x3D545458),
    accent = Color(0xFF7D7AFF),
    positive = Color(0xFF30D158),
    negative = Color(0xFFFF6961),
    warning = Color(0xFFFFB340),
    heroGradient = Brush.linearGradient(listOf(Color(0xFF3634A3), Color(0xFF6E3CBC), Color(0xFFA2457E))),
    investGradient = Brush.linearGradient(listOf(Color(0xFF0060C7), Color(0xFF1E7F8E), Color(0xFF1F7A4C))),
    calmGradient = Brush.linearGradient(listOf(Color(0xFF1C1C2E), Color(0xFF261C2C))),
    chart = listOf(Color(0xFF7D7AFF), Color(0xFFFFB340), Color(0xFF5AC8DE), Color(0xFFFF6482), Color(0xFF30D158), Color(0xFFDA8FFF)),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

private fun typography(): Typography {
    val t = Typography()
    return t.copy(
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = Color.White, primaryContainer = p.accent.copy(alpha = 0.14f), onPrimaryContainer = p.accent,
        secondary = p.accent, secondaryContainer = p.accent.copy(alpha = 0.12f), onSecondaryContainer = p.label,
        tertiary = Color(0xFF30B0C7),
        background = p.background, onBackground = p.label,
        surface = p.background, onSurface = p.label, surfaceVariant = p.cardSecondary, onSurfaceVariant = p.secondaryLabel,
        surfaceContainer = p.card, surfaceContainerLow = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.cardSecondary,
        surfaceContainerLowest = p.card,
        outline = p.separator, outlineVariant = p.separator,
        error = p.negative, errorContainer = p.negative.copy(alpha = 0.12f), onErrorContainer = p.negative,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

object Fin {
    val palette: Palette @Composable get() = LocalPalette.current
}

@Composable
fun incomeColor(): Color = LocalPalette.current.positive

@Composable
fun spendColor(): Color = LocalPalette.current.negative
