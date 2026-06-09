package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import st.kiwifarms.sneedroid.data.CustomMacro
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

private val BB_COLORS = listOf(
    "#789922", // 4chan greentext
    "#e04f4f", // red
    "#e0883c", // orange
    "#e0c84a", // yellow
    "#5fae6b", // green
    "#4fb0c4", // cyan
    "#5a93c4", // blue
    "#8a6fd4", // indigo
    "#b56fae", // purple
    "#e08aa8", // pink
    "#9aa0a6", // gray
    "#ffffff", // white
    "#000000", // black
)
private val BB_SIZES = listOf("S" to 3, "L" to 7, "XL" to 190)
private val RAINBOW = listOf(
    Color(0xFFE04F4F), Color(0xFFE0C84A), Color(0xFF5FAE6B), Color(0xFF4FB0C4),
    Color(0xFF5A93C4), Color(0xFFB56FAE), Color(0xFFE04F4F),
)
private const val MAX_LEN = 1023

/**
 * The message composer: BBCode toolbar, source/preview toggle, growing input, send.
 * Text state is hoisted so callers can inject mentions/quotes and drive edit mode.
 */
@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    editing: Boolean,
    onSend: () -> Unit,
    onCancelEdit: () -> Unit,
    onUploadImage: (() -> Unit)? = null,
    recentEmotes: List<String> = emptyList(),
    onEmoteUsed: (String) -> Unit = {},
    macros: List<CustomMacro> = emptyList(),
    onMacroPick: (CustomMacro) -> Unit = {},
    onMacroAdd: () -> Unit = {},
    onMacroEdit: (CustomMacro) -> Unit = {},
) {
    val c = SneedTheme.colors
    var renderMode by remember { mutableStateOf(false) }
    var popover by remember { mutableStateOf<String?>(null) } // "color" | "size" | "emote" | null
    var showCustomColor by remember { mutableStateOf(false) }

    // Stable identity for the emote picker's onPick: read the latest value/callbacks via snapshots
    // so the lambda never changes, letting the grid cells skip recomposition on keystrokes/inserts.
    val curValue by rememberUpdatedState(value)
    val curChange by rememberUpdatedState(onValueChange)
    val curUsed by rememberUpdatedState(onEmoteUsed)
    val onEmotePick = remember {
        // Trailing space so the inserted code can't glue to following text (boundary-guarded match).
        { code: String -> curChange(curValue.insertAtCaret("$code ")); curUsed(code) }
    }

    // Custom selection toolbar: long-pressing text shows the usual Copy/Cut/Paste plus Bold/Italic/
    // Underline/Strike, which wrap the highlighted text. Reads latest value/onChange via snapshots.
    val view = LocalView.current
    val formatToolbar = remember(view) {
        FormatTextToolbar(view) { before, after -> curChange(curValue.wrapSelection(before, after, "")) }
    }

    // Rendered editing: hides inline tag markers and applies their styles in-place, while the value
    // stays raw BBCode. The field is fully editable in this mode — it's not a read-only preview.
    val rendered = remember(c) { RenderedEditTransformation(c) }

    fun wrap(before: String, after: String, placeholder: String) {
        onValueChange(value.wrapSelection(before, after, placeholder))
    }

    // Tap-to-toggle for the simple inline styles: with a selection it wraps; with a bare caret it
    // drops in empty tags and parks the caret inside (tap again to step back out). No text selection
    // required — the mobile-native flow. Active state is derived from the caret, never stored.
    fun toggle(name: String, before: String, after: String) {
        onValueChange(value.toggleFormat(name, before, after))
    }
    val openTags = value.openTagsAtCaret()

    fun send() {
        if (value.text.isBlank()) return
        onSend()
        renderMode = false
    }

    val canSend = value.text.isNotBlank()
    val over = value.text.length > MAX_LEN

    Column(Modifier.fillMaxWidth().background(c.surface)) {
        if (editing) {
            Row(
                Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Editing message — saves on send", color = c.text, fontSize = 12.sp)
                Box(Modifier.clip(CircleShape).clickable { onCancelEdit() }.padding(4.dp)) {
                    Text("✕", color = c.text2, fontSize = 14.sp)
                }
            }
        }
        // Toolbar
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dim = 1f
                if (onUploadImage != null) {
                    ToolIconBtn(Icons.Filled.AddPhotoAlternate, "Upload image", dim, true) { onUploadImage() }
                    ToolSep()
                }
                ToolBtn("B", FontWeight.ExtraBold, dim, true, active = "b" in openTags) { toggle("b", "[b]", "[/b]") }
                ToolBtn("I", FontWeight.Normal, dim, true, italic = true, active = "i" in openTags) { toggle("i", "[i]", "[/i]") }
                ToolBtn("U", FontWeight.SemiBold, dim, true, underline = true, active = "u" in openTags) { toggle("u", "[u]", "[/u]") }
                ToolBtn("S", FontWeight.SemiBold, dim, true, strike = true, active = "s" in openTags) { toggle("s", "[s]", "[/s]") }
                ToolSep()
                ToolIconBtn(Icons.Filled.FormatColorText, "Color", dim, true) { popover = if (popover == "color") null else "color" }
                ToolIconBtn(Icons.Filled.FormatSize, "Size", dim, true) { popover = if (popover == "size") null else "size" }
                ToolSep()
                ToolIconBtn(Icons.Filled.Link, "Link", dim, true) { wrap("[url=https://]", "[/url]", "link text") }
                ToolIconBtn(Icons.Filled.Image, "Image", dim, true) { wrap("[img]", "[/img]", "https://image.url") }
                ToolSep()
                ToolIconBtn(Icons.Filled.Mood, "Emotes", dim, true) { popover = if (popover == "emote") null else "emote" }
                ToolIconBtn(Icons.Filled.Bookmarks, "Custom emotes", dim, true) { popover = if (popover == "macro") null else "macro" }
            }
            Spacer(Modifier.width(8.dp))
            // Source / Rendered toggle — both are editable; only the display differs.
            Row(
                Modifier.clip(RoundedCornerShape(9.dp)).background(c.inputBg).padding(2.dp)
                    .clickable { renderMode = !renderMode },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Seg("BBCode", active = !renderMode)
                Seg("Rendered", active = renderMode)
            }
        }

        // Popovers
        if (popover == "color") {
            ColorPanel(
                onPick = { hex -> wrap("[color=$hex]", "[/color]", "text"); popover = null },
                onCustom = { popover = null; showCustomColor = true },
            )
        }
        if (popover == "size") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BB_SIZES.forEach { (label, n) ->
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.surface)
                            .clickable { wrap("[size=$n]", "[/size]", "text"); popover = null },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = c.text, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
            }
        }
        if (popover == "emote") {
            EmotePickerPanel(recent = recentEmotes, onPick = onEmotePick)
        }
        if (popover == "macro") {
            MacroPanel(
                macros = macros,
                onPick = { m -> onMacroPick(m); popover = null },
                onAdd = { popover = null; onMacroAdd() },
                onEdit = { m -> popover = null; onMacroEdit(m) },
            )
        }
        if (showCustomColor) {
            CustomColorDialog(
                initial = parseHex(BB_COLORS.first()),
                onDismiss = { showCustomColor = false },
                onPick = { hex -> wrap("[color=$hex]", "[/color]", "text"); showCustomColor = false },
            )
        }

        // Input row
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 9.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(c.inputBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                CompositionLocalProvider(LocalTextToolbar provides formatToolbar) {
                    BasicTextField(
                        value = value,
                        onValueChange = { if (it.text.length <= MAX_LEN + 200) onValueChange(it) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 20.sp),
                        // Rendered mode hides inline tags and styles their content in-place; BBCode mode is plain.
                        visualTransformation = if (renderMode) rendered else VisualTransformation.None,
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp, max = 120.dp),
                        decorationBox = { inner ->
                            if (value.text.isEmpty()) {
                                Text("Write a message…  use [b] [i] [color] …", color = c.text3, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (canSend && !over) c.accent else c.surface3)
                    .clickable(enabled = canSend && !over) { send() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                    tint = if (canSend && !over) c.accentOn else c.text3, modifier = Modifier.size(20.dp),
                )
            }
        }
        if (over) {
            Text(
                "Message too long (${value.text.length}/$MAX_LEN)",
                color = c.danger, fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * Emote picker: a pinned "Recent" bar (most-recent-first, ~2 rows) over a scrollable grid of every
 * custom emote. Tapping any cell inserts its code at the caret.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmotePickerPanel(recent: List<String>, onPick: (String) -> Unit) {
    val c = SneedTheme.colors
    val entries = remember { EmoteTable.entries() }
    if (entries.isEmpty()) {
        Text("Emotes unavailable", color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        return
    }
    val ctx = LocalContext.current
    val loader = EmoteTable.loader ?: ctx.imageLoader
    // First time the picker opens, warm the memory cache at cell size so scrolling never hits disk.
    val density = LocalDensity.current
    LaunchedEffect(Unit) { EmoteTable.warmThumbnails(ctx, with(density) { 30.dp.roundToPx() }) }
    val urlOf = remember(entries) { entries.toMap() }
    // Keep only recents we still have an image for, preserving most-recent-first order.
    val recents = remember(recent, urlOf) { recent.mapNotNull { code -> urlOf[code]?.let { code to it } } }

    Column(Modifier.fillMaxWidth().background(c.surface2)) {
        if (recents.isNotEmpty()) {
            Text(
                "Recent", color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                recents.forEach { (code, url) -> EmoteCell(code, url, loader, onPick) }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).height(1.dp).background(c.border))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 46.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(entries, key = { it.first }) { (code, url) -> EmoteCell(code, url, loader, onPick) }
        }
    }
}

@Composable
private fun EmoteCell(code: String, url: String, loader: coil.ImageLoader, onPick: (String) -> Unit) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).clickable { onPick(code) },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(model = url, contentDescription = code, imageLoader = loader, modifier = Modifier.size(30.dp))
    }
}

/** The color popover: a wrapped grid of common swatches (incl. 4chan greentext) plus a Custom tile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPanel(onPick: (String) -> Unit, onCustom: () -> Unit) {
    val c = SneedTheme.colors
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BB_COLORS.forEach { hex ->
            val needsBorder = hex.equals("#ffffff", ignoreCase = true) || hex.equals("#000000", ignoreCase = true)
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(parseHex(hex))
                    .then(if (needsBorder) Modifier.border(1.dp, c.border, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onPick(hex) },
            )
        }
        // Custom color tile: rainbow with a + to signal "pick your own".
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.sweepGradient(RAINBOW))
                .clickable { onCustom() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Custom color", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToolBtn(
    label: String, weight: FontWeight, alpha: Float, enabled: Boolean,
    italic: Boolean = false, underline: Boolean = false, strike: Boolean = false,
    active: Boolean = false, onClick: () -> Unit,
) {
    val c = SneedTheme.colors
    Box(
        Modifier.size(width = 34.dp, height = 30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) c.accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = (if (active) c.accent else c.text2).copy(alpha = alpha), fontWeight = weight, fontSize = 15.sp,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                underline -> TextDecoration.Underline
                strike -> TextDecoration.LineThrough
                else -> null
            },
        )
    }
}

@Composable
private fun ToolIconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, alpha: Float, enabled: Boolean, onClick: () -> Unit) {
    val c = SneedTheme.colors
    Box(
        Modifier.size(width = 34.dp, height = 30.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = c.text2.copy(alpha = alpha), modifier = Modifier.size(18.dp)) }
}

@Composable
private fun ToolSep() {
    Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 18.dp).background(SneedTheme.colors.border))
}

@Composable
private fun Seg(label: String, active: Boolean) {
    val c = SneedTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(if (active) c.accent else Color.Transparent)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(label, color = if (active) c.accentOn else c.text3, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Matches any BBCode tag, capturing whether it closes (group 1) and its name (group 2). */
private val INLINE_TAG_RE = Regex("""\[(/?)([a-zA-Z]+)(?:=[^\]]*)?]""")

/**
 * Names of the inline tags currently open at the caret, e.g. {"b", "color"}. Derived purely by
 * walking the tag stack in the text before the caret — so toolbar buttons can light up to show
 * "you're typing inside this" without any separate, desync-prone state.
 */
fun TextFieldValue.openTagsAtCaret(): Set<String> {
    val caret = selection.min
    val stack = mutableListOf<String>()
    for (m in INLINE_TAG_RE.findAll(text.substring(0, caret))) {
        val name = m.groupValues[2].lowercase()
        if (m.groupValues[1] == "/") {
            val i = stack.lastIndexOf(name)
            if (i >= 0) stack.removeAt(i)
        } else {
            stack.add(name)
        }
    }
    return stack.toSet()
}

/**
 * Tap-to-toggle an inline style. With a selection, wraps it. With a bare caret: if not already
 * inside this tag, inserts an empty pair and parks the caret between them; if already inside,
 * steps the caret out past the matching close tag. IME-safe — only ever fires on a button tap.
 */
fun TextFieldValue.toggleFormat(name: String, before: String, after: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    if (start != end) return wrapSelection(before, after, "")
    if (name in openTagsAtCaret()) {
        val close = Regex("""\[/""" + Regex.escape(name) + """]""", RegexOption.IGNORE_CASE).find(text, start)
        return if (close != null) copy(selection = TextRange(close.range.last + 1)) else this
    }
    val newText = text.substring(0, start) + before + after + text.substring(start)
    val caret = start + before.length
    return copy(text = newText, selection = TextRange(caret))
}

/** Wrap the current selection (or insert a placeholder at the caret) with BBCode tags. */
fun TextFieldValue.wrapSelection(before: String, after: String, placeholder: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val selected = if (start == end) placeholder else text.substring(start, end)
    val newText = text.substring(0, start) + before + selected + after + text.substring(end)
    val cursorStart = start + before.length
    return copy(text = newText, selection = TextRange(cursorStart, cursorStart + selected.length))
}

/** Insert [s] at the caret, replacing any selection, and place the caret right after it. */
fun TextFieldValue.insertAtCaret(s: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val newText = text.substring(0, start) + s + text.substring(end)
    val cursor = start + s.length
    return copy(text = newText, selection = TextRange(cursor, cursor))
}

private fun parseHex(hex: String): Color {
    val h = hex.removePrefix("#")
    return h.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.Gray
}
