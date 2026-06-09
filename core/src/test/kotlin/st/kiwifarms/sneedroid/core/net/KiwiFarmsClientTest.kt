package st.kiwifarms.sneedroid.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KiwiFarmsClientTest {

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

    private fun client(jar: InMemoryCookieJar) = KiwiFarmsClient(
        baseUrl = server.url("/").toString().toHttpUrl(),
        cookieJar = jar,
        cookieDomain = server.hostName,
        nonceBase = { 0L }, // deterministic PoW start
    )

    @Test
    fun `direct login succeeds on 303`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""<html data-csrf="csrf-1" data-logged-in="false"></html>"""))
        server.enqueue(MockResponse().setResponseCode(303)) // login POST -> See Other

        val jar = InMemoryCookieJar()
        val outcome = client(jar).login("marlin", "hunter2")
        assertEquals(LoginOutcome.Success, outcome)

        val getLogin = server.takeRequest()
        assertEquals("/login", getLogin.path)
        val postLogin: RecordedRequest = server.takeRequest()
        assertEquals("/login/login", postLogin.path)
        val body = postLogin.body.readUtf8()
        assertTrue(body.contains("_xfToken=csrf-1"))
        assertTrue(body.contains("login=marlin"))
        assertTrue(body.contains("remember=1"))
    }

    @Test
    fun `login solves a 203 PoW challenge then logs in`() = runBlocking {
        // 1) GET /login -> 203 (challenge required)
        server.enqueue(MockResponse().setResponseCode(203).setBody("challenge required"))
        // 2) GET / -> low-difficulty sssg challenge (difficulty 8 solves instantly)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""<html id="sssg" data-sssg-challenge="salt-xyz" data-sssg-difficulty="8"></html>"""),
        )
        // 3) POST /.sssg/api/answer -> auth token
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"auth":"CLEARANCE-TOKEN"}"""))
        // 4) GET /login (retry) -> csrf, not logged in
        server.enqueue(MockResponse().setResponseCode(200).setBody("""<html data-csrf="csrf-2" data-logged-in="false"></html>"""))
        // 5) POST /login/login -> 303 success
        server.enqueue(MockResponse().setResponseCode(303))

        val jar = InMemoryCookieJar()
        client(jar).login("marlin", "hunter2")

        // The whole sequence was consumed in order.
        assertEquals("/login", server.takeRequest().path)
        assertEquals("/", server.takeRequest().path)
        val sssg = server.takeRequest()
        assertEquals("/.sssg/api/answer", sssg.path)
        val sssgBody = sssg.body.readUtf8()
        assertTrue(sssgBody.startsWith("a=salt-xyz&b=")) { "sssg form was: $sssgBody" }
        assertEquals("/login", server.takeRequest().path)
        assertEquals("/login/login", server.takeRequest().path)

        // Clearance cookie was captured for reuse on the WebSocket.
        assertTrue(jar.snapshot().any { it.name == "sssg_clearance" && it.value == "CLEARANCE-TOKEN" })
    }

    @Test
    fun `login failure surfaces the server error text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""<html data-csrf="csrf-3" data-logged-in="false"></html>"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<html data-logged-in="false"></html><div class="blockMessage blockMessage--error blockMessage--iconic">Incorrect password.</div>""",
            ),
        )

        val jar = InMemoryCookieJar()
        val ex = assertThrows(KiwiFarmsLoginException::class.java) {
            runBlocking { client(jar).login("marlin", "wrong") }
        }
        assertTrue(ex.message!!.contains("Incorrect password"))
    }

    @Test
    fun `login detects 2FA, then submitTwoFactor completes`() = runBlocking {
        // 1) GET /login -> csrf, not logged in
        server.enqueue(MockResponse().setResponseCode(200).setBody("""<html data-csrf="csrf-a" data-logged-in="false"></html>"""))
        // 2) POST /login/login -> 303 redirect to the two-step page
        server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "/login/two-step"))

        val jar = InMemoryCookieJar()
        val client = client(jar)
        val first = client.login("marlin", "hunter2")
        assertTrue(first is LoginOutcome.TwoFactorRequired) { "expected 2FA, got $first" }

        assertEquals("/login", server.takeRequest().path)
        assertEquals("/login/login", server.takeRequest().path)

        // 3) GET /login/two-step -> fresh CSRF (as a hidden input this time)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""<html><form action="/login/two-step"><input type="hidden" name="_xfToken" value="csrf-b"></form></html>"""),
        )
        // 4) POST /login/two-step -> success: redirect home + session cookie
        server.enqueue(
            MockResponse().setResponseCode(303)
                .addHeader("Location", "/")
                .addHeader("Set-Cookie", "xf_session=sess-123; Path=/"),
        )

        val second = client.submitTwoFactor(code = "123456", trust = true)
        assertEquals(LoginOutcome.Success, second)

        assertEquals("/login/two-step", server.takeRequest().path)
        val post2 = server.takeRequest()
        assertEquals("/login/two-step", post2.path)
        val body = post2.body.readUtf8()
        assertTrue(body.contains("_xfToken=csrf-b")) { "should use the fresh two-step token: $body" }
        assertTrue(body.contains("code=123456"))
        assertTrue(body.contains("provider=totp"))
        assertTrue(body.contains("confirm=1"))
        assertTrue(body.contains("trust=1"))
        assertTrue(jar.snapshot().any { it.name == "xf_session" })
    }
}
