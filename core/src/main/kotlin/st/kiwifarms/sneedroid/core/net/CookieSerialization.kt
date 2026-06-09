package st.kiwifarms.sneedroid.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie

/** Storage-friendly representation of an OkHttp [Cookie]. */
@Serializable
data class PersistedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

/**
 * Converts the session cookie jar to/from a JSON string so the app can persist it
 * (encrypted). Lives in :core so the Cookie↔DTO mapping is unit-testable without Android.
 */
object CookieSerialization {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(PersistedCookie.serializer())

    fun toPersisted(cookie: Cookie): PersistedCookie = PersistedCookie(
        name = cookie.name,
        value = cookie.value,
        domain = cookie.domain,
        path = cookie.path,
        expiresAt = cookie.expiresAt,
        secure = cookie.secure,
        httpOnly = cookie.httpOnly,
        hostOnly = cookie.hostOnly,
    )

    fun toCookie(p: PersistedCookie): Cookie {
        val b = Cookie.Builder()
            .name(p.name)
            .value(p.value)
            .path(p.path)
            .expiresAt(p.expiresAt)
        // hostOnly vs domain cookies are built with different setters.
        if (p.hostOnly) b.hostOnlyDomain(p.domain) else b.domain(p.domain)
        if (p.secure) b.secure()
        if (p.httpOnly) b.httpOnly()
        return b.build()
    }

    fun encode(cookies: List<Cookie>): String =
        json.encodeToString(listSerializer, cookies.map(::toPersisted))

    fun decode(serialized: String): List<Cookie> = try {
        json.decodeFromString(listSerializer, serialized).map(::toCookie)
    } catch (_: Exception) {
        emptyList()
    }
}
