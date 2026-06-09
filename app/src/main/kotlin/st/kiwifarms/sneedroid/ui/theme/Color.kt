package st.kiwifarms.sneedroid.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The full token palette from the design pack (docs/04-ui-spec.md). Material3's
 * ColorScheme can't express all of these (surface-2/-3, text-2/-3, quote bar, …), so we
 * carry them in a dedicated [SneedColors] exposed via [LocalSneedColors].
 */
@Immutable
data class SneedColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val inputBg: Color,
    val border: Color,
    val border2: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentOn: Color,
    val danger: Color,
    val codeBg: Color,
    val quoteBar: Color,
)

fun darkColors(accent: Color) = SneedColors(
    bg = Color(0xFF21262C),
    surface = Color(0xFF2D3239),
    surface2 = Color(0xFF363C44),
    surface3 = Color(0xFF3C434C),
    inputBg = Color(0xFF262B31),
    border = Color(0xFF3A414A),
    border2 = Color(0xFF2C3138),
    text = Color(0xFFE7E9EC),
    text2 = Color(0xFFA8AFB7),
    text3 = Color(0xFF79818A),
    accent = accent,
    accentOn = Color(0xFF10130F),
    danger = Color(0xFFDF8B91),
    codeBg = Color(0xFF1A1E23),
    quoteBar = Color(0xFF4A525C),
)

fun lightColors(accent: Color) = SneedColors(
    bg = Color(0xFFE9EDF0),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEEF1F4),
    surface3 = Color(0xFFE4E9EE),
    inputBg = Color(0xFFF0F3F6),
    border = Color(0xFFD6DCE2),
    border2 = Color(0xFFE4E9EE),
    text = Color(0xFF1B2025),
    text2 = Color(0xFF5A636E),
    text3 = Color(0xFF8B939D),
    accent = accent,
    accentOn = Color(0xFFFFFFFF),
    danger = Color(0xFFC0454F),
    codeBg = Color(0xFFF0F2F4),
    quoteBar = Color(0xFFCFD6DD),
)

/**
 * Accent per hue. The design uses oklch(L C hue); proper oklch→sRGB conversion is a
 * Phase 5 task. These sRGB approximations cover the four design presets for now.
 */
fun accentColor(hue: Int, dark: Boolean): Color = when (hue) {
    250 -> if (dark) Color(0xFF6F93D8) else Color(0xFF3A5FB0) // blue
    60 -> if (dark) Color(0xFFB79A3F) else Color(0xFF8A6D18)  // amber
    350 -> if (dark) Color(0xFFCF7596) else Color(0xFFB03A5F) // pink
    else -> if (dark) Color(0xFF6BA65E) else Color(0xFF3F7D3A) // 142 green (default)
}

val LocalSneedColors = staticCompositionLocalOf<SneedColors> {
    error("SneedColors not provided — wrap content in SneedroidTheme")
}
