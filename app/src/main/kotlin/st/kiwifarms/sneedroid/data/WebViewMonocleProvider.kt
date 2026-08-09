package st.kiwifarms.sneedroid.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import st.kiwifarms.sneedroid.core.net.MonocleProvider
import st.kiwifarms.sneedroid.core.net.MonocleUnavailable
import kotlin.coroutines.resume

/**
 * Mints Spur Monocle assessments in an offscreen [WebView].
 *
 * The gate's browser verification is the one step that genuinely needs a browser engine: the
 * assessment is a JWE minted by Spur's servers and sealed to Tartarus, so there is no algorithm
 * to port. What it does *not* need is for the rest of the client to move into a WebView, and
 * that distinction is the whole design here. Verified against the live gate:
 *
 * * the assessment mints fine from a page that never touches the site's origin,
 * * it is not bound to the challenge salt — it can be obtained before the salt is known,
 * * it is not bound to the TLS connection that later submits it: one minted by Chromium was
 *   accepted on a POST made by a completely separate HTTP stack, and
 * * it is replayable across several challenges.
 *
 * So this class hands back a string and nothing else. The challenge fetch, the proof of work,
 * the solution POST, the cookie jar and the chat WebSocket all stay on OkHttp, where the
 * killswitch interceptor and the cookie handling already live. That split is not merely tidy:
 * the `ttrs_clearance` the gate issues *is* bound to the TLS fingerprint that minted it, so a
 * session obtained wholly inside a WebView would be refused on OkHttp's connections anyway.
 *
 * **The polling is driven from Kotlin on purpose.** A WebView that is never attached to a
 * window gets its JavaScript timers throttled hard by Chromium — in practice a `setTimeout`
 * scheduled for 20 s simply never fires, so a page that waits for the SDK using timers hangs
 * forever and the mint dies on the outer timeout. Network callbacks and script execution still
 * work, so the loop below repeatedly *asks* the page for the assessment rather than letting the
 * page tell us when it has one. Anything reintroducing a `setTimeout` here will reintroduce
 * that hang, and it will only show up on a real device.
 *
 * The WebView is created per call and destroyed in a `finally`;
 * [st.kiwifarms.sneedroid.core.net.CachedMonocleProvider] in front of it is what keeps that
 * from happening on every reconnect.
 */
class WebViewMonocleProvider(
    private val context: Context,
    /**
     * True when the IP killswitch is blocking. Checked here as well as in the OkHttp
     * interceptor because a WebView uses its own network stack and would otherwise sail
     * straight past it — and this request goes to a third party, not even to the site.
     */
    private val killswitchBlocked: () -> Boolean = { false },
    /**
     * Only ever the inline page's base URL — never fetched. The SDK comes from Spur and the
     * document is supplied inline; this just gives the page the same origin a real visitor's
     * challenge page would have.
     */
    private val domain: String = "kiwifarms.st",
    private val timeoutMs: Long = 45_000,
) : MonocleProvider {

    override suspend fun assessment(siteKey: String): String {
        if (killswitchBlocked()) {
            throw MonocleUnavailable(
                "IP killswitch on — connect a VPN before browser verification"
            )
        }
        // The WebView API is main-thread-only, start to finish.
        return withContext(Dispatchers.Main) {
            val view = newWebView()
            try {
                withTimeoutOrNull(timeoutMs) { mint(view, siteKey) }
                    ?: throw MonocleUnavailable(
                        "browser verification timed out after ${timeoutMs}ms " +
                            "(last stage: ${lastStage(view)})"
                    )
            } finally {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Nothing here is a document the SDK needs to read off disk.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Best-effort against Chromium's background throttling. Not sufficient on its own —
        // hence the Kotlin-side polling — but it costs nothing and helps the SDK's internals.
        resumeTimers()
    }

    private suspend fun mint(view: WebView, siteKey: String): String {
        view.loadDataWithBaseURL("https://$domain/", page(siteKey), "text/html", "utf-8", null)

        // Poll rather than wait to be called back; see the class comment.
        while (true) {
            delay(POLL_INTERVAL_MS)
            val raw = view.eval(PROBE) ?: continue
            val state = runCatching { JSONObject(raw) }.getOrNull() ?: continue

            state.optString("error").takeIf { it.isNotEmpty() }?.let {
                throw MonocleUnavailable("browser verification failed: $it")
            }
            val assessment = state.optString("assessment")
            if (assessment.isNotEmpty()) return assessment
        }
    }

    /** Best-effort description of where the page got to, for a timeout message. */
    private suspend fun lastStage(view: WebView): String =
        runCatching { view.eval("window.__mclStage || 'unknown'") }
            .getOrNull()
            ?.trim('"')
            ?: "unreachable"

    /**
     * Run [script] and return its value with the JSON string quoting undone.
     *
     * `evaluateJavascript` hands back a *JSON-encoded* result, so a string comes wrapped in
     * quotes with inner escapes; decoding it through [JSONObject] is what makes the payload
     * usable rather than a mess of backslashes.
     */
    private suspend fun WebView.eval(script: String): String? =
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(script) { encoded ->
                val value = if (encoded == null || encoded == "null") {
                    null
                } else {
                    runCatching { JSONObject("{\"v\":$encoded}").getString("v") }
                        .getOrElse { encoded }
                }
                cont.resume(value)
            }
        }

    /**
     * The minimal page: pull the SDK for [siteKey] and stash whatever it produces on `window`
     * for the Kotlin side to collect. Deliberately timer-free.
     */
    private fun page(siteKey: String): String {
        val key = JSONObject.quote(siteKey)
        return """
            <!doctype html><html><head><meta charset="utf-8"></head><body><script>
            window.__mclStage = 'injecting';
            window.__mclAssessment = null;
            window.__mclError = null;
            (function () {
              var s = document.createElement('script');
              s.src = 'https://mcl.spur.us/d/mcl.js?tk=' + encodeURIComponent($key);
              s.async = true;
              s.onerror = function () {
                window.__mclError = 'could not load the verification script';
              };
              s.onload = function () { window.__mclStage = 'sdk-loaded'; };
              document.head.appendChild(s);
            })();
            </script></body></html>
        """.trimIndent()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L

        /**
         * Asks the page for its state. Calls `getAssessment()` directly on each poll — the SDK
         * fills it in as soon as Spur answers — and also registers the `onAssessment` callback
         * once the SDK exists, in case a build only ever pushes the value.
         */
        const val PROBE = """
            (function () {
              var out = { assessment: '', error: window.__mclError || '', stage: window.__mclStage };
              if (out.error) return JSON.stringify(out);
              if (window.__mclAssessment) {
                out.assessment = window.__mclAssessment;
                return JSON.stringify(out);
              }
              var M = window.MCL;
              if (!M || typeof M.getAssessment !== 'function') {
                out.stage = window.__mclStage || 'waiting-for-sdk';
                return JSON.stringify(out);
              }
              window.__mclStage = 'sdk-ready';
              if (!window.__mclHooked) {
                window.__mclHooked = true;
                try {
                  M.onAssessment = function (x) { if (x) window.__mclAssessment = x; };
                } catch (e) { /* not all builds expose the setter */ }
              }
              try {
                var a = M.getAssessment();
                if (a) { window.__mclAssessment = a; out.assessment = a; }
              } catch (e) {
                out.error = 'verification threw: ' + e;
              }
              return JSON.stringify(out);
            })()
        """
    }
}
