package st.kiwifarms.sneedroid.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/** A stub provider that counts mints and can be told to fail. */
private class FakeMonocle(private val values: List<String> = listOf("assessment-1", "assessment-2")) :
    MonocleProvider {
    val mints = AtomicInteger()
    override suspend fun assessment(siteKey: String): String {
        val index = mints.getAndIncrement()
        return values.getOrElse(index) { values.last() }
    }
}

class CachedMonocleProviderTest {

    @Test
    fun `reuses one assessment until the ttl expires`() = runBlocking {
        val inner = FakeMonocle()
        var now = 0L
        val cached = CachedMonocleProvider(inner, ttl = 10.minutes, clock = { now })

        assertEquals("assessment-1", cached.assessment("k"))
        assertEquals("assessment-1", cached.assessment("k"))
        assertEquals(1, inner.mints.get())

        now += 10.minutes.inWholeNanoseconds
        assertEquals("assessment-2", cached.assessment("k"))
        assertEquals(2, inner.mints.get())
    }

    @Test
    fun `invalidate forces a fresh mint`() = runBlocking {
        val inner = FakeMonocle()
        val cached = CachedMonocleProvider(inner, ttl = 10.minutes, clock = { 0L })

        assertEquals("assessment-1", cached.assessment("k"))
        cached.invalidate()
        assertEquals("assessment-2", cached.assessment("k"))
        assertEquals(2, inner.mints.get())
    }
}

class HtmlScrapeMonocleTest {

    @Test
    fun `parses the monocle site key and algorithm off the challenge page`() {
        val html = """
            <html id="ttrs" data-ttrs-challenge="abc_def_16" data-ttrs-difficulty="16"
                  data-ttrs-algorithm="sha256" data-ttrs-monocle-key="SITE-KEY">
        """.trimIndent()
        val challenge = HtmlScrape.parseChallenge(html)!!
        assertEquals("SITE-KEY", challenge.monocleKey)
        assertEquals("sha256", challenge.algorithm)
    }

    @Test
    fun `a page with no monocle key yields a null key and the sha256 default`() {
        val html = """<html id="ttrs" data-ttrs-challenge="abc" data-ttrs-difficulty="8">"""
        val challenge = HtmlScrape.parseChallenge(html)!!
        assertEquals(null, challenge.monocleKey)
        assertEquals("sha256", challenge.algorithm)
    }

    @Test
    fun `a blank monocle key counts as absent`() {
        val html = """<html id="ttrs" data-ttrs-challenge="abc" data-ttrs-difficulty="8" data-ttrs-monocle-key="">"""
        assertEquals(null, HtmlScrape.parseChallenge(html)!!.monocleKey)
    }
}

class KiwiFarmsClientMonocleTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(jar: InMemoryCookieJar, monocle: MonocleProvider) = KiwiFarmsClient(
        baseUrl = server.url("/").toString().toHttpUrl(),
        cookieJar = jar,
        cookieDomain = server.hostName,
        nonceBase = { 0L },
        monocle = monocle,
    )

    /** Difficulty 8 solves instantly, so these tests spend no real time on the PoW. */
    private fun challengePage(monocleKey: String? = "SITE-KEY", algorithm: String = "sha256") =
        buildString {
            append("""<html id="ttrs" data-ttrs-challenge="salt" data-ttrs-difficulty="8" """)
            append("""data-ttrs-algorithm="$algorithm"""")
            if (monocleKey != null) append(""" data-ttrs-monocle-key="$monocleKey"""")
            append(">")
        }

    @Test
    fun `the assessment is sent as a monocle form field`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengePage()))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true}""")
                .addHeader("Set-Cookie", "ttrs_clearance=cleared; Path=/")
        )

        val jar = InMemoryCookieJar()
        client(jar, FakeMonocle()).ensureClearance()

        server.takeRequest() // GET /
        val post = server.takeRequest()
        assertEquals("/.ttrs/challenge", post.path)
        val body = post.body.readUtf8()
        assertTrue(body.contains("monocle=assessment-1"), "body was: $body")
        assertTrue(jar.snapshot().any { it.name == "ttrs_clearance" })
    }

    @Test
    fun `no monocle field is sent when the page carries no site key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengePage(monocleKey = null)))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true}""")
                .addHeader("Set-Cookie", "ttrs_clearance=cleared; Path=/")
        )

        val monocle = FakeMonocle()
        client(InMemoryCookieJar(), monocle).ensureClearance()

        server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("monocle="))
        assertEquals(0, monocle.mints.get(), "should not mint when the gate isn't asking")
    }

    @Test
    fun `a stale assessment is retried once with a fresh challenge and a fresh mint`() = runBlocking {
        // First pass: challenge, then a 403 monocle_invalid.
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengePage()))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"success":false,"reason":"monocle_invalid"}""")
        )
        // Second pass: a whole new challenge, then success.
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengePage()))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true}""")
                .addHeader("Set-Cookie", "ttrs_clearance=cleared; Path=/")
        )

        val inner = FakeMonocle()
        // Cached, so the retry only re-mints because the client invalidated it.
        val jar = InMemoryCookieJar()
        client(jar, CachedMonocleProvider(inner, ttl = 10.minutes, clock = { 0L })).ensureClearance()

        assertEquals(2, inner.mints.get(), "the refusal should have dropped the cached assessment")
        assertEquals(4, server.requestCount, "the salt is burned; the retry must re-fetch it")
        assertTrue(jar.snapshot().any { it.name == "ttrs_clearance" })
    }

    @Test
    fun `a refused connection is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengePage()))
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"success":false,"reason":"MCL"}""")
        )

        val inner = FakeMonocle()
        val error = assertThrows(MonocleRejectedException::class.java) {
            runBlocking { client(InMemoryCookieJar(), inner).ensureClearance() }
        }
        assertFalse(error.retryable)
        assertEquals(1, inner.mints.get())
        assertEquals(2, server.requestCount, "no second attempt for a judged connection")
    }

    @Test
    fun `an unimplemented pow algorithm fails by name before any solving`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(challengePage(algorithm = "argon2id"))
        )

        val error = assertThrows(KiwiFlareException::class.java) {
            runBlocking { client(InMemoryCookieJar(), FakeMonocle()).ensureClearance() }
        }
        assertTrue(error.message!!.contains("argon2id"), "message was: ${error.message}")
    }

    @Test
    fun `the default provider explains itself rather than posting without a field`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengePage()))

        assertThrows(MonocleUnavailable::class.java) {
            runBlocking {
                KiwiFarmsClient(
                    baseUrl = server.url("/").toString().toHttpUrl(),
                    cookieJar = InMemoryCookieJar(),
                    cookieDomain = server.hostName,
                    nonceBase = { 0L },
                ).ensureClearance()
            }
        }
        assertEquals(1, server.requestCount, "should not have POSTed a doomed solution")
    }
}
