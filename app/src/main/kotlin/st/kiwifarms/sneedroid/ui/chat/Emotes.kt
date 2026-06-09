package st.kiwifarms.sneedroid.ui.chat

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.serialization.json.Json

/**
 * Custom-emote lookup for SneedChat. The forum's emote "codes" are not regular: they range from
 * colon-delimited (`:smug:`) to ASCII faces (`:)`, `8)`, `}:P`) to bare phrases (`*sigh*`,
 * `AUGH YEAH`). So there is no pattern to match — we match the literal set of known codes, built
 * once from `assets/emotes.json` (code → absolute image URL).
 *
 * Matching is:
 *  - longest-first, so `}:P` wins over `:P` and `:'(` over `:(`,
 *  - case-sensitive, so `:O` (surprised) ≠ `:o` (eek),
 *  - boundary-guarded, so a code only counts when it is not glued to a letter/digit on either side
 *    (blocks `:c` in "Subject:cat", `AUGH YEAH` mid-word). See [findMatches].
 *
 * Emotes are a small, fixed, heavily-reused asset set, so they get their own [loader] (a dedicated
 * Coil disk + memory cache) rather than competing with avatars/embeds in the app-wide cache, and
 * the whole set is warmed by [prefetchAll] at startup.
 */
object EmoteTable {
    private var codeToUrl: Map<String, String> = emptyMap()
    private var regex: Regex? = null

    /** Dedicated image loader so emotes can't be evicted by chat-image churn. Set by [load]. */
    var loader: ImageLoader? = null
        private set

    val isReady: Boolean get() = regex != null

    /** All emotes as (code, url) in the bundled file's order — for the picker grid. */
    fun entries(): List<Pair<String, String>> = codeToUrl.entries.map { it.key to it.value }

    /** Parse the bundled table and build the emote loader once at startup. */
    fun load(context: Context) {
        if (regex != null) return
        val json = runCatching {
            context.assets.open("emotes.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val map = runCatching { Json.decodeFromString<Map<String, String>>(json) }.getOrNull() ?: return
        if (map.isEmpty()) return
        codeToUrl = map
        // Longest code first so the alternation prefers the most specific match at each position.
        val alternation = map.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        regex = Regex(alternation) // case-sensitive by default — intentional
        loader = buildLoader(context.applicationContext)
    }

    /** A small private cache scoped to the ~175 emotes; survives across launches, separate from app images. */
    private fun buildLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory()) // animated GIF + animated WebP
            } else {
                add(GifDecoder.Factory())
            }
            add(SvgDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder(context).maxSizeBytes(24 * 1024 * 1024).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("emote_cache"))
                .maxSizeBytes(64L * 1024 * 1024) // far more than the whole set needs; emotes never evict each other
                .build()
        }
        // Honour the IP killswitch — incl. the startup prefetch, which would otherwise hit the CDN.
        .okHttpClient { okhttp3.OkHttpClient.Builder().addInterceptor(st.kiwifarms.sneedroid.data.KillswitchInterceptor()).build() }
        .build()

    /**
     * Download every emote that isn't already on disk into the emote cache. Idempotent and cheap on
     * warm starts (a disk-snapshot lookup per url, no decode); on a cold cache it fetches the lot,
     * throttled by OkHttp's per-host limit. Call from a background coroutine.
     */
    fun prefetchAll(context: Context) {
        val loader = loader ?: return
        val disk = loader.diskCache
        for (url in codeToUrl.values.toSet()) {
            // Already cached? openSnapshot uses our explicit diskCacheKey; skip without re-fetching.
            if (disk?.openSnapshot(url)?.use { true } == true) continue
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .diskCacheKey(url)
                    .memoryCachePolicy(CachePolicy.DISABLED) // warming disk only; don't churn the memory cache
                    .build(),
            )
        }
    }

    private var thumbsWarmed = false

    /**
     * Decode every (non-animated) emote into the emote *memory* cache at the picker's thumbnail
     * size, so the grid scrolls and reopens without per-cell disk decodes. Runs once; cheap
     * (~5–6 MB) and reads from the disk cache already warmed by [prefetchAll]. Call from the picker
     * (first open) — not at startup — so the memory isn't held unless the picker is actually used.
     * Animated GIFs are skipped: Coil doesn't bitmap-cache animated drawables, so warming them is
     * wasted decode work.
     */
    fun warmThumbnails(context: Context, sizePx: Int) {
        if (thumbsWarmed || sizePx <= 0) return
        val loader = loader ?: return
        thumbsWarmed = true
        for (url in codeToUrl.values.toSet()) {
            if (url.endsWith(".gif", ignoreCase = true)) continue
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(sizePx, sizePx) // match the picker cell so the cache keys line up
                    .scale(Scale.FIT)
                    .build(),
            )
        }
    }

    data class Match(val start: Int, val end: Int, val url: String)

    /**
     * Find non-overlapping emote codes in [text], honoring the boundary-guarded rule: a candidate is
     * rejected if the character immediately before or after it is a letter or digit. A rejected
     * candidate doesn't consume the text — we advance one char so a shorter code (or a later one)
     * can still match.
     */
    fun findMatches(text: String): List<Match> {
        val re = regex ?: return emptyList()
        val out = mutableListOf<Match>()
        var from = 0
        while (from <= text.length) {
            val m = re.find(text, from) ?: break
            val s = m.range.first
            val e = m.range.last + 1
            val beforeOk = s == 0 || !text[s - 1].isLetterOrDigit()
            val afterOk = e == text.length || !text[e].isLetterOrDigit()
            if (beforeOk && afterOk) {
                out += Match(s, e, codeToUrl.getValue(m.value))
                from = e
            } else {
                from = s + 1
            }
        }
        return out
    }
}

/** Inline-content id used by [appendInlineContent]; the emote URL travels as the placeholder's alt-text. */
const val EMOTE_INLINE_ID = "emote"

/**
 * Single inline-content entry shared by every BBCode [androidx.compose.material3.Text]. Keyed by a
 * constant; the per-emote URL is passed as the placeholder alt-text, so we don't need per-message
 * maps. Loads via the dedicated emote [EmoteTable.loader]. The box is uniform (~1.4×1.3em) with Fit
 * scaling — wide emotes letterbox, which is fine.
 */
val EmoteInlineContent: Map<String, InlineTextContent> = mapOf(
    EMOTE_INLINE_ID to InlineTextContent(
        Placeholder(width = 1.45.em, height = 1.3.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
    ) { url ->
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            imageLoader = EmoteTable.loader ?: LocalContext.current.imageLoader,
            modifier = Modifier.fillMaxSize(),
        )
    },
)
