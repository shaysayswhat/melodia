package com.melodia.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object MelodiaColors {
    val Violet = Color(0xFF8B5CF6)
    val VioletDeep = Color(0xFF6D3FE0)
    val Pink = Color(0xFFEC4899)
    val Cyan = Color(0xFF22D3EE)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val Background = Color(0xFF0D0D12)
    val Surface = Color(0xFF16161D)
    val SurfaceVariant = Color(0xFF1F1F29)
    val SurfaceHigh = Color(0xFF262633)
    val Outline = Color(0xFF3A3A48)
    val Text = Color(0xFFECECF1)
    val TextDim = Color(0xFFA6A6B5)

    val gradient: Brush get() = Brush.linearGradient(listOf(VioletDeep, Violet, Pink))

    /** A stable two-colour cover for a song, picked from its id. */
    fun coverFor(seed: String): Brush {
        val palettes = listOf(
            listOf(Color(0xFF6D3FE0), Color(0xFFEC4899)),
            listOf(Color(0xFF0EA5E9), Color(0xFF8B5CF6)),
            listOf(Color(0xFFF97316), Color(0xFFEC4899)),
            listOf(Color(0xFF10B981), Color(0xFF0EA5E9)),
            listOf(Color(0xFFEAB308), Color(0xFFF97316)),
            listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE)),
            listOf(Color(0xFFDB2777), Color(0xFF7C3AED)),
        )
        val index = (seed.hashCode() and Int.MAX_VALUE) % palettes.size
        return Brush.linearGradient(palettes[index])
    }
}

private val colors = darkColorScheme(
    primary = MelodiaColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2D1B69),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = MelodiaColors.Pink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A1532),
    onSecondaryContainer = Color(0xFFFFD8E8),
    tertiary = MelodiaColors.Cyan,
    onTertiary = Color(0xFF00363D),
    background = MelodiaColors.Background,
    onBackground = MelodiaColors.Text,
    surface = MelodiaColors.Surface,
    onSurface = MelodiaColors.Text,
    surfaceVariant = MelodiaColors.SurfaceVariant,
    onSurfaceVariant = MelodiaColors.TextDim,
    surfaceContainerLowest = MelodiaColors.Background,
    surfaceContainerLow = Color(0xFF131319),
    surfaceContainer = MelodiaColors.Surface,
    surfaceContainerHigh = MelodiaColors.SurfaceVariant,
    surfaceContainerHighest = MelodiaColors.SurfaceHigh,
    outline = MelodiaColors.Outline,
    outlineVariant = Color(0xFF2A2A36),
    error = MelodiaColors.Red,
    onError = Color(0xFF450A0A),
)

private val base = Typography()

private val typography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun MelodiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
