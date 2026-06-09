package st.kiwifarms.sneedroid.ui.chat

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.flow.MutableStateFlow
import st.kiwifarms.sneedroid.SneedApp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import st.kiwifarms.sneedroid.data.YouTubeStyle
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private val YOUTUBE_RED = Color(0xFFFF0000)

/** YouTube link rendering style, supplied from settings. */
val LocalYouTubeStyle = compositionLocalOf { YouTubeStyle.Card }

/** Matches youtube.com/watch?v=, youtu.be/, shorts/, embed/, live/ and captures the 11-char id. */
private val YT_RE = Regex(
    """(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?(?:[^\s&]*&)*v=|shorts/|embed/|live/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    RegexOption.IGNORE_CASE,
)

fun youTubeId(text: String): String? = YT_RE.find(text.trim())?.groupValues?.get(1)

fun youTubeMatches(text: String): Sequence<MatchResult> = YT_RE.findAll(text)

@Serializable
private data class OEmbed(val title: String = "", @SerialName("author_name") val authorName: String = "")

private object YouTubeApi {
    // Honour the IP killswitch: don't fetch video titles from Google without a VPN.
    private val client = OkHttpClient.Builder()
        .addInterceptor(st.kiwifarms.sneedroid.data.KillswitchInterceptor()).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, OEmbed>()

    suspend fun oembed(id: String): OEmbed? {
        cache[id]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val video = "https://www.youtube.com/watch?v=$id"
                val url = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(video, "UTF-8")
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null
                    // Only cache results with a real title; a blank/garbage parse would otherwise
                    // stick forever and the card would show the "YouTube video" fallback until restart.
                    else json.decodeFromString<OEmbed>(r.body!!.string()).takeIf { it.title.isNotBlank() }?.also { cache[id] = it }
                }
            }.getOrNull()
        }
    }
}

@Composable
private fun thumbUrl(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

@Composable
private fun PlayBadge(size: androidx.compose.ui.unit.Dp = 48.dp) {
    Box(
        Modifier.size(width = size * 1.45f, height = size).clip(RoundedCornerShape(percent = 28))
            .background(YOUTUBE_RED.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/**
 * Compact title bar (no video): red left-accent + dark gradient, a small red play glyph,
 * then the title and channel (oEmbed) on one line each. Tap opens the video.
 */
@Composable
fun YouTubeCard(id: String) {
    val uri = LocalUriHandler.current
    val meta by produceState<OEmbed?>(initialValue = null, id) { value = YouTubeApi.oembed(id) }
    val gradient = Brush.linearGradient(listOf(Color(0xFF1A1A1A), Color(0xFF252525)))
    Box(
        Modifier.padding(vertical = 4.dp).fillMaxWidth().widthIn(max = 450.dp)
            .clip(RoundedCornerShape(4.dp)).background(YOUTUBE_RED), // red base shows as the 3dp left edge
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 3.dp).background(gradient)
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .clickable { runCatching { uri.openUri("https://www.youtube.com/watch?v=$id") } }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 36.dp, height = 26.dp).clip(RoundedCornerShape(4.dp)).background(YOUTUBE_RED), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta?.title?.ifBlank { null } ?: "YouTube video",
                    color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta?.authorName?.ifBlank { null } ?: "youtube.com",
                    color = Color(0xFF888888), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** Inline player: thumbnail + play overlay; tap loads the YouTube IFrame embed in a WebView. */
@Composable
fun YouTubeEmbed(id: String) {
    var playing by remember(id) { mutableStateOf(false) }
    // The IFrame embed runs in a WebView, which uses its own network stack — an OkHttp interceptor
    // can't gate it. So observe the killswitch directly and refuse to load (or tear down) the
    // player while blocked, since the WebView would otherwise reach Google with no VPN.
    val ctx = LocalContext.current
    val blockedFlow = remember(ctx) {
        (ctx.applicationContext as? SneedApp)?.container?.killswitchGate?.blocked ?: MutableStateFlow(false)
    }
    val blocked by blockedFlow.collectAsState()
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (!playing) {
            AsyncImage(model = thumbUrl(id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().clickable { playing = true }, contentAlignment = Alignment.Center) { PlayBadge() }
        } else if (blocked) {
            Text("VPN required to play", color = Color.White, fontSize = 13.sp)
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.domStorageEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        loadUrl("https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0")
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
