package st.kiwifarms.sneedroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Sneedroid theme. Drives both the Material3 [MaterialTheme] (for stock components) and
 * the extended [SneedColors] tokens. [dark] and [accentHue] will be wired to user
 * settings (DataStore) in Phase 5; defaults match the design pack's TWEAK_DEFAULTS.
 */
@Composable
fun SneedroidTheme(
    dark: Boolean = true,
    accentHue: Int = 142,
    content: @Composable () -> Unit,
) {
    val accent = accentColor(accentHue, dark)
    val colors = if (dark) darkColors(accent) else lightColors(accent)

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentOn,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.text2,
            error = colors.danger,
            outline = colors.border,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentOn,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.text2,
            error = colors.danger,
            outline = colors.border,
        )
    }

    CompositionLocalProvider(LocalSneedColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Shorthand for the extended token palette: `SneedTheme.colors.surface2`. */
object SneedTheme {
    val colors: SneedColors
        @Composable get() = LocalSneedColors.current
}
