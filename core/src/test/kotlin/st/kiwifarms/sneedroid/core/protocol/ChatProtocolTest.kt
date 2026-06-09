package st.kiwifarms.sneedroid.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatProtocolTest {

    // ---------- parse: inbound frames ----------

    @Test
    fun `parses a messages frame`() {
        val json = """
            {"messages":[{
              "message_uuid":"u-1","message_id":12345,
              "author":{"id":110635,"username":"felted","avatar_url":"/data/avatars/m/110/110635.jpg"},
              "message":"hi <b>there</b>","message_raw":"hi [b]there[/b]",
              "message_date":1657317093,"message_edit_date":0,"room_id":10
            }]}
        """.trimIndent()
        val event = ChatProtocol.parse(json)
        assertTrue(event is ChatEvent.Messages)
        val m = (event as ChatEvent.Messages).messages.single()
        assertEquals("u-1", m.uuid)
        assertEquals(110635L, m.author.id)
        assertEquals("hi [b]there[/b]", m.raw)
        assertEquals(false, m.isEdited)
    }

    @Test
    fun `parses a users frame as a roster`() {
        val json = """{"users":{"1337":{"id":1337,"username":"Example","avatar_url":"/a.jpg","last_activity":1657316000}}}"""
        val event = ChatProtocol.parse(json)
        assertTrue(event is ChatEvent.UsersJoined)
        val u = (event as ChatEvent.UsersJoined).users.single()
        assertEquals(1337L, u.id)
        assertEquals("Example", u.username)
    }

    @Test
    fun `parses a user part frame into ids`() {
        val json = """{"user":{"1337":false,"42":false}}"""
        val event = ChatProtocol.parse(json)
        assertTrue(event is ChatEvent.UsersParted)
        assertEquals(listOf(1337L, 42L), (event as ChatEvent.UsersParted).ids)
    }

    @Test
    fun `parses a permissions frame`() {
        val json = """{"permissions":{"can_view":true,"can_send":true,"can_edit_own":true,"can_delete_own":true}}"""
        val event = ChatProtocol.parse(json)
        assertTrue(event is ChatEvent.Permissions)
        val p = (event as ChatEvent.Permissions).permissions
        assertTrue(p.canSend)
        assertTrue(p.canEditOwn)
        assertEquals(false, p.canEditOther) // defaulted, absent in JSON
    }

    @Test
    fun `parses a system frame`() {
        val event = ChatProtocol.parse("""{"system":"Welcome to the room"}""")
        assertEquals(ChatEvent.System("Welcome to the room"), event)
    }

    @Test
    fun `parses a delete frame`() {
        val event = ChatProtocol.parse("""{"delete":["a","b","c"]}""")
        assertEquals(ChatEvent.Deleted(listOf("a", "b", "c")), event)
    }

    @Test
    fun `parses a whisper frame and identifies both parties`() {
        val json = """
            {"whisper":{
              "author":{"id":58227,"username":"Dumpster","avatar_url":"/a.jpg"},
              "recipient":{"id":1,"username":"Null","avatar_url":"/b.jpg"},
              "message":"hey","message_raw":"hey","message_date":1773881876
            }}
        """.trimIndent()
        val event = ChatProtocol.parse(json)
        assertTrue(event is ChatEvent.Whisper)
        val w = (event as ChatEvent.Whisper).whisper
        assertEquals(58227L, w.author.id)
        assertEquals(1L, w.recipient.id)
        assertEquals("hey", w.raw)
    }

    @Test
    fun `parses a motd frame as a message`() {
        val json = """{"motd":{"author":{"id":1,"username":"Null"},"message_raw":"rules","message_date":1}}"""
        val event = ChatProtocol.parse(json)
        assertTrue(event is ChatEvent.Motd)
        assertEquals("rules", (event as ChatEvent.Motd).message.raw)
    }

    @Test
    fun `unknown or malformed frames do not throw`() {
        assertTrue(ChatProtocol.parse("""{"somethingNew":123}""") is ChatEvent.Unknown)
        assertTrue(ChatProtocol.parse("not json at all") is ChatEvent.Unknown)
        assertTrue(ChatProtocol.parse("") is ChatEvent.Unknown)
    }

    // ---------- encode: outbound frames ----------

    @Test
    fun `encodes join and plain send`() {
        assertEquals("/join 15", ChatProtocol.encode(ChatCommand.Join(15)))
        assertEquals("hello world", ChatProtocol.encode(ChatCommand.Send("hello world")))
    }

    @Test
    fun `whisper prefers id form, falls back to username form`() {
        assertEquals("/w 1234 hi", ChatProtocol.encode(ChatCommand.Whisper(1234L, "alice", "hi")))
        assertEquals("/w @alice, hi", ChatProtocol.encode(ChatCommand.Whisper(null, "alice", "hi")))
    }

    @Test
    fun `delete and motd`() {
        assertEquals("/delete u-9", ChatProtocol.encode(ChatCommand.Delete("u-9")))
        assertEquals("/motd u-9", ChatProtocol.encode(ChatCommand.Motd("u-9")))
    }

    @Test
    fun `edit emits a json payload that preserves unicode`() {
        val frame = ChatProtocol.encode(ChatCommand.Edit("u-1", "café 🦛 [b]x[/b]"))
        assertTrue(frame.startsWith("/edit "))
        val payload = frame.removePrefix("/edit ")
        // Round-trips back, and the raw frame is not \u-escaped.
        val event = ChatProtocol.json.parseToJsonElement(payload)
        assertTrue(payload.contains("café 🦛")) { "unicode should be literal, got: $payload" }
        assertTrue(event.toString().contains("u-1"))
    }
}
