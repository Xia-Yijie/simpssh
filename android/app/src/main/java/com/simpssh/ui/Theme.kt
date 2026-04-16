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
    // ---- Dark ----
    ThemePalette(
        name = "default", displayName = "普鲁士蓝",
        primary = Color(0xFF4A8FD9), onPrimary = Color.White,
        primaryContainer = Color(0xFF1A3A66), onPrimaryContainer = Color(0xFFD7E3FF),
        darkBackground = Color(0xFF0B1A33), darkSurface = Color(0xFF11244A),
        darkSurfaceVariant = Color(0xFF1A3661),
    ),
    ThemePalette(
        name = "dracula", displayName = "德古拉紫",
        primary = Color(0xFFBD93F9), onPrimary = Color(0xFF282A36),
        primaryContainer = Color(0xFF44475A), onPrimaryContainer = Color(0xFFF8F8F2),
        darkBackground = Color(0xFF282A36), darkSurface = Color(0xFF1E1F29),
        darkSurfaceVariant = Color(0xFF44475A),
    ),
    ThemePalette(
        name = "monokai", displayName = "莫诺凯绿",
        primary = Color(0xFFA6E22E), onPrimary = Color(0xFF272822),
        primaryContainer = Color(0xFF49483E), onPrimaryContainer = Color(0xFFF8F8F2),
        darkBackground = Color(0xFF272822), darkSurface = Color(0xFF1E1F1C),
        darkSurfaceVariant = Color(0xFF49483E),
    ),
    // ---- Light ----
    ThemePalette(
        name = "morninglight", displayName = "晨曦白",
        primary = Color(0xFF0969DA), onPrimary = Color.White,
        primaryContainer = Color(0xFFDDF4FF), onPrimaryContainer = Color(0xFF0A3069),
        darkBackground = Color(0xFFFAFBFC), darkSurface = Color(0xFFF6F8FA),
        darkSurfaceVariant = Color(0xFFEAEEF2),
    ),
    ThemePalette(
        name = "warmcream", displayName = "米色暖光",
        // Olive primary needs dark text — light cream onPrimary lands in
        // WCAG AA borderline at small sizes.
        primary = Color(0xFF859900), onPrimary = Color(0xFF002B36),
        primaryContainer = Color(0xFFEEE8D5), onPrimaryContainer = Color(0xFF586E75),
        darkBackground = Color(0xFFFDF6E3), darkSurface = Color(0xFFF7F0D8),
        darkSurfaceVariant = Color(0xFFEEE8D5),
    ),
    ThemePalette(
        name = "lightmist", displayName = "轻雾灰",
        primary = Color(0xFF5C6BC0), onPrimary = Color.White,
        primaryContainer = Color(0xFFE8EAF6), onPrimaryContainer = Color(0xFF1A237E),
        darkBackground = Color(0xFFF5F5F7), darkSurface = Color(0xFFEBEDF0),
        darkSurfaceVariant = Color(0xFFDFE2E8),
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

/// True when the palette's terminal background is actually a light colour
/// (e.g. the "晨曦白" / "米色暖光" / "轻雾灰" presets). The session view picks
/// a light Material scheme in that case so text + chrome stay readable.
val ThemePalette.isLight: Boolean
    get() = darkBackground.luminance() > 0.5f

/// The Material scheme to use for the always-themed Session view. Picks a
/// light scheme + the palette's bg as surface for light palettes; otherwise
/// the dark scheme.
internal fun sessionSchemeFor(p: ThemePalette): ColorScheme =
    if (p.isLight) {
        lightColorScheme(
            primary = p.primary,
            onPrimary = p.onPrimary,
            primaryContainer = p.primaryContainer,
            onPrimaryContainer = p.onPrimaryContainer,
            background = p.darkBackground,
            surface = p.darkSurface,
            surfaceVariant = p.darkSurfaceVariant,
        )
    } else {
        darkSchemeFor(p)
    }

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
): ThemePalette {
    // Force opaque alpha — Material's surface/background calculations and
    // toArgb-based JSON round-trip both assume fully opaque colours.
    val p = primary.copy(alpha = 1f)
    val c = primaryContainer.copy(alpha = 1f)
    val bg = darkBackground.copy(alpha = 1f)
    return ThemePalette(
        name = id,
        displayName = displayName,
        primary = p,
        onPrimary = bestForeground(p),
        primaryContainer = c,
        onPrimaryContainer = bestForeground(c),
        darkBackground = bg,
        darkSurface = bg.lighten(0.05f),
        darkSurfaceVariant = bg.lighten(0.10f),
        custom = true,
    )
}
