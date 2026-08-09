package st.kiwifarms.sneedroid.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

/**
 * Obtains a Monocle assessment with a plain HTTP GET — no browser engine at all.
 *
 * Spur's loader is served per-request and **already contains a minted assessment**. Fetching
 * `https://mcl.spur.us/d/mcl.js?tk=<siteKey>` (which 301s to a randomised `*.mcl.io` host)
 * returns a bundle carrying a JWE as a string constant; the SDK's own
 * `refreshWithExistingAssessment()` hands exactly that string to the host page as *the*
 * assessment when it decides not to re-gather signals. Spur mints it server-side from the
 * request itself, which is why no browser is involved.
 *
 * Verified against the live gate: assessments extracted this way clear the Tartarus challenge
 * and produce a working session on repeated independent attempts, and are replayable across
 * challenges exactly like browser-minted ones. A fetch takes ~0.4 s, against ~1 s plus a
 * WebView spin-up for the browser path.
 *
 * **This is a shortcut, not a contract.** Spur documents no such interface. The bundle is
 * minified, so the constant's *name* changes on any rebuild — which is why the extraction
 * below matches on the shape of the value, never on a variable name — and Spur could stop
 * embedding one whenever it likes. Separately, an assessment minted with no browser signals is
 * presumably a weaker one, so Tartarus could begin declining these even while Spur keeps
 * issuing them. Both are why a browser-backed provider should sit behind this one; see
 * [FallbackMonocleProvider].
 */
class HttpMonocleProvider(
    /** Defaults to a client of its own: this talks to Spur, never to KiwiFarms. */
    client: OkHttpClient = OkHttpClient(),
    /**
     * True when the IP killswitch is blocking. Checked here because this request goes to Spur
     * rather than to KiwiFarms, so it never passes through [KiwiFarmsClient]'s own check — and
     * handing a third party the device's real address is exactly what the killswitch exists to
     * prevent.
     */
    private val killswitchBlocked: () -> Boolean = { false },
    /** Spur's loader endpoint. Only overridden by tests. */
    private val loaderUrl: String = "https://mcl.spur.us/d/mcl.js",
) : MonocleProvider {

    /**
     * Derived from the caller's client so the connection pool and any interceptors are shared,
     * but with two deliberate changes: redirects are followed (the shared client disables them
     * so login 303s stay observable, and this endpoint always redirects), and cookies are
     * dropped on the floor rather than being mixed into the KiwiFarms jar — Spur's cookies are
     * no business of a session that gets persisted to disk.
     */
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    override suspend fun assessment(siteKey: String): String = withContext(Dispatchers.IO) {
        if (killswitchBlocked()) {
            throw MonocleUnavailable("IP killswitch on — connect a VPN before browser verification")
        }

        val url: HttpUrl = loaderUrl.toHttpUrl().newBuilder()
            .addQueryParameter("tk", siteKey)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", KiwiFarmsClient.USER_AGENT)
            .header("Accept", "*/*")
            .build()

        val bundle = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw MonocleUnavailable("verification loader returned HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        } catch (e: MonocleUnavailable) {
            throw e
        } catch (e: Exception) {
            throw MonocleUnavailable("could not reach the verification loader: ${e.message}", e)
        }

        extractAssessment(bundle) ?: throw MonocleUnavailable(
            "the verification loader carried no assessment (${bundle.length} bytes) — " +
                "Spur may have changed the bundle"
        )
    }

    private companion object {

        /** A quoted JS string literal that could be a compact JWE: base64url runs, dot-separated. */
        val CANDIDATE = Regex("\"([A-Za-z0-9_-]{16,}(?:\\.[A-Za-z0-9_-]*){2,4})\"")

        /**
         * Pick the assessment out by decoding each candidate's JWE header. The site key is
         * itself a long opaque string in the same bundle, so "the longest base64-looking
         * thing" would not be selective enough.
         */
        fun extractAssessment(bundle: String): String? = CANDIDATE.findAll(bundle)
            .map { it.groupValues[1] }
            .firstOrNull { isJweHeader(it.substringBefore('.')) }

        fun isJweHeader(segment: String): Boolean {
            if (segment.isEmpty()) return false
            val json = runCatching {
                String(Base64.getUrlDecoder().decode(segment.padBase64()))
            }.getOrNull() ?: return false
            // Only selective enough to pick the right literal out of one bundle; the gate
            // remains the authority on whether the assessment is actually any good.
            return json.contains("\"alg\"") && json.contains("\"enc\"")
        }

        fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)
    }
}

/**
 * Tries [primary] and falls back to [fallback] when it cannot produce an assessment.
 *
 * Exists so the browserless [HttpMonocleProvider] can be preferred without betting the whole
 * login on an undocumented shortcut: if Spur stops embedding an assessment, or Tartarus starts
 * declining the ones it embeds, the WebView path still works and the user gets a slower login
 * rather than a broken one.
 *
 * Note what this does *not* cover. A gate refusal is not a failure to produce an assessment,
 * so it never reaches this class — [KiwiFarmsClient] handles that by invalidating and
 * re-minting. In particular a `MCL` verdict, where Spur judged the address itself
 * unacceptable, cannot be fixed by any provider: the fallback would be handed the same address
 * and get the same answer.
 */
class FallbackMonocleProvider(
    private val primary: MonocleProvider,
    private val fallback: MonocleProvider,
    /** Notified when the fallback is used, so the debug window can report that the fast path broke. */
    private val onFallback: (String) -> Unit = {},
) : MonocleProvider {

    override suspend fun assessment(siteKey: String): String =
        try {
            primary.assessment(siteKey)
        } catch (e: Exception) {
            onFallback(e.message ?: e::class.simpleName ?: "unknown error")
            fallback.assessment(siteKey)
        }

    override suspend fun invalidate() {
        primary.invalidate()
        fallback.invalidate()
    }
}
