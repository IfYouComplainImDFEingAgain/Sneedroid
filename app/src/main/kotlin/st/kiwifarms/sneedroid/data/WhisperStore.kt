package st.kiwifarms.sneedroid.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk persistence for whisper conversations.
 *
 * Whispers arrive live and the server does not replay private-message history on connect, so
 * without this every conversation is lost on process death. Threads are written as JSON and
 * namespaced per signed-in user id, so switching accounts on one device can't cross-contaminate
 * another user's DMs. Per-conversation line history is capped on write to keep the file bounded.
 */
class WhisperStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writeLock = Any()

    private fun file(selfId: Long?): File =
        File(context.filesDir, "whispers_${selfId ?: "anon"}.json")

    /** Read persisted conversations for [selfId]; empty (never throws) when absent or corrupt. */
    fun load(selfId: Long?): List<WhisperConversation> {
        val f = file(selfId)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredConv>>(f.readText()).map { c ->
                WhisperConversation(
                    partnerId = c.partnerId,
                    partnerName = c.name,
                    partnerAvatar = c.avatar,
                    lines = c.lines.map { WhisperLine(it.fromMe, it.raw, it.date) },
                    unread = c.unread,
                    lastActivity = c.lastActivity,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Overwrite the persisted threads for [selfId]. Serialized; safe to call from any thread. */
    fun save(selfId: Long?, convs: List<WhisperConversation>) {
        val data = convs.map { c ->
            StoredConv(
                partnerId = c.partnerId,
                name = c.partnerName,
                avatar = c.partnerAvatar,
                lines = c.lines.takeLast(MAX_LINES).map { StoredLine(it.fromMe, it.raw, it.date) },
                unread = c.unread,
                lastActivity = c.lastActivity,
            )
        }
        synchronized(writeLock) {
            runCatching { file(selfId).writeText(json.encodeToString(data)) }
        }
    }

    @Serializable
    private data class StoredLine(val fromMe: Boolean, val raw: String, val date: Long)

    @Serializable
    private data class StoredConv(
        val partnerId: Long,
        val name: String,
        val avatar: String? = null,
        val lines: List<StoredLine> = emptyList(),
        val unread: Int = 0,
        val lastActivity: Long = 0,
    )

    private companion object {
        const val MAX_LINES = 500
    }
}
