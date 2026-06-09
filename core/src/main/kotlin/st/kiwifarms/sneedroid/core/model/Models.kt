package st.kiwifarms.sneedroid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Author/recipient stub embedded in messages and whispers. */
@Serializable
data class ChatAuthor(
    val id: Long,
    val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** A chat message (also used for MOTD, which has the same shape). */
@Serializable
data class ChatMessage(
    @SerialName("message_uuid") val uuid: String? = null,
    @SerialName("message_id") val id: Long? = null,
    val author: ChatAuthor,
    /** Server-rendered HTML (emotes already as <img>). Prefer [raw] for our own rendering. */
    val message: String = "",
    /** Original BBCode source (HTML-entity-encoded). Render this ourselves. */
    @SerialName("message_raw") val raw: String = "",
    @SerialName("message_date") val date: Long = 0,
    /** 0 = never edited; otherwise the epoch-seconds of the last edit. */
    @SerialName("message_edit_date") val editDate: Long = 0,
    @SerialName("room_id") val roomId: Long? = null,
) {
    val isEdited: Boolean get() = editDate > 0
}

/** A roster entry, as found in a `users` frame. */
@Serializable
data class ChatUser(
    val id: Long,
    val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("last_activity") val lastActivity: Long? = null,
)

/** What the current user may do in the joined room (from a `permissions` frame). */
@Serializable
data class RoomPermissions(
    @SerialName("can_view") val canView: Boolean = false,
    @SerialName("can_send") val canSend: Boolean = false,
    @SerialName("can_edit_own") val canEditOwn: Boolean = false,
    @SerialName("can_edit_other") val canEditOther: Boolean = false,
    @SerialName("can_delete_own") val canDeleteOwn: Boolean = false,
    @SerialName("can_delete_other") val canDeleteOther: Boolean = false,
    @SerialName("can_report") val canReport: Boolean = false,
    @SerialName("can_view_deleted") val canViewDeleted: Boolean = false,
    @SerialName("can_undelete") val canUndelete: Boolean = false,
    @SerialName("can_motd") val canMotd: Boolean = false,
)

/** A private message between [author] and [recipient]. */
@Serializable
data class WhisperMessage(
    val author: ChatAuthor,
    val recipient: ChatAuthor,
    val message: String = "",
    @SerialName("message_raw") val raw: String = "",
    @SerialName("message_date") val date: Long = 0,
)
