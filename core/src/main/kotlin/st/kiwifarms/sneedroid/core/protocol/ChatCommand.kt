package st.kiwifarms.sneedroid.core.protocol

/**
 * An outbound chat action. [ChatProtocol.encode] turns these into the raw text frames
 * the server expects. See docs/02-protocol.md §3.2.
 */
sealed interface ChatCommand {
    /** `/join {roomId}` */
    data class Join(val roomId: Int) : ChatCommand

    /** A normal message — sent as raw text, no command prefix. */
    data class Send(val text: String) : ChatCommand

    /**
     * A whisper / DM. Prefers the id form `/w {recipientId} {text}` (unambiguous) when
     * [recipientId] is known, falling back to the username form `/w @{username}, {text}`.
     */
    data class Whisper(val recipientId: Long?, val username: String, val text: String) : ChatCommand

    /** `/edit {"uuid":...,"message":...}` */
    data class Edit(val uuid: String, val text: String) : ChatCommand

    /** `/delete {uuid}` */
    data class Delete(val uuid: String) : ChatCommand

    /** `/motd {uuid}` */
    data class Motd(val uuid: String) : ChatCommand
}
