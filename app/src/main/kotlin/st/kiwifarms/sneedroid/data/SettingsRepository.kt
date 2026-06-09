package st.kiwifarms.sneedroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MessageLayout { Forum, Compact, Bubbles }
enum class Density { Cozy, Compact }
enum class AvatarShape { Circle, Square }
enum class YouTubeStyle { Card, Embed }

/**
 * How the background notification service behaves.
 * - [Off]: no service; notifications only while the app is open (or briefly after).
 * - [WhispersOnly]: hold an authenticated connection that NEVER joins a room (whispers are
 *   connection-level, so they still arrive) — cheap, since there's no room firehose.
 * - [WhispersAndMentions]: also stay joined to the current room to catch @-mentions (more battery).
 */
enum class BackgroundMode { Off, WhispersOnly, WhispersAndMentions }

/** User appearance preferences (design TWEAK_DEFAULTS). */
data class AppSettings(
    val layout: MessageLayout = MessageLayout.Forum,
    val density: Density = Density.Cozy,
    val avatarShape: AvatarShape = AvatarShape.Circle,
    val showTime: Boolean = true,
    val dark: Boolean = true,
    val accentHue: Int = 142,
    val recentRooms: List<Int> = Rooms.defaultIds,
    /** Recently-used emote codes, most-recent-first; powers the picker's "Recent" bar (~2 rows). */
    val recentEmotes: List<String> = emptyList(),
    val botUsers: List<String> = listOf("KenoGPT"),
    /** Domains whose [img] embeds are allowed to load inline; others show as a link. */
    val imageDomains: List<String> = listOf("i.ddos.lgbt", "kiwifarms.st"),
    /** Blacklisted usernames whose messages are muted/collapsed. */
    val mutedUsers: List<String> = emptyList(),
    /** When true, muted users' messages are hidden entirely (no "tap to show" row). */
    val hideMuted: Boolean = false,
    /** How to render YouTube links: a preview card or an inline player. */
    val youtubeStyle: YouTubeStyle = YouTubeStyle.Card,
    /** Immersive chat: hide the top app bar + MOTD; the menu icon floats top-left. */
    val immersive: Boolean = false,
    /** Post a system notification for an incoming whisper while the app is backgrounded. */
    val notifyWhispers: Boolean = true,
    /** Post a system notification when someone @-mentions you while the app is backgrounded. */
    val notifyMentions: Boolean = true,
    /** Background connection behaviour for delivering notifications while the app is closed. */
    val backgroundMode: BackgroundMode = BackgroundMode.Off,
    /** Zipline image-upload config (token grants upload to your own Zipline host). */
    val ziplineEnabled: Boolean = false,
    val ziplineUrl: String = "",
    val ziplineKey: String = "",
    /** IP killswitch: when on, refuse to connect unless a VPN tunnel is detected (block residential). */
    val ipKillswitch: Boolean = false,
) {
    /** Whether the composer's upload button should appear. */
    val ziplineReady: Boolean get() = ziplineEnabled && ziplineUrl.isNotBlank() && ziplineKey.isNotBlank()
}

private val Context.dataStore by preferencesDataStore("sneedroid_settings")

/** DataStore-backed appearance settings, exposed reactively with fire-and-forget setters. */
class SettingsRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val ds = context.dataStore

    val settings: StateFlow<AppSettings> = ds.data
        .map { p ->
            AppSettings(
                layout = p[LAYOUT]?.let { runCatching { MessageLayout.valueOf(it) }.getOrNull() } ?: MessageLayout.Forum,
                density = p[DENSITY]?.let { runCatching { Density.valueOf(it) }.getOrNull() } ?: Density.Cozy,
                avatarShape = p[AVATAR]?.let { runCatching { AvatarShape.valueOf(it) }.getOrNull() } ?: AvatarShape.Circle,
                showTime = p[SHOW_TIME] ?: true,
                dark = p[DARK] ?: true,
                accentHue = p[ACCENT] ?: 142,
                recentRooms = p[ROOMS]?.split(",")?.mapNotNull(String::toIntOrNull)?.ifEmpty { Rooms.defaultIds } ?: Rooms.defaultIds,
                recentEmotes = p[RECENT_EMOTES]?.split("\n")?.filter(String::isNotBlank) ?: emptyList(),
                botUsers = p[BOT_USERS]?.let { stored -> stored.split("\n").filter(String::isNotBlank) } ?: listOf("KenoGPT"),
                imageDomains = p[IMG_DOMAINS]?.let { stored -> stored.split("\n").filter(String::isNotBlank) } ?: listOf("i.ddos.lgbt", "kiwifarms.st"),
                mutedUsers = p[MUTED_USERS]?.split("\n")?.filter(String::isNotBlank) ?: emptyList(),
                hideMuted = p[HIDE_MUTED] ?: false,
                youtubeStyle = p[YOUTUBE]?.let { runCatching { YouTubeStyle.valueOf(it) }.getOrNull() } ?: YouTubeStyle.Card,
                immersive = p[IMMERSIVE] ?: false,
                notifyWhispers = p[NOTIFY_WHISPERS] ?: true,
                notifyMentions = p[NOTIFY_MENTIONS] ?: true,
                backgroundMode = p[BACKGROUND_MODE]?.let { runCatching { BackgroundMode.valueOf(it) }.getOrNull() }
                    ?: BackgroundMode.Off,
                ziplineEnabled = p[ZIP_ON] ?: false,
                ziplineUrl = p[ZIP_URL].orEmpty(),
                ziplineKey = p[ZIP_KEY].orEmpty(),
                ipKillswitch = p[IP_KILLSWITCH] ?: false,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    fun setLayout(v: MessageLayout) = put { it[LAYOUT] = v.name }
    fun setDensity(v: Density) = put { it[DENSITY] = v.name }
    fun setAvatarShape(v: AvatarShape) = put { it[AVATAR] = v.name }
    fun setShowTime(v: Boolean) = put { it[SHOW_TIME] = v }
    fun setDark(v: Boolean) = put { it[DARK] = v }
    fun setAccentHue(v: Int) = put { it[ACCENT] = v }
    fun setHideMuted(v: Boolean) = put { it[HIDE_MUTED] = v }
    fun setYouTubeStyle(v: YouTubeStyle) = put { it[YOUTUBE] = v.name }
    fun setImmersive(v: Boolean) = put { it[IMMERSIVE] = v }
    fun setNotifyWhispers(v: Boolean) = put { it[NOTIFY_WHISPERS] = v }
    fun setNotifyMentions(v: Boolean) = put { it[NOTIFY_MENTIONS] = v }
    fun setBackgroundMode(v: BackgroundMode) = put { it[BACKGROUND_MODE] = v.name }
    fun setZiplineEnabled(v: Boolean) = put { it[ZIP_ON] = v }
    fun setZiplineUrl(v: String) = put { it[ZIP_URL] = v.trim() }
    fun setZiplineKey(v: String) = put { it[ZIP_KEY] = v.trim() }
    fun setIpKillswitch(v: Boolean) = put { it[IP_KILLSWITCH] = v }

    fun addBotUser(name: String) = put { prefs ->
        val cur = prefs[BOT_USERS]?.split("\n")?.filter(String::isNotBlank) ?: listOf("KenoGPT")
        prefs[BOT_USERS] = (cur + name.trim()).filter(String::isNotBlank)
            .distinctBy { it.lowercase() }.joinToString("\n")
    }

    fun removeBotUser(name: String) = put { prefs ->
        val cur = prefs[BOT_USERS]?.split("\n")?.filter(String::isNotBlank) ?: listOf("KenoGPT")
        prefs[BOT_USERS] = cur.filterNot { it.equals(name, ignoreCase = true) }.joinToString("\n")
    }

    fun addImageDomain(domain: String) = put { prefs ->
        val cur = prefs[IMG_DOMAINS]?.split("\n")?.filter(String::isNotBlank) ?: listOf("i.ddos.lgbt", "kiwifarms.st")
        prefs[IMG_DOMAINS] = (cur + normalizeDomain(domain)).filter(String::isNotBlank)
            .distinctBy { it.lowercase() }.joinToString("\n")
    }

    fun removeImageDomain(domain: String) = put { prefs ->
        val cur = prefs[IMG_DOMAINS]?.split("\n")?.filter(String::isNotBlank) ?: listOf("i.ddos.lgbt", "kiwifarms.st")
        prefs[IMG_DOMAINS] = cur.filterNot { it.equals(domain, ignoreCase = true) }.joinToString("\n")
    }

    fun addMutedUser(name: String) = put { prefs ->
        val cur = prefs[MUTED_USERS]?.split("\n")?.filter(String::isNotBlank).orEmpty()
        prefs[MUTED_USERS] = (cur + name.trim()).filter(String::isNotBlank)
            .distinctBy { it.lowercase() }.joinToString("\n")
    }

    fun removeMutedUser(name: String) = put { prefs ->
        val cur = prefs[MUTED_USERS]?.split("\n")?.filter(String::isNotBlank).orEmpty()
        prefs[MUTED_USERS] = cur.filterNot { it.equals(name, ignoreCase = true) }.joinToString("\n")
    }

    /** Strip protocol/path/leading-www so the stored value is a bare host. */
    private fun normalizeDomain(input: String): String = input.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore('/').removePrefix("www.").trim()

    fun rememberRoom(id: Int) = put { prefs ->
        val current = prefs[ROOMS]?.split(",")?.mapNotNull(String::toIntOrNull).orEmpty()
        val updated = (listOf(id) + current).distinct().take(12)
        prefs[ROOMS] = updated.joinToString(",")
    }

    /** Prepend a just-used emote, dedupe, and keep ~2 rows' worth (newline-joined; codes lack newlines). */
    fun rememberEmote(code: String) = put { prefs ->
        val current = prefs[RECENT_EMOTES]?.split("\n")?.filter(String::isNotBlank).orEmpty()
        val updated = (listOf(code) + current).distinct().take(16)
        prefs[RECENT_EMOTES] = updated.joinToString("\n")
    }

    private fun put(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { ds.edit(transform) }
    }

    private companion object {
        val LAYOUT = stringPreferencesKey("layout")
        val DENSITY = stringPreferencesKey("density")
        val AVATAR = stringPreferencesKey("avatar_shape")
        val SHOW_TIME = booleanPreferencesKey("show_time")
        val DARK = booleanPreferencesKey("dark")
        val ACCENT = intPreferencesKey("accent_hue")
        val ROOMS = stringPreferencesKey("recent_rooms")
        val RECENT_EMOTES = stringPreferencesKey("recent_emotes")
        val BOT_USERS = stringPreferencesKey("bot_users")
        val IMG_DOMAINS = stringPreferencesKey("image_domains")
        val MUTED_USERS = stringPreferencesKey("muted_users")
        val HIDE_MUTED = booleanPreferencesKey("hide_muted")
        val YOUTUBE = stringPreferencesKey("youtube_style")
        val IMMERSIVE = booleanPreferencesKey("immersive")
        val NOTIFY_WHISPERS = booleanPreferencesKey("notify_whispers")
        val NOTIFY_MENTIONS = booleanPreferencesKey("notify_mentions")
        val BACKGROUND_MODE = stringPreferencesKey("background_mode")
        val ZIP_ON = booleanPreferencesKey("zipline_enabled")
        val ZIP_URL = stringPreferencesKey("zipline_url")
        val ZIP_KEY = stringPreferencesKey("zipline_key")
        val IP_KILLSWITCH = booleanPreferencesKey("ip_killswitch")
    }
}
