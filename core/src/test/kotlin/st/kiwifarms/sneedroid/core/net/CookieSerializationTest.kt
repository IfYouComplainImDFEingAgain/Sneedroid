package st.kiwifarms.sneedroid.core.net

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CookieSerializationTest {

    @Test
    fun `round-trips the session cookies`() {
        val url = "https://kiwifarms.st/".toHttpUrl()
        val original = listOf(
            Cookie.Builder().name("xf_session").value("abc123").domain("kiwifarms.st").path("/")
                .expiresAt(System.currentTimeMillis() + 86_400_000).secure().httpOnly().build(),
            Cookie.Builder().name("ttrs_clearance").value("tok-xyz").domain("kiwifarms.st").path("/").build(),
        )

        val restored = CookieSerialization.decode(CookieSerialization.encode(original))

        assertEquals(original.size, restored.size)
        val session = restored.first { it.name == "xf_session" }
        assertEquals("abc123", session.value)
        assertEquals("kiwifarms.st", session.domain)
        assertTrue(session.secure)
        assertTrue(session.httpOnly)
        // The restored cookies still match the request URL they were scoped to.
        assertTrue(restored.all { it.matches(url) })
    }

    @Test
    fun `decode of garbage yields empty list, not a crash`() {
        assertTrue(CookieSerialization.decode("not json").isEmpty())
        assertTrue(CookieSerialization.decode("").isEmpty())
    }
}
