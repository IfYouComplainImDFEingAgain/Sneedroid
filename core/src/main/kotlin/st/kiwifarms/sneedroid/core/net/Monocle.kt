package st.kiwifarms.sneedroid.core.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Spur Monocle ("MCL") — the browser-verification step the Tartarus gate added in
 * August 2026, and the reason a pure-HTTP login stopped working.
 *
 * The challenge page carries a site key in `data-ttrs-monocle-key` and loads
 * `https://mcl.spur.us/d/mcl.js?tk=<key>`. That SDK gathers browser and network signals,
 * POSTs them to Spur, and Spur hands back an **assessment**: a JWE (`ECDH-ES` + `A256GCM`)
 * encrypted to a key Tartarus holds. The solution POST must carry it as a `monocle` form
 * field alongside `salt` and `nonce`.
 *
 * Two consequences follow, and they shape everything below:
 *
 * * **The assessment cannot be synthesised.** It is minted by Spur's server and sealed to
 *   Tartarus, so there is nothing to reimplement — no algorithm, no key. Omitting the field
 *   earns `{"success":false,"reason":"monocle_required"}` and anything home-made earns
 *   `monocle_invalid`, both HTTP 403. A real browser engine has to run the SDK.
 * * **That is the only part that needs a browser.** The assessment is not bound to the salt,
 *   to the page's origin (it mints fine from `about:blank`), or to the TLS connection that
 *   later submits it — verified against the live gate. So the browser's whole job is to
 *   return this one string; the challenge fetch, the proof of work, the solution POST, the
 *   cookie jar and the WebSocket all stay on OkHttp.
 *
 * It is also worth being clear-eyed about what this costs: Monocle is an IP-reputation and
 * proxy-detection product. Obtaining an assessment tells Spur, a third party, the device's
 * address and a browser fingerprint on every solve — and a VPN or proxy is precisely what it
 * exists to flag. See `docs/09-monocle.md`.
 */
fun interface MonocleProvider {

    /**
     * Return a Monocle assessment for [siteKey], or throw [MonocleUnavailable].
     *
     * Implementations must be safe to call from any thread and may block for a second or two.
     */
    suspend fun assessment(siteKey: String): String

    /**
     * Discard any cached assessment, so the next [assessment] call mints a fresh one.
     *
     * Called when the gate refuses one with `monocle_invalid`, which is the only reliable
     * signal that a cached assessment has gone stale. A no-op for providers that don't cache.
     */
    suspend fun invalidate() {}
}

/** Thrown when no assessment can be obtained, so the PoW gate cannot be cleared. */
class MonocleUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The provider used when the host has no browser engine to offer (plain-JVM callers, tests).
 * Fails with an explanation rather than letting the POST go out and come back `monocle_required`.
 */
object UnsupportedMonocleProvider : MonocleProvider {
    override suspend fun assessment(siteKey: String): String =
        throw MonocleUnavailable(
            "the site demands Spur Monocle browser verification, which needs a WebView; " +
                "this client was built without one"
        )
}

/**
 * Caches one assessment and hands it to every caller until it is [invalidate]d or goes stale.
 *
 * Assessments are replayable: the same one clears repeated challenges on separate connections
 * (verified against the live gate), so a solve does not need its own. Since one WebView spin-up
 * per reconnect is exactly the cost worth avoiding, caching is the point of this class.
 *
 * [ttl] is a proactive-refresh bound, never a correctness requirement. Spur does not publish a
 * lifetime and the assessment is opaque, so the authority on whether one is still good is the
 * gate: it either accepts the POST or answers `monocle_invalid`, at which point the caller calls
 * [invalidate] and tries again. Setting [ttl] too high therefore costs one wasted round trip,
 * not a failure — the same bargain `ClearanceStore` makes in the archiver.
 */
class CachedMonocleProvider(
    private val delegate: MonocleProvider,
    private val ttl: Duration = 10.minutes,
    private val clock: () -> Long = { System.nanoTime() },
) : MonocleProvider {

    private val mutex = Mutex()
    private var cached: String? = null
    private var mintedAt = 0L

    override suspend fun assessment(siteKey: String): String = mutex.withLock {
        // Held across the mint deliberately: without it, a mass reconnect has every room
        // discover the cache is empty at the same instant and spin up its own WebView.
        val current = cached
        if (current != null && clock() - mintedAt < ttl.inWholeNanoseconds) return current
        val fresh = delegate.assessment(siteKey)
        cached = fresh
        mintedAt = clock()
        fresh
    }

    override suspend fun invalidate() {
        mutex.withLock {
            cached = null
            mintedAt = 0L
        }
        delegate.invalidate()
    }
}
