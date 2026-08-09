package st.kiwifarms.sneedroid.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import st.kiwifarms.sneedroid.core.net.MonocleProvider
import st.kiwifarms.sneedroid.core.net.MonocleUnavailable
import java.util.concurrent.atomic.AtomicBoolean

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
 * killswitch interceptor and the cookie handling already live.
 *
 * The WebView is created per call and destroyed in a `finally`; [st.kiwifarms.sneedroid.core.net.CachedMonocleProvider]
 * in front of it is what keeps that from happening on every reconnect.
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
    private val timeoutMs: Long = 30_000,
) : MonocleProvider {

    override suspend fun assessment(siteKey: String): String {
        if (killswitchBlocked()) {
            throw MonocleUnavailable(
                "IP killswitch on — connect a VPN before browser verification"
            )
        }
        // The WebView API is main-thread-only, start to finish.
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) { mint(siteKey) }
                ?: throw MonocleUnavailable("browser verification timed out after ${timeoutMs}ms")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun mint(siteKey: String): String = suspendCancellableCoroutine { cont ->
        var webView: WebView? = null
        // The page can call back on success *and* on a later error; only the first wins.
        val settled = AtomicBoolean(false)

        fun finish(result: Result<String>) {
            if (!settled.compareAndSet(false, true)) return
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            webView = null
            cont.resumeWith(result)
        }

        val bridge = object {
            @JavascriptInterface
            fun onAssessment(value: String) {
                // Bounced back to the main thread: @JavascriptInterface methods arrive on a
                // WebView-internal thread, and destroy() must not be called from there.
                webView?.post {
                    finish(
                        if (value.isNotBlank()) Result.success(value)
                        else Result.failure(MonocleUnavailable("Spur returned an empty assessment"))
                    )
                }
            }

            @JavascriptInterface
            fun onError(stage: String) {
                webView?.post {
                    finish(Result.failure(MonocleUnavailable("browser verification failed: $stage")))
                }
            }
        }

        try {
            val view = WebView(context)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Nothing here is a document the SDK needs to read off disk.
                allowFileAccess = false
                allowContentAccess = false
            }
            view.addJavascriptInterface(bridge, BRIDGE)
            // Loaded against the site's own origin rather than about:blank. Not required today
            // (about:blank mints fine) but it is what a real visitor's page looks like, and it
            // costs nothing to avoid depending on Spur never adding an origin check.
            view.loadDataWithBaseURL(
                "https://$domain/",
                page(siteKey),
                "text/html",
                "utf-8",
                null,
            )
        } catch (e: Throwable) {
            finish(Result.failure(MonocleUnavailable("could not start a WebView", e)))
        }

        cont.invokeOnCancellation {
            // Cancellation can arrive on any thread; destroy() cannot.
            webView?.post { finish(Result.failure(MonocleUnavailable("cancelled"))) }
        }
    }

    /**
     * The minimal page: pull the SDK for [siteKey], hand back the assessment.
     *
     * `getAssessment()` often returns synchronously once the SDK has bootstrapped, but not
     * always, so both that and the `onAssessment` callback are wired up and whichever fires
     * first wins.
     */
    private fun page(siteKey: String): String {
        val key = org.json.JSONObject.quote(siteKey)
        return """
            <!doctype html><html><head><meta charset="utf-8"></head><body><script>
            (function () {
              var done = false;
              function ok(v) { if (!done) { done = true; $BRIDGE.onAssessment(v || ""); } }
              function bad(s) { if (!done) { done = true; $BRIDGE.onError(s); } }
              var s = document.createElement('script');
              s.src = 'https://mcl.spur.us/d/mcl.js?tk=' + encodeURIComponent($key);
              s.async = true;
              s.onerror = function () { bad('could not load the verification script'); };
              document.head.appendChild(s);
              var t0 = Date.now();
              (function poll() {
                var M = window.MCL;
                if (!M || typeof M.getAssessment !== 'function') {
                  if (Date.now() - t0 > 20000) return bad('verification script did not start');
                  return setTimeout(poll, 50);
                }
                var a;
                try { a = M.getAssessment(); } catch (e) { return bad('verification threw: ' + e); }
                if (a) return ok(a);
                M.onAssessment = function (x) { x ? ok(x) : bad('empty assessment'); };
                setTimeout(function () { bad('verification did not complete'); }, 20000);
              })();
            })();
            </script></body></html>
        """.trimIndent()
    }

    private companion object {
        const val BRIDGE = "SneedroidMonocle"
    }
}
