package st.kiwifarms.sneedroid.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Process-wide view of the IP killswitch for cross-cutting network clients that aren't built
 * through [AppContainer] — notably the Coil image loaders in `SneedApp`/`EmoteTable`. The container
 * points [blocked] at the real [KillswitchGate] on startup; the default never blocks.
 */
object Killswitches {
    @Volatile
    var blocked: () -> Boolean = { false }
}

/**
 * OkHttp interceptor that refuses every request while the killswitch is blocking. Added to the
 * image loaders so avatars/emotes (incl. the startup emote prefetch) can't fetch from the
 * KiwiFarms CDN without a VPN — closing the only network path that didn't already go through the
 * gated chat/auth clients.
 */
class KillswitchInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (Killswitches.blocked()) throw IOException("IP killswitch — request blocked (no VPN tunnel)")
        return chain.proceed(chain.request())
    }
}
