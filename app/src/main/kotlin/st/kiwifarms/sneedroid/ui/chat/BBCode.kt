package st.kiwifarms.sneedroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import st.kiwifarms.sneedroid.ui.theme.SneedColors
import st.kiwifarms.sneedroid.ui.theme.SneedTheme

/**
 * Block-level BBCode renderer for SneedChat. Handles inline styling (b/i/u/s/color/size/
 * code/url/@mention) plus block elements the inline-only renderer couldn't: [img] images,
 * [br] breaks, [heading], and collapsible [spoiler] sections. Note: SneedChat renders
 * [size=N] as N percent (not pixels).
 */

/** Whitelist of hosts whose [img] embeds may load inline. Provided from settings. */
val LocalImageDomains = androidx.compose.runtime.compositionLocalOf { emptyList<String>() }

private sealed interface BBlock {
    data class Para(val text: AnnotatedString) : BBlock
    data class Heading(val text: AnnotatedString) : BBlock
    data class Img(val url: String) : BBlock
    data class Spoiler(val title: String, val children: List<BBlock>) : BBlock
    data class YouTube(val id: String) : BBlock
}

private sealed interface Tok
private data class Txt(val s: String) : Tok
private data class Open(val name: String, val arg: String?) : Tok
private data class Close(val name: String) : Tok

private val tagRe = Regex("""\[(/?)([a-zA-Z0-9]+)(?:=([^\]]*))?\]""")
private val mentionRe = Regex("""(^|[\s(])@([A-Za-z0-9_]+)""")
private val INLINE = setOf("b", "i", "u", "s", "code", "color", "size")

@Composable
fun BBCodeText(
    raw: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = fontSize * 1.4,
) {
    val colors = SneedTheme.colors
    val blocks = remember(raw, colors) { parseBlocks(decodeEntities(raw), colors) }
    if (blocks.size == 1 && blocks[0] is BBlock.Para) {
        Text((blocks[0] as BBlock.Para).text, color = color, fontSize = fontSize, lineHeight = lineHeight, modifier = modifier, inlineContent = EmoteInlineContent)
    } else {
        Column(modifier) { blocks.forEach { RenderBlock(it, color, fontSize, lineHeight) } }
    }
}

@Composable
private fun RenderBlock(block: BBlock, color: Color, fontSize: TextUnit, lineHeight: TextUnit) {
    when (block) {
        is BBlock.Para -> if (block.text.isNotEmpty()) {
            Text(block.text, color = color, fontSize = fontSize, lineHeight = lineHeight, inlineContent = EmoteInlineContent)
        }
        is BBlock.Heading -> Text(
            block.text, color = color, fontSize = fontSize * 1.25, fontWeight = FontWeight.Bold,
            lineHeight = fontSize * 1.4, modifier = Modifier.padding(vertical = 2.dp), inlineContent = EmoteInlineContent,
        )
        is BBlock.Img -> {
            val uri = LocalUriHandler.current
            if (isImageAllowed(block.url, LocalImageDomains.current)) {
                val full = withScheme(block.url)
                AsyncImage(
                    model = full,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.padding(vertical = 3.dp).heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(8.dp)).clickable { runCatching { uri.openUri(full) } },
                )
            } else {
                ImageLink(block.url)
            }
        }
        is BBlock.Spoiler -> SpoilerBlock(block, color, fontSize, lineHeight)
        is BBlock.YouTube -> when (LocalYouTubeStyle.current) {
            st.kiwifarms.sneedroid.data.YouTubeStyle.Card -> YouTubeCard(block.id)
            st.kiwifarms.sneedroid.data.YouTubeStyle.Embed -> YouTubeEmbed(block.id)
        }
    }
}

@Composable
private fun SpoilerBlock(block: BBlock.Spoiler, color: Color, fontSize: TextUnit, lineHeight: TextUnit) {
    val c = SneedTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, c.border, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .background(c.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null, tint = c.text2, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(block.title, color = c.text2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                block.children.forEach { RenderBlock(it, color, fontSize, lineHeight) }
            }
        }
    }
}

@Composable
private fun ImageLink(url: String) {
    val c = SneedTheme.colors
    val uri = LocalUriHandler.current
    Row(
        Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
            .background(c.surface2).clickable { runCatching { uri.openUri(url) } }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            url, color = c.accent, fontSize = 13.sp,
            textDecoration = TextDecoration.Underline, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** True if [url]'s host exactly matches a whitelisted domain (no subdomain wildcarding). */
private fun isImageAllowed(url: String, domains: List<String>): Boolean {
    if (domains.isEmpty()) return false
    val host = hostOf(url) ?: return false
    return domains.any { d ->
        val dom = d.trim().lowercase().removePrefix("www.")
        dom.isNotEmpty() && host == dom
    }
}

/**
 * Extract the host from a URL without relying on java.net.URI (which returns null for
 * scheme-less URLs). Handles http(s)://, protocol-relative //, bare host, userinfo, and port.
 */
private fun hostOf(url: String): String? {
    var s = url.trim()
    val scheme = s.indexOf("://")
    when {
        scheme in 0..8 -> s = s.substring(scheme + 3)
        s.startsWith("//") -> s = s.substring(2)
    }
    val slash = s.indexOf('/').let { if (it < 0) s.length else it }
    val at = s.lastIndexOf('@', startIndex = slash - 1)
    if (at in 0 until slash) s = s.substring(at + 1)
    val host = s.takeWhile { it != '/' && it != '?' && it != '#' && it != ':' }
    return host.ifBlank { null }?.lowercase()?.removePrefix("www.")
}

/** Ensure a URL has a scheme so Coil can load it (the raw may omit https). */
private fun withScheme(url: String): String {
    val u = url.trim()
    return when {
        u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) -> u
        u.startsWith("//") -> "https:$u"
        else -> "https://$u"
    }
}

// ---------------- parsing ----------------

private fun tokenize(s: String): List<Tok> {
    val toks = mutableListOf<Tok>()
    var last = 0
    for (m in tagRe.findAll(s)) {
        if (m.range.first > last) toks.add(Txt(s.substring(last, m.range.first)))
        val closing = m.groupValues[1] == "/"
        val name = m.groupValues[2].lowercase()
        val arg = m.groupValues[3].ifEmpty { null }
        when {
            name == "br" -> toks.add(Txt("\n"))
            closing -> toks.add(Close(name))
            else -> toks.add(Open(name, arg))
        }
        last = m.range.last + 1
    }
    if (last < s.length) toks.add(Txt(s.substring(last)))
    return toks
}

private fun parseBlocks(raw: String, colors: SneedColors): List<BBlock> {
    if (raw.isEmpty()) return listOf(BBlock.Para(AnnotatedString("")))
    return Parser(tokenize(raw), colors).parseUntil(null)
}

private class Parser(val toks: List<Tok>, val colors: SneedColors) {
    var i = 0

    fun parseUntil(stop: String?): List<BBlock> {
        val blocks = mutableListOf<BBlock>()
        var sb = AnnotatedString.Builder()
        var hasInline = false
        val styles = ArrayDeque<SpanStyle>()
        fun merged(): SpanStyle {
            var s = SpanStyle()
            styles.forEach { s = s.merge(it) }
            return s
        }
        fun flush() {
            if (hasInline) { blocks += BBlock.Para(sb.toAnnotatedString()); sb = AnnotatedString.Builder(); hasInline = false }
        }
        while (i < toks.size) {
            when (val t = toks[i]) {
                is Txt -> {
                    if (t.s.isNotEmpty()) {
                        // Split bare text on YouTube links so they become their own block.
                        var idx = 0
                        for (m in youTubeMatches(t.s)) {
                            val before = t.s.substring(idx, m.range.first)
                            if (before.isNotEmpty()) { appendInline(sb, before, merged(), colors); hasInline = true }
                            flush()
                            blocks += BBlock.YouTube(m.groupValues[1])
                            idx = m.range.last + 1
                        }
                        val rest = t.s.substring(idx)
                        if (rest.isNotEmpty()) { appendInline(sb, rest, merged(), colors); hasInline = true }
                    }
                    i++
                }
                is Close -> {
                    if (t.name == stop) { i++; flush(); return blocks }
                    if (t.name in INLINE && styles.isNotEmpty()) styles.removeLast()
                    i++
                }
                is Open -> when (t.name) {
                    in INLINE -> { styles.addLast(inlineStyle(t.name, t.arg, colors)); i++ }
                    "url" -> {
                        i++
                        val content = readTokensUntilClose("url")
                        val plain = content.filterIsInstance<Txt>().joinToString("") { it.s }
                        // SneedChat embeds images as [url=X][img]Y[/img][/url]; render the nested image
                        // as a (clickable) image block instead of collapsing it to a bare text link.
                        val imgUrl = nestedImgUrl(content)
                        val href = t.arg?.takeIf { it.isNotBlank() } ?: imgUrl ?: plain
                        val yt = youTubeId(href)
                        when {
                            yt != null -> { flush(); blocks += BBlock.YouTube(yt) }
                            imgUrl != null -> { flush(); blocks += BBlock.Img(imgUrl) }
                            href.isNotBlank() -> {
                                sb.withLink(LinkAnnotation.Url(href)) {
                                    appendInline(sb, plain.ifEmpty { href }, merged().merge(linkStyle(colors)), colors)
                                }
                                hasInline = true
                            }
                        }
                    }
                    "img" -> {
                        i++
                        val url = readPlainUntilClose("img").trim()
                        flush()
                        if (url.isNotBlank()) blocks += BBlock.Img(url)
                    }
                    "heading" -> {
                        i++
                        val text = readInlineUntilClose("heading", colors)
                        flush()
                        blocks += BBlock.Heading(text)
                    }
                    "spoiler" -> {
                        i++
                        flush()
                        val children = parseUntil("spoiler")
                        val title = t.arg?.trim('"', ' ')?.ifEmpty { null } ?: "Spoiler"
                        blocks += BBlock.Spoiler(title, children)
                    }
                    else -> i++ // unknown tag: drop it, keep content
                }
            }
        }
        flush()
        return blocks
    }

    /** Collect the raw tokens up to [stop] (consuming the closing tag), preserving nested tags. */
    private fun readTokensUntilClose(stop: String): List<Tok> {
        val out = mutableListOf<Tok>()
        while (i < toks.size) {
            val t = toks[i]
            if (t is Close && t.name == stop) { i++; break }
            out += t
            i++
        }
        return out
    }

    /** Concatenate plain text up to [stop], skipping nested tags. */
    private fun readPlainUntilClose(stop: String): String {
        val b = StringBuilder()
        while (i < toks.size) {
            val t = toks[i]
            if (t is Close && t.name == stop) { i++; break }
            if (t is Txt) b.append(t.s)
            i++
        }
        return b.toString()
    }

    /** Parse inline-styled content up to [stop] into a single AnnotatedString. */
    private fun readInlineUntilClose(stop: String, colors: SneedColors): AnnotatedString {
        val sb = AnnotatedString.Builder()
        val styles = ArrayDeque<SpanStyle>()
        fun merged(): SpanStyle { var s = SpanStyle(); styles.forEach { s = s.merge(it) }; return s }
        while (i < toks.size) {
            when (val t = toks[i]) {
                is Close -> { if (t.name == stop) { i++; break }; if (t.name in INLINE && styles.isNotEmpty()) styles.removeLast(); i++ }
                is Open -> { if (t.name in INLINE) styles.addLast(inlineStyle(t.name, t.arg, colors)); i++ }
                is Txt -> { appendInline(sb, t.s, merged(), colors); i++ }
            }
        }
        return sb.toAnnotatedString()
    }
}

/**
 * Append a plain-text run, rendering custom emotes as inline images and @mentions as styled text.
 * Emotes are split out first (they may be ASCII faces or bare phrases that mention/word logic would
 * mangle); each gap between emotes still goes through [appendWithMentions].
 */
/** The URL inside a nested [img]…[/img] within these tokens, or null if there's no image. */
private fun nestedImgUrl(content: List<Tok>): String? {
    val open = content.indexOfFirst { it is Open && it.name == "img" }
    if (open < 0) return null
    val close = content.indexOfFirst { it is Close && it.name == "img" }
    val end = if (close > open) close else content.size
    return content.subList(open + 1, end).filterIsInstance<Txt>().joinToString("") { it.s }.trim().ifBlank { null }
}

private fun appendInline(sb: AnnotatedString.Builder, text: String, base: SpanStyle, colors: SneedColors) {
    val matches = if (EmoteTable.isReady) EmoteTable.findMatches(text) else emptyList()
    if (matches.isEmpty()) { appendWithMentions(sb, text, base, colors); return }
    var last = 0
    for (m in matches) {
        if (m.start > last) appendWithMentions(sb, text.substring(last, m.start), base, colors)
        // Inline image; the URL rides along as the placeholder's alt-text (see EmoteInlineContent).
        sb.appendInlineContent(EMOTE_INLINE_ID, m.url)
        last = m.end
    }
    if (last < text.length) appendWithMentions(sb, text.substring(last), base, colors)
}

private fun appendWithMentions(sb: AnnotatedString.Builder, text: String, base: SpanStyle, colors: SneedColors) {
    var last = 0
    for (m in mentionRe.findAll(text)) {
        val pre = m.groupValues[1]
        sb.withStyle(base) { sb.append(text.substring(last, m.range.first + pre.length)) }
        sb.withStyle(base.merge(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold))) { sb.append("@" + m.groupValues[2]) }
        last = m.range.last + 1
    }
    if (last < text.length) sb.withStyle(base) { sb.append(text.substring(last)) }
}

private fun inlineStyle(name: String, arg: String?, colors: SneedColors): SpanStyle = when (name) {
    "b" -> SpanStyle(fontWeight = FontWeight.Bold)
    "i" -> SpanStyle(fontStyle = FontStyle.Italic)
    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "code" -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBg, fontSize = 0.9.em)
    "color" -> parseColor(arg)?.let { SpanStyle(color = it) } ?: SpanStyle()
    "size" -> SpanStyle(fontSize = sizePx(arg)) // SneedChat/XenForo: N is a size scale (1–9) or literal px
    else -> SpanStyle()
}

/** XenForo's [SIZE] scale: values 1–9 pick a preset px size (this is why size=3 is "small" and 7 is "large"). */
private val SIZE_SCALE = mapOf(1 to 9, 2 to 10, 3 to 12, 4 to 15, 5 to 18, 6 to 22, 7 to 26, 8 to 30, 9 to 34)

private fun sizePx(arg: String?): TextUnit {
    val n = arg?.toIntOrNull() ?: return 15.sp
    // 1–9 select the preset scale; larger numbers are literal px, capped so a stray [size=999] can't blow up chat.
    return (SIZE_SCALE[n] ?: n.coerceIn(9, 40)).sp
}

private fun linkStyle(colors: SneedColors) = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)

private val namedColors = mapOf(
    "red" to 0xFFE05A5A, "green" to 0xFF5FAE6B, "blue" to 0xFF5A93C4, "yellow" to 0xFFD8C24A,
    "orange" to 0xFFD68A4A, "purple" to 0xFFB56FAE, "pink" to 0xFFD98B91, "white" to 0xFFFFFFFF,
    "black" to 0xFF000000, "gray" to 0xFF888888, "grey" to 0xFF888888, "cyan" to 0xFF5AB8C4, "magenta" to 0xFFC45AB0,
)

private fun parseColor(arg: String?): Color? {
    val a = arg?.trim()?.lowercase() ?: return null
    namedColors[a]?.let { return Color(it) }
    val hex = a.removePrefix("#")
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
    }
    return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/**
 * A [VisualTransformation] for editing BBCode as (near-)rendered text. The inline formatting tag
 * markers (b/i/u/s/color/size/code) are hidden and their style applied to the wrapped content, so
 * the field shows **hello** instead of `[b]hello[/b]` while you type. The value stays raw BBCode —
 * only the display changes — and [androidx.compose.ui.text.input.TextFieldValue.selection] is in raw
 * coordinates, so the toolbar and selection menu keep operating on the source unchanged.
 *
 * Non-inline tags (url/img/spoiler/heading) and emotes are left visible: they can't be represented
 * inline in an editable field, so the Preview pane remains the way to see them fully rendered.
 *
 * The [OffsetMapping] collapses the hidden spans and maps the caret between the visible text and the
 * raw source. At a boundary next to a hidden close tag the caret resolves *inside* the format, so
 * continuing to type extends it — matching the toolbar's tap-to-toggle behaviour.
 */
class RenderedEditTransformation(private val colors: SneedColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val hidden = mutableListOf<IntArray>()                    // [start, endExclusive) markers to drop
        val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()  // styled content ranges (raw coords)
        val open = ArrayDeque<Triple<String, SpanStyle, Int>>()   // name, style, contentStart (raw)
        for (m in tagRe.findAll(raw)) {
            val name = m.groupValues[2].lowercase()
            if (name !in INLINE) continue                         // only hide inline formatting tags
            hidden.add(intArrayOf(m.range.first, m.range.last + 1))
            if (m.groupValues[1] == "/") {
                val idx = open.indexOfLast { it.first == name }
                if (idx >= 0) {
                    val (_, style, start) = open.removeAt(idx)
                    if (m.range.first > start) spans.add(Triple(start, m.range.first, style))
                }
            } else {
                open.addLast(Triple(name, inlineStyle(name, m.groupValues[3].ifEmpty { null }, colors), m.range.last + 1))
            }
        }
        for ((_, style, start) in open) if (raw.length > start) spans.add(Triple(start, raw.length, style))

        // Build the visible string by dropping hidden ranges; record kept segments for offset mapping.
        val sb = StringBuilder()
        val kept = mutableListOf<IntArray>()                      // [rawStart, rawEnd, shownStart]
        var cur = 0
        for (h in hidden) {
            if (h[0] > cur) { kept.add(intArrayOf(cur, h[0], sb.length)); sb.append(raw, cur, h[0]) }
            cur = h[1]
        }
        if (cur < raw.length) { kept.add(intArrayOf(cur, raw.length, sb.length)); sb.append(raw, cur, raw.length) }
        val shown = sb.toString()

        fun o2t(offset: Int): Int {
            val o = offset.coerceIn(0, raw.length)
            var r = shown.length
            for (k in kept) {
                val rs = k[0]; val re = k[1]; val ts = k[2]
                if (o < rs) { r = ts; break }
                if (o <= re) { r = ts + (o - rs); break }
                r = ts + (re - rs)
            }
            return r.coerceIn(0, shown.length)
        }
        fun t2o(offset: Int): Int {
            val t = offset.coerceIn(0, shown.length)
            var r = raw.length
            for (k in kept) {
                val rs = k[0]; val re = k[1]; val ts = k[2]; val te = ts + (re - rs)
                if (t < ts) { r = rs; break }
                if (t <= te) { r = rs + (t - ts); break }
                r = re
            }
            return r.coerceIn(0, raw.length)
        }

        val out = AnnotatedString.Builder(shown)
        for ((s, e, style) in spans) { val a = o2t(s); val b = o2t(e); if (b > a) out.addStyle(style, a, b) }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = o2t(offset)
            override fun transformedToOriginal(offset: Int) = t2o(offset)
        }
        return TransformedText(out.toAnnotatedString(), mapping)
    }
}

private val entityRe = Regex("""&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);""")
private val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
)

private fun decodeOnce(s: String): String = entityRe.replace(s) { m ->
    val body = m.groupValues[1]
    when {
        body.startsWith("#x") || body.startsWith("#X") ->
            body.substring(2).toIntOrNull(16)?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
        body.startsWith("#") ->
            body.substring(1).toIntOrNull()?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
        else -> namedEntities[body] ?: m.value
    }
}

/** Decode HTML entities (named, decimal #39, and hex #x27), tolerating one level of double-encoding. */
private fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    val once = decodeOnce(s)
    return if (once != s && '&' in once) decodeOnce(once) else once
}
