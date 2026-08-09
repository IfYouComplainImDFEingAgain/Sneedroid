package st.kiwifarms.sneedroid.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import st.kiwifarms.sneedroid.core.pow.KiwiFlare
import st.kiwifarms.sneedroid.core.pow.KiwiFlareChallenge
import st.kiwifarms.sneedroid.core.pow.KiwiFlareSolution
import st.kiwifarms.sneedroid.core.pow.PowAlgorithm
import st.kiwifarms.sneedroid.core.pow.PowVariant

/** Coarse progress steps the login flow passes through, for UI feedback. */
enum class LoginPhase { FetchingPage, SolvingChallenge, VerifyingBrowser, SubmittingChallenge, SigningIn }

/** Result of a login attempt. */
sealed interface LoginOutcome {
    data object Success : LoginOutcome
    /** Account has two-factor enabled; call [KiwiFarmsClient.submitTwoFactor] with a code. */
    data class TwoFactorRequired(val provider: String = "totp") : LoginOutcome
}

/** Thrown when login fails; [message] is the server's error text when available. */
class KiwiFarmsLoginException(message: String) : Exception(message)

/** Thrown when the PoW challenge can't be solved/submitted. */
class KiwiFlareException(message: String) : Exception(message)

/**
 * The gate refused our Spur Monocle assessment (`monocle_required` / `monocle_invalid` / `MCL`).
 *
 * Kept distinct from a plain [KiwiFlareException] because the responses differ: a stale
 * assessment is worth one retry with a freshly minted one, whereas a refused *valid* assessment
 * means Spur judged the connection (VPN, datacentre or residential proxy) and retrying is futile.
 */
class MonocleRejectedException(message: String, val retryable: Boolean) : Exception(message)

/**
 * HTTP side of the protocol: solve the Tartarus/KiwiFlare PoW and perform native login,
 * accumulating cookies (xf_user, xf_session, *_clearance) into [cookieJar] for the
 * WebSocket to reuse. Ports `KiwiFlare.cs` + `KfTokenService.cs`.
 *
 * @param baseUrl the site root, e.g. `https://kiwifarms.st` (overridable for tests).
 * @param cookieDomain the domain to scope manually-built clearance cookies to.
 */
class KiwiFarmsClient(
    private val baseUrl: HttpUrl,
    private val cookieJar: InMemoryCookieJar,
    private val cookieDomain: String = baseUrl.host,
    private val httpClient: OkHttpClient = defaultClient(cookieJar),
    // Masked to 48 bits: a non-negative random start (the server 500s on a negative nonce)
    // with ample headroom so the solver's upward stride can't overflow into negatives.
    private val nonceBase: () -> Long = { java.util.Random().nextLong() and 0xFFFF_FFFF_FFFFL },
    // IP killswitch: returns true when every request to the target server must be refused (e.g.
    // the killswitch is on and no VPN is detected). Checked before any socket is opened, so even
    // the app-start session check can't touch the network while blocked.
    private val killswitchBlocked: () -> Boolean = { false },
    // Mints the Spur Monocle assessment the Tartarus gate now demands alongside the PoW nonce.
    // Needs a browser engine, so :core (plain JVM) can only declare the seam; the Android app
    // supplies a WebView-backed implementation. See [MonocleProvider].
    private val monocle: MonocleProvider = UnsupportedMonocleProvider,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Set when a login attempt lands on the two-step page; consumed by [submitTwoFactor]. */
    @Volatile
    private var pendingTwoStepUrl: HttpUrl? = null

    constructor(
        domain: String,
        cookieJar: InMemoryCookieJar,
        killswitchBlocked: () -> Boolean = { false },
        monocle: MonocleProvider = UnsupportedMonocleProvider,
    ) : this(
        "https://$domain".toHttpUrl(),
        cookieJar,
        domain,
        killswitchBlocked = killswitchBlocked,
        monocle = monocle,
    )

    /**
     * Solve the PoW if the site is currently presenting a challenge; otherwise no-op.
     *
     * Runs at most twice. A cached Monocle assessment the gate has stopped honouring looks
     * exactly like a good one until it is refused, so the refusal is the signal to mint a fresh
     * one — and because a rejected POST burns the salt, the retry has to fetch and re-solve a
     * whole new challenge rather than resubmit the old one.
     */
    suspend fun ensureClearance(onPhase: (LoginPhase) -> Unit = {}) {
        var lastRejection: MonocleRejectedException? = null
        repeat(2) {
            val challenge = fetchChallenge() ?: return
            try {
                clearChallenge(challenge, onPhase)
                return
            } catch (e: MonocleRejectedException) {
                if (!e.retryable) throw e
                lastRejection = e
                // Stale assessment: drop it so the next pass mints a new one.
                monocle.invalidate()
            }
        }
        throw lastRejection ?: KiwiFlareException("could not clear the PoW gate")
    }

    private suspend fun clearChallenge(challenge: KiwiFlareChallenge, onPhase: (LoginPhase) -> Unit) {
        if (!challenge.algorithm.equals(PowAlgorithm.SHA256, ignoreCase = true)) {
            // Fail by name rather than solve the wrong digest and read the rejection as a
            // protocol bug. Argon2id needs a different solver and its own cost parameters.
            throw KiwiFlareException(
                "the gate switched to the '${challenge.algorithm}' proof-of-work algorithm, " +
                    "which this client does not implement"
            )
        }
        // Minted before the solve: it is independent of the salt, and doing it first means a
        // refusal costs no CPU. It is also the step most likely to need the user's attention.
        val assessment = challenge.monocleKey?.let { key ->
            onPhase(LoginPhase.VerifyingBrowser)
            monocle.assessment(key)
        }
        onPhase(LoginPhase.SolvingChallenge)
        val solution = KiwiFlare.solve(challenge, baseNonce = nonceBase())
        onPhase(LoginPhase.SubmittingChallenge)
        when (challenge.variant) {
            PowVariant.SSSG -> {
                val token = submitSssg(solution)
                cookieJar.add(clearanceCookie("sssg_clearance", token))
            }
            PowVariant.TTRS -> {
                // sets ttrs_clearance via the jar (and we add it explicitly)
                submitTtrs(solution, assessment)
            }
        }
    }

    /** GET `/` and extract a challenge, or null if none is presented. */
    suspend fun fetchChallenge(): KiwiFlareChallenge? {
        val body = get(baseUrl).body
        return HtmlScrape.parseChallenge(body)
    }

    private suspend fun submitSssg(solution: KiwiFlareSolution): String {
        val form = FormBody.Builder().add("a", solution.salt).add("b", solution.nonce.toString()).build()
        val resp = post(baseUrl.newBuilder().encodedPath("/.sssg/api/answer").build(), form)
        val obj = parseAnswer(resp, "/.sssg/api/answer")
        obj["error"]?.let { throw KiwiFlareException("sssg error: ${it.jsonPrimitive.content}") }
        return obj["auth"]?.jsonPrimitive?.content
            ?: throw KiwiFlareException("sssg response missing auth: ${resp.body}")
    }

    private suspend fun submitTtrs(solution: KiwiFlareSolution, assessment: String?): String {
        val form = FormBody.Builder()
            .add("salt", solution.salt)
            .add("nonce", solution.nonce.toString())
            .apply { if (assessment != null) add("monocle", assessment) }
            .build()
        val resp = post(baseUrl.newBuilder().encodedPath("/.ttrs/challenge").build(), form)
        val obj = parseAnswer(resp, "/.ttrs/challenge")
        val success = obj["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (!success) {
            val reason = obj["reason"]?.jsonPrimitive?.content ?: "unknown"
            monocleRejection(reason)?.let { throw it }
            throw KiwiFlareException("ttrs rejected solution: $reason")
        }
        // The clearance cookie is delivered via Set-Cookie; the jar captures it, but we
        // also parse it defensively in case the jar's domain matching differs.
        val token = resp.setCookie.firstNotNullOfOrNull { header ->
            Regex("ttrs_clearance=([^;]+)").find(header)?.groupValues?.get(1)
        }
        if (token != null) cookieJar.add(clearanceCookie("ttrs_clearance", token))
        return token ?: ""
    }

    /**
     * Parse a PoW answer response, failing with a *useful* message instead of a cryptic
     * "unexpected end of input" when the Tartarus/KiwiFlare gate returns an empty body or a
     * non-JSON page (rate-limit, IP block, gateway error). [endpoint] is named for the error.
     *
     * A JSON body is parsed whatever the status code: the gate reports a refused Monocle
     * assessment as HTTP 403 with `{"success":false,"reason":"monocle_invalid"}`, and rejecting
     * that on the status alone would throw away the one field that says what to do about it.
     */
    private fun parseAnswer(resp: Resp, endpoint: String): kotlinx.serialization.json.JsonObject {
        if (resp.body.isBlank()) {
            throw KiwiFlareException("PoW gate at $endpoint returned HTTP ${resp.code} (empty body).")
        }
        return runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrElse {
            throw KiwiFlareException("PoW gate at $endpoint returned non-JSON (HTTP ${resp.code}): ${resp.body.take(200)}")
        }
    }

    /**
     * Map a gate rejection reason to a [MonocleRejectedException], or null if it isn't about
     * browser verification.
     *
     * `monocle_required` and `monocle_invalid` mean the assessment was missing, malformed or
     * stale — worth one retry with a freshly minted one. `MCL` means Spur judged the assessment
     * genuine and the *connection* unacceptable (VPN, datacentre or residential proxy), which no
     * amount of re-minting will change.
     */
    private fun monocleRejection(reason: String): MonocleRejectedException? = when (reason) {
        "monocle_required" -> MonocleRejectedException(
            "the gate demanded browser verification and none was supplied", retryable = true
        )
        "monocle_invalid" -> MonocleRejectedException(
            "the gate refused the browser verification token as invalid or expired", retryable = true
        )
        "MCL" -> MonocleRejectedException(
            "browser verification was refused — the gate rejects this connection. " +
                "A VPN, datacentre or residential proxy address is the usual cause.",
            retryable = false,
        )
        else -> null
    }

    /** True if the stored cookies already represent a logged-in session. */
    suspend fun isLoggedIn(): Boolean = HtmlScrape.isLoggedIn(getLoginPage())

    /**
     * Perform native login. Returns [LoginOutcome.Success], or
     * [LoginOutcome.TwoFactorRequired] if the account has 2FA (then call
     * [submitTwoFactor]). Throws [KiwiFarmsLoginException] on bad credentials.
     */
    suspend fun login(
        username: String,
        password: String,
        onPhase: (LoginPhase) -> Unit = {},
    ): LoginOutcome {
        pendingTwoStepUrl = null
        val page = getLoginPage(onPhase)
        if (HtmlScrape.isLoggedIn(page)) return LoginOutcome.Success
        val csrf = HtmlScrape.csrfToken(page)
            ?: throw KiwiFarmsLoginException("login page missing CSRF token")
        onPhase(LoginPhase.SigningIn)
        val form = FormBody.Builder()
            .add("_xfToken", csrf)
            .add("login", username)
            .add("password", password)
            .add("_xfRedirect", "$baseUrl/")
            .add("remember", "1")
            .build()
        val resp = post(baseUrl.newBuilder().encodedPath("/login/login").build(), form)
        return interpretLogin(resp)
    }

    private fun interpretLogin(resp: Resp): LoginOutcome {
        // Redirect (followRedirects is off): inspect where it points.
        if (resp.code in 300..399) {
            val target = resp.location?.let { baseUrl.resolve(it) }
            if (target != null && target.encodedPath.contains("two-step")) {
                pendingTwoStepUrl = target
                return LoginOutcome.TwoFactorRequired()
            }
            return LoginOutcome.Success // redirected home
        }
        // 200: either the two-step page rendered inline, an authenticated page, or an error.
        if (HtmlScrape.isTwoStepPage(resp.body)) {
            pendingTwoStepUrl = baseUrl.newBuilder().encodedPath("/login/two-step").build()
            return LoginOutcome.TwoFactorRequired()
        }
        if (HtmlScrape.isLoggedIn(resp.body)) return LoginOutcome.Success
        throw KiwiFarmsLoginException(HtmlScrape.loginError(resp.body) ?: "login failed (${resp.code})")
    }

    /**
     * Complete two-factor login with a user-entered [code]. [trust] sets the
     * xf_tfa_trust cookie so future logins on this device skip 2FA. Must be called after
     * [login] returned [LoginOutcome.TwoFactorRequired].
     */
    suspend fun submitTwoFactor(
        code: String,
        trust: Boolean = false,
        provider: String = "totp",
        onPhase: (LoginPhase) -> Unit = {},
    ): LoginOutcome {
        val url = pendingTwoStepUrl
            ?: throw KiwiFarmsLoginException("no pending two-factor challenge")
        onPhase(LoginPhase.FetchingPage)
        val page = get(url).body
        val csrf = HtmlScrape.csrfToken(page)
            ?: throw KiwiFarmsLoginException("two-step page missing CSRF token")
        onPhase(LoginPhase.SigningIn)
        val builder = FormBody.Builder()
            .add("_xfToken", csrf)
            .add("code", code.trim())
            .add("provider", provider)
            .add("confirm", "1")
            .add("remember", "1")
            .add("_xfRedirect", "$baseUrl/")
        if (trust) builder.add("trust", "1")
        val resp = post(baseUrl.newBuilder().encodedPath("/login/two-step").build(), builder.build())

        // Success: redirected away from two-step, or session cookies now present.
        if (resp.code in 300..399) {
            val target = resp.location?.let { baseUrl.resolve(it) }
            if (target == null || !target.encodedPath.contains("two-step")) {
                pendingTwoStepUrl = null
                return LoginOutcome.Success
            }
        }
        if (hasSessionCookie()) {
            pendingTwoStepUrl = null
            return LoginOutcome.Success
        }
        throw KiwiFarmsLoginException(HtmlScrape.loginError(resp.body) ?: "Invalid two-factor code")
    }

    private fun hasSessionCookie(): Boolean =
        cookieJar.snapshot().any { it.name == "xf_session" || it.name == "xf_user" }

    /** GET `/login`, transparently solving a PoW challenge first if the server demands one (203). */
    private suspend fun getLoginPage(onPhase: (LoginPhase) -> Unit = {}): String {
        onPhase(LoginPhase.FetchingPage)
        val url = baseUrl.newBuilder().encodedPath("/login").build()
        var resp = get(url)
        if (resp.code == 203) {
            ensureClearance(onPhase)
            resp = get(url)
        }
        return resp.body
    }

    private fun clearanceCookie(name: String, value: String): Cookie =
        Cookie.Builder().name(name).value(value).domain(cookieDomain).path("/").build()

    // ---- tiny blocking-call wrappers ----

    private data class Resp(val code: Int, val body: String, val setCookie: List<String>, val location: String?)

    private suspend fun get(url: HttpUrl): Resp = execute(Request.Builder().url(url).build())

    private suspend fun post(url: HttpUrl, form: FormBody): Resp =
        execute(Request.Builder().url(url).post(form).build())

    private suspend fun execute(request: Request): Resp = withContext(Dispatchers.IO) {
        if (killswitchBlocked()) throw java.io.IOException("IP killswitch on — connect a VPN to reach KiwiFarms")
        httpClient.newCall(request).execute().use { r ->
            Resp(r.code, r.body?.string().orEmpty(), r.headers("Set-Cookie"), r.header("Location"))
        }
    }

    companion object {
        /**
         * A browser-like User-Agent. The PoW/anti-DDoS gate is more permissive to clients
         * that don't look like bots; OkHttp's default "okhttp/x.y" is an obvious tell.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /**
         * Default client: shares [cookieJar], disables auto-redirect so a 303 login success
         * and the ttrs Set-Cookie are observable (matches the bot), and sends a browser
         * [USER_AGENT] so the PoW gate doesn't single the app out as a bot.
         */
        fun defaultClient(cookieJar: InMemoryCookieJar): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }
}
