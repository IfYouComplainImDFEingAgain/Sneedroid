package st.kiwifarms.sneedroid.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * A JWE-shaped token whose header decodes to the real thing, so the extractor is exercised on
 * the property it actually selects by rather than on a fixed string.
 */
private fun jwe(payload: String = "body"): String {
    val header = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"alg":"ECDH-ES","enc":"A256GCM"}""".toByteArray())
    val rest = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
    return "$header.$rest.$rest.$rest.$rest"
}

/** The shape Spur serves: a minified bundle with the assessment as one string among many. */
private fun bundle(assessment: String, siteKey: String = "k".repeat(400)): String =
    """var k="0.0.23",I="js",L="$siteKey",R="019fe75f-beab-7e28-8c4f-e4d87732b3a3",""" +
        """M="$assessment",F="",N="arw5uaabt7tv7pvlpyuiyt7e3b3tfm5daaaaempw2c6";""" +
        """class q extends Error{}"""

class HttpMonocleProviderTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun provider(killswitch: () -> Boolean = { false }) = HttpMonocleProvider(
        client = OkHttpClient.Builder().followRedirects(false).build(),
        killswitchBlocked = killswitch,
        loaderUrl = server.url("/d/mcl.js").toString(),
    )

    @Test
    fun `extracts the assessment from the served bundle`() = runBlocking {
        val expected = jwe()
        server.enqueue(MockResponse().setBody(bundle(expected)))

        assertEquals(expected, provider().assessment("site-key"))

        val request = server.takeRequest()
        assertEquals("site-key", request.requestUrl?.queryParameter("tk"))
    }

    @Test
    fun `follows the redirect Spur always answers with`() = runBlocking {
        // The shared OkHttp client disables redirects so login 303s stay observable; the
        // provider has to re-enable them for itself or every fetch returns a 301 body.
        val expected = jwe()
        server.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", server.url("/real/mcl.js").toString())
        )
        server.enqueue(MockResponse().setBody(bundle(expected)))

        assertEquals(expected, provider().assessment("site-key"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `does not mistake the site key for the assessment`() = runBlocking {
        // The site key is a 421-char opaque string sitting in the same bundle, so picking the
        // longest base64-looking literal would pick the wrong one.
        val expected = jwe()
        server.enqueue(MockResponse().setBody(bundle(expected, siteKey = "A".repeat(600))))

        assertEquals(expected, provider().assessment("site-key"))
    }

    @Test
    fun `survives the minifier renaming the constant`() = runBlocking {
        // Matching on `M="..."` would break on any Spur rebuild; the extractor matches on the
        // value's shape instead, so a renamed variable must still work.
        val expected = jwe()
        server.enqueue(MockResponse().setBody(bundle(expected).replace("M=", "zQ7=")))

        assertEquals(expected, provider().assessment("site-key"))
    }

    @Test
    fun `explains itself when the bundle carries no assessment`() {
        server.enqueue(MockResponse().setBody("""var k="0.0.23",I="js";"""))

        val error = assertThrows(MonocleUnavailable::class.java) {
            runBlocking { provider().assessment("site-key") }
        }
        assertTrue(
            error.message!!.contains("no assessment"),
            "expected a message naming the cause, got: ${error.message}",
        )
    }

    @Test
    fun `reports a loader error rather than returning junk`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("nope"))

        val error = assertThrows(MonocleUnavailable::class.java) {
            runBlocking { provider().assessment("site-key") }
        }
        assertTrue(error.message!!.contains("503"), "expected the status, got: ${error.message}")
    }

    @Test
    fun `refuses to talk to Spur while the killswitch is blocking`() {
        // The request goes to a third party rather than to KiwiFarms, so it bypasses the
        // client's own killswitch check; leaking the real IP here is the exact failure the
        // killswitch exists to prevent.
        val error = assertThrows(MonocleUnavailable::class.java) {
            runBlocking { provider(killswitch = { true }).assessment("site-key") }
        }
        assertTrue(error.message!!.contains("killswitch"), "got: ${error.message}")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `keeps Spur's cookies out of the shared jar`() = runBlocking {
        val jar = InMemoryCookieJar(emptyList())
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "spur_session=abc; Path=/")
                .setBody(bundle(jwe()))
        )

        HttpMonocleProvider(
            client = OkHttpClient.Builder().cookieJar(jar).build(),
            loaderUrl = server.url("/d/mcl.js").toString(),
        ).assessment("site-key")

        assertTrue(
            jar.snapshot().none { it.name == "spur_session" },
            "Spur's cookies must not reach a jar that gets persisted: ${jar.snapshot()}",
        )
    }
}

class FallbackMonocleProviderTest {

    private class Failing(private val message: String) : MonocleProvider {
        var calls = 0
        override suspend fun assessment(siteKey: String): String {
            calls++
            throw MonocleUnavailable(message)
        }
    }

    private class Working(private val value: String) : MonocleProvider {
        var calls = 0
        override suspend fun assessment(siteKey: String): String {
            calls++
            return value
        }
    }

    @Test
    fun `uses the primary and never touches the fallback`() = runBlocking {
        val primary = Working("fast")
        val fallback = Working("slow")

        assertEquals("fast", FallbackMonocleProvider(primary, fallback).assessment("k"))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `falls back and says why when the fast path breaks`() = runBlocking {
        val primary = Failing("Spur may have changed the bundle")
        val fallback = Working("slow")
        var reported: String? = null

        val value = FallbackMonocleProvider(primary, fallback) { reported = it }.assessment("k")

        assertEquals("slow", value)
        assertEquals(1, fallback.calls)
        assertNotNull(reported)
        assertTrue(reported!!.contains("bundle"), "the reason should reach the debug log: $reported")
    }

    @Test
    fun `propagates the fallback's failure when both are down`() {
        val error = assertThrows(MonocleUnavailable::class.java) {
            runBlocking {
                FallbackMonocleProvider(Failing("primary down"), Failing("no WebView")).assessment("k")
            }
        }
        assertEquals("no WebView", error.message)
    }

    @Test
    fun `invalidate reaches both, since either may be holding a stale one`() = runBlocking {
        var primaryCleared = false
        var fallbackCleared = false
        val primary = object : MonocleProvider {
            override suspend fun assessment(siteKey: String) = "a"
            override suspend fun invalidate() { primaryCleared = true }
        }
        val fallback = object : MonocleProvider {
            override suspend fun assessment(siteKey: String) = "b"
            override suspend fun invalidate() { fallbackCleared = true }
        }

        FallbackMonocleProvider(primary, fallback).invalidate()

        assertTrue(primaryCleared && fallbackCleared)
    }
}
