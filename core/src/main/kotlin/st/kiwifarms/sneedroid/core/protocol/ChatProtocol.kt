package st.kiwifarms.sneedroid.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import st.kiwifarms.sneedroid.core.model.ChatMessage
import st.kiwifarms.sneedroid.core.model.ChatUser
import st.kiwifarms.sneedroid.core.model.RoomPermissions
import st.kiwifarms.sneedroid.core.model.WhisperMessage

/**
 * Wire (de)serialization for the SneedChat WebSocket protocol.
 *
 * Inbound frames are JSON objects keyed by a single top-level field; [parse] dispatches
 * on that key. Outbound frames are plain text (sometimes with a `/command` prefix);
 * [encode] builds them. See docs/02-protocol.md.
 */
object ChatProtocol {

    /** Lenient on purpose: the server may add fields we don't model. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        // Do NOT escape non-ASCII — the edit payload must preserve Unicode (matches the bot).
    }

    @Serializable
    private data class EditPayload(
        @SerialName("uuid") val uuid: String,
        @SerialName("message") val message: String,
    )

    /**
     * Parse one inbound text frame into a [ChatEvent]. Never throws — malformed or
     * unclassifiable frames come back as [ChatEvent.Unknown].
     */
    fun parse(text: String): ChatEvent {
        val root: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return ChatEvent.Unknown(text)
        }
        return try {
            when {
                "messages" in root ->
                    ChatEvent.Messages(json.decodeFromJsonElement(listSerializer(), root.getValue("messages")))

                "users" in root -> {
                    val map: Map<String, ChatUser> = json.decodeFromJsonElement(usersSerializer(), root.getValue("users"))
                    ChatEvent.UsersJoined(map.values.toList())
                }

                "user" in root -> {
                    // { "user": { "1337": false, ... } } — keys are parted user ids.
                    val ids = root.getValue("user").jsonObject.keys.mapNotNull { it.toLongOrNull() }
                    ChatEvent.UsersParted(ids)
                }

                "permissions" in root ->
                    ChatEvent.Permissions(json.decodeFromJsonElement(RoomPermissions.serializer(), root.getValue("permissions")))

                "system" in root ->
                    ChatEvent.System(root.getValue("system").jsonPrimitive.contentOrNull ?: "")

                "delete" in root ->
                    ChatEvent.Deleted(json.decodeFromJsonElement(stringListSerializer(), root.getValue("delete")))

                "whisper" in root ->
                    ChatEvent.Whisper(json.decodeFromJsonElement(WhisperMessage.serializer(), root.getValue("whisper")))

                "motd" in root ->
                    ChatEvent.Motd(json.decodeFromJsonElement(ChatMessage.serializer(), root.getValue("motd")))

                else -> ChatEvent.Unknown(text)
            }
        } catch (_: Exception) {
            ChatEvent.Unknown(text)
        }
    }

    /** Build the raw text frame for an outbound [ChatCommand]. */
    fun encode(command: ChatCommand): String = when (command) {
        is ChatCommand.Join -> "/join ${command.roomId}"
        is ChatCommand.Send -> command.text
        is ChatCommand.Whisper ->
            if (command.recipientId != null) "/w ${command.recipientId} ${command.text}"
            else "/w @${command.username}, ${command.text}"
        is ChatCommand.Edit -> "/edit " + json.encodeToString(EditPayload(command.uuid, command.text))
        is ChatCommand.Delete -> "/delete ${command.uuid}"
        is ChatCommand.Motd -> "/motd ${command.uuid}"
    }

    // Serializers kept here to avoid sprinkling reified generics around.
    private fun listSerializer() = ListSerializer(ChatMessage.serializer())

    private fun stringListSerializer() = ListSerializer(String.serializer())

    private fun usersSerializer() = MapSerializer(String.serializer(), ChatUser.serializer())
}
