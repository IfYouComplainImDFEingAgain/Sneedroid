package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Deterministic per-username colors, ported from the design pack's colorForName. */
private val avatarColors = listOf(
    0xFFC98A4B, 0xFF5A93C4, 0xFFB56FAE, 0xFF5FAE8F, 0xFFC46A6A,
    0xFF8A86C9, 0xFFC4A64F, 0xFF5AA6B8, 0xFFA98F6B, 0xFF9BB05A,
).map { Color(it) }

fun colorForName(name: String): Color {
    var h = 0
    for (c in name) h = (h * 31 + c.code) and 0x7FFFFFFF
    return avatarColors[h % avatarColors.size]
}

private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Format an epoch-seconds timestamp as HH:mm. */
fun formatTime(epochSeconds: Long): String =
    if (epochSeconds <= 0) "" else hhmm.format(Date(epochSeconds * 1000))

/** Two uppercase initials for an avatar fallback. */
fun initials(name: String): String =
    name.trim().take(2).uppercase().ifEmpty { "?" }
