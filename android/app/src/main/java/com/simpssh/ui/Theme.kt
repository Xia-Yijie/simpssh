package com.simpssh.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/// A user-pickable palette. Defines the primary colour family for both
/// the (mostly light) home/edit screens and the (always dark) session view.
data class ThemePalette(
    val name: String,
    val displayName: String,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val darkBackground: Color = Color(0xFF0B0E12),
    val darkSurface: Color = Color(0xFF12161C),
    val darkSurfaceVariant: Color = Color(0xFF1A2027),
    /// True if this is a user-created custom palette (rendered separately
    /// in Settings and persistable / deletable).
    val custom: Boolean = false,
)

val BuiltInPalettes: List<ThemePalette> = listOf(
    ThemePalette(
        name = "default", displayName = "默认蓝",
        primary = Color(0xFF005FBF), onPrimary = Color.White,
        primaryContainer = Color(0xFFD7E3FF), onPrimaryContainer = Color(0xFF001A41),
    ),
    ThemePalette(
        name = "dracula", displayName = "德古拉紫",
        primary = Color(0xFFBD93F9), onPrimary = Color(0xFF282A36),
        primaryContainer = Color(0xFF44475A), onPrimaryContainer = Color(0xFFF8F8F2),
        darkBackground = Color(0xFF282A36), darkSurface = Color(0xFF1E1F29),
        darkSurfaceVariant = Color(0xFF44475A),
    ),
    ThemePalette(
        name = "tokyonight", displayName = "东京之夜",
        primary = Color(0xFF7AA2F7), onPrimary = Color(0xFF1A1B26),
        primaryContainer = Color(0xFF24283B), onPrimaryContainer = Color(0xFFC0CAF5),
        darkBackground = Color(0xFF1A1B26), darkSurface = Color(0xFF24283B),
        darkSurfaceVariant = Color(0xFF2F334D),
    ),
    ThemePalette(
        name = "nord", displayName = "北欧霜",
        primary = Color(0xFF88C0D0), onPrimary = Color(0xFF2E3440),
        primaryContainer = Color(0xFF4C566A), onPrimaryContainer = Color(0xFFECEFF4),
        darkBackground = Color(0xFF2E3440), darkSurface = Color(0xFF3B4252),
        darkSurfaceVariant = Color(0xFF434C5E),
    ),
    ThemePalette(
        name = "gruvbox", displayName = "复古暖橙",
        primary = Color(0xFFFE8019), onPrimary = Color(0xFF282828),
        primaryContainer = Color(0xFF504945), onPrimaryContainer = Color(0xFFEBDBB2),
        darkBackground = Color(0xFF282828), darkSurface = Color(0xFF3C3836),
        darkSurfaceVariant = Color(0xFF504945),
    ),
    ThemePalette(
        name = "onedark", displayName = "原子暗",
        primary = Color(0xFF61AFEF), onPrimary = Color(0xFF21252B),
        primaryContainer = Color(0xFF3E4451), onPrimaryContainer = Color(0xFFABB2BF),
        darkBackground = Color(0xFF282C34), darkSurface = Color(0xFF21252B),
        darkSurfaceVariant = Color(0xFF3E4451),
    ),
    ThemePalette(
        name = "monokai", displayName = "莫诺凯绿",
        primary = Color(0xFFA6E22E), onPrimary = Color(0xFF272822),
        primaryContainer = Color(0xFF49483E), onPrimaryContainer = Color(0xFFF8F8F2),
        darkBackground = Color(0xFF272822), darkSurface = Color(0xFF1E1F1C),
        darkSurfaceVariant = Color(0xFF49483E),
    ),
    ThemePalette(
        name = "solarized", displayName = "日冕暖黄",
        primary = Color(0xFFB58900), onPrimary = Color(0xFF002B36),
        primaryContainer = Color(0xFF073642), onPrimaryContainer = Color(0xFFEEE8D5),
        darkBackground = Color(0xFF002B36), darkSurface = Color(0xFF073642),
        darkSurfaceVariant = Color(0xFF586E75),
    ),
)

/// Resolve a palette by name across both built-in and custom lists,
/// falling back to the first built-in if no match.
fun resolvePalette(name: String, custom: List<ThemePalette> = emptyList()): ThemePalette =
    BuiltInPalettes.firstOrNull { it.name == name }
        ?: custom.firstOrNull { it.name == name }
        ?: BuiltInPalettes.first()

val LocalPalette = staticCompositionLocalOf { BuiltInPalettes.first() }

private fun lightSchemeFor(p: ThemePalette): ColorScheme = lightColorScheme(
    primary = p.primary,
    onPrimary = p.onPrimary,
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
)

internal fun darkSchemeFor(p: ThemePalette): ColorScheme = darkColorScheme(
    primary = p.primary,
    onPrimary = p.onPrimary,
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
    background = p.darkBackground,
    surface = p.darkSurface,
    surfaceVariant = p.darkSurfaceVariant,
    onSurface = Color(0xFFE6E8EB),
    onSurfaceVariant = Color(0xFFB7BEC9),
    outline = Color(0xFF40474F),
)

@Composable
fun SimpsshTheme(
    palette: ThemePalette = BuiltInPalettes.first(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = lightSchemeFor(palette), content = content)
    }
}

// ---- helpers shared with the custom-palette editor ------------------------

/// Black or white, whichever sits more readably on top of `bg`.
fun bestForeground(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color.Black else Color.White

/// Blend the colour with white by `amount` (0f = unchanged, 1f = white).
fun Color.lighten(amount: Float): Color = Color(
    red = (red + (1f - red) * amount).coerceIn(0f, 1f),
    green = (green + (1f - green) * amount).coerceIn(0f, 1f),
    blue = (blue + (1f - blue) * amount).coerceIn(0f, 1f),
    alpha = alpha,
)

/// Build a complete ThemePalette from the few colours the user actually picks.
/// Sets onPrimary / onPrimaryContainer for contrast and derives the surface
/// shades by lightening the background.
fun customPalette(
    id: String,
    displayName: String,
    primary: Color,
    primaryContainer: Color,
    darkBackground: Color,
): ThemePalette = ThemePalette(
    name = id,
    displayName = displayName,
    primary = primary,
    onPrimary = bestForeground(primary),
    primaryContainer = primaryContainer,
    onPrimaryContainer = bestForeground(primaryContainer),
    darkBackground = darkBackground,
    darkSurface = darkBackground.lighten(0.05f),
    darkSurfaceVariant = darkBackground.lighten(0.10f),
    custom = true,
)
