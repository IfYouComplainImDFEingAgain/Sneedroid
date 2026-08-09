package st.kiwifarms.sneedroid.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Cookie
import st.kiwifarms.sneedroid.core.net.CachedMonocleProvider
import st.kiwifarms.sneedroid.core.net.CookieSerialization
import st.kiwifarms.sneedroid.core.net.InMemoryCookieJar
import st.kiwifarms.sneedroid.core.net.KiwiFarmsClient
import st.kiwifarms.sneedroid.core.net.LoginOutcome
import st.kiwifarms.sneedroid.core.net.LoginPhase
import st.kiwifarms.sneedroid.core.net.MonocleProvider
import st.kiwifarms.sneedroid.core.net.UnsupportedMonocleProvider

/** Result of an attempted resume of a stored session. */
enum class SessionState { LoggedIn, NeedsLogin }

/**
 * Owns the session cookie jar and the [KiwiFarmsClient]. Bridges the encrypted
 * [SecureStore] (Android) and the pure-JVM protocol client (:core).
 */
class AuthRepository(
    private val store: SecureStore,
    private val killswitchBlocked: () -> Boolean = { false },
    /**
     * Browser verification for the PoW gate. Wrapped in a cache so a burst of reconnects
     * shares one assessment instead of spinning up a WebView each; the cache is dropped
     * automatically when the gate refuses one. Null in tests and plain-JVM callers, which
     * leaves [KiwiFarmsClient] to fail with an explanation rather than a bare rejection.
     */
    monocle: MonocleProvider? = null,
) {

    private val monocle: MonocleProvider =
        monocle?.let { CachedMonocleProvider(it) } ?: UnsupportedMonocleProvider

    private val cookieJar = InMemoryCookieJar(restoreCookies())

    /**
     * Emits when automatic recovery has concluded the session genuinely needs a manual
     * sign-in (saved credentials are absent/stale, or the account now needs a fresh 2FA
     * code that can't be answered from a background reconnect). The route owner observes
     * this to bounce the user to the login screen. extraBufferCapacity so a fire just
     * before the collector subscribes isn't dropped.
     */
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    @Volatile
    private var client: KiwiFarmsClient = newClient()

    private fun newClient() =
        KiwiFarmsClient(store.settings.domain, cookieJar, killswitchBlocked, monocle)

    private fun restoreCookies(): List<Cookie> =
        store.cookies?.let { CookieSerialization.decode(it) } ?: emptyList()

    private fun persistCookies() {
        store.cookies = CookieSerialization.encode(cookieJar.snapshot())
    }

    /** Cookies to attach to the chat WebSocket upgrade. */
    fun sessionCookies(): List<Cookie> = cookieJar.snapshot()

    /** Our own user id, parsed from the leading digits of the xf_user cookie ("1234,hash"). */
    fun myUserId(): Long? {
        val raw = cookieJar.snapshot().firstOrNull { it.name == "xf_user" }?.value ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.takeWhile { it.isDigit() }.toLongOrNull()
    }

    /** Saved credentials for silent re-login, if the user opted in. */
    fun savedCredentials(): Pair<String, String>? {
        if (!store.rememberCredentials) return null
        val u = store.savedUsername ?: return null
        val p = store.savedPassword ?: return null
        return u to p
    }

    /**
     * Try to reuse the stored session. Returns whether we're already authenticated.
     *
     * With cookies on hand we only demand a fresh login when the server *positively* says we're
     * signed out. If the session check can't be made — the IP killswitch is blocking (no VPN), or
     * the network is unreachable — we keep the stored session and route to chat: its connection is
     * itself gated/retried, and its refresh→sessionExpired flow bounces to login only if the
     * session turns out to be genuinely dead. This avoids re-prompting for a password just because
     * we're offline-by-policy or briefly offline.
     */
    suspend fun resume(): SessionState {
        if (cookieJar.snapshot().isEmpty()) return SessionState.NeedsLogin
        if (killswitchBlocked()) return SessionState.LoggedIn
        return try {
            if (client.isLoggedIn()) SessionState.LoggedIn else SessionState.NeedsLogin
        } catch (_: Exception) {
            SessionState.LoggedIn
        }
    }

    /**
     * Re-establish authentication after the chat server rejects a room join. A rejected
     * join is far more often an expired token than a real permissions/threshold block:
     * the KiwiFlare/Tartarus PoW clearance lapses roughly every ~18h, and the xf_session
     * can expire too. We refresh the cheap, fully-automatic things first and only fall
     * back to a credential re-login if the session itself is gone.
     *
     * Returns true if the session looks usable afterwards (so a reconnect is worth a try),
     * false if recovery needs the user (no saved credentials, or 2FA is required).
     */
    suspend fun refreshSession(onPhase: (LoginPhase) -> Unit = {}): Boolean {
        // 1. Re-solve the PoW if the gate is challenging us again (clearance expiry).
        runCatching { client.ensureClearance(onPhase) }.getOrElse { return false }
        persistCookies()

        // 2. If the session is still valid, the refreshed clearance was the fix.
        if (runCatching { client.isLoggedIn() }.getOrDefault(false)) return true

        // 3. The session itself lapsed — try a silent re-login with saved credentials.
        val (user, pass) = savedCredentials()
            ?: return false.also { _sessionExpired.tryEmit(Unit) } // no creds → sign in by hand
        val outcome = runCatching { client.login(user, pass, onPhase) }.getOrNull()
            ?: return false // transient (network/PoW) — let the reconnect loop retry, don't force login
        // A clean Success is recoverable; otherwise (2FA now required, or credentials no longer
        // valid) we need the user on the login screen — the 2FA page there is the same flow.
        if (outcome is LoginOutcome.Success) {
            persistCookies()
            return true
        }
        _sessionExpired.tryEmit(Unit)
        return false
    }

    private var pendingRemember = false
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null

    /**
     * Perform native login (solving the PoW if challenged). Returns the [LoginOutcome]:
     * on [LoginOutcome.Success] the session is persisted; on
     * [LoginOutcome.TwoFactorRequired] call [submitTwoFactor]. Throws on bad credentials.
     */
    suspend fun login(
        username: String,
        password: String,
        remember: Boolean,
        onPhase: (LoginPhase) -> Unit = {},
    ): LoginOutcome {
        val outcome = client.login(username, password, onPhase)
        when (outcome) {
            is LoginOutcome.Success -> finishLogin(username, password, remember)
            is LoginOutcome.TwoFactorRequired -> {
                pendingRemember = remember
                pendingUsername = username
                pendingPassword = password
            }
        }
        return outcome
    }

    /** Complete a 2FA challenge with a user-entered code. */
    suspend fun submitTwoFactor(
        code: String,
        trust: Boolean,
        onPhase: (LoginPhase) -> Unit = {},
    ): LoginOutcome {
        val outcome = client.submitTwoFactor(code = code, trust = trust, onPhase = onPhase)
        if (outcome is LoginOutcome.Success) {
            finishLogin(pendingUsername.orEmpty(), pendingPassword.orEmpty(), pendingRemember)
            pendingUsername = null
            pendingPassword = null
            pendingRemember = false
        }
        return outcome
    }

    private fun finishLogin(username: String, password: String, remember: Boolean) {
        persistCookies()
        // NB: don't treat the login field as the display name — it's often an email. The real
        // chat username is learned from the first echoed own message (ChatRepository.learnSelf).
        store.rememberCredentials = remember
        if (remember) {
            store.savedUsername = username
            store.savedPassword = password
        } else {
            store.savedUsername = null
            store.savedPassword = null
        }
    }

    fun logout() {
        cookieJar.clear()
        store.clear()
        client = newClient()
    }
}
