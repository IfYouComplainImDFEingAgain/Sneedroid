package st.kiwifarms.sneedroid.core.protocol

import st.kiwifarms.sneedroid.core.model.ChatMessage
import st.kiwifarms.sneedroid.core.model.ChatUser
import st.kiwifarms.sneedroid.core.model.RoomPermissions
import st.kiwifarms.sneedroid.core.model.WhisperMessage

/**
 * A parsed inbound chat frame. Every server frame is a JSON object with a single
 * top-level key identifying its type; [ChatProtocol.parse] maps that key to one of
 * these. [Unknown] preserves anything we can't classify (forward-compatible).
 */
sealed interface ChatEvent {
    data class Messages(val messages: List<ChatMessage>) : ChatEvent
    data class UsersJoined(val users: List<ChatUser>) : ChatEvent
    data class UsersParted(val ids: List<Long>) : ChatEvent
    data class Permissions(val permissions: RoomPermissions) : ChatEvent
    data class System(val text: String) : ChatEvent
    data class Deleted(val uuids: List<String>) : ChatEvent
    data class Whisper(val whisper: WhisperMessage) : ChatEvent
    data class Motd(val message: ChatMessage) : ChatEvent

    /** A frame we couldn't classify or parse; [raw] is the original text. */
    data class Unknown(val raw: String) : ChatEvent
}
