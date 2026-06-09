package st.kiwifarms.sneedroid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Connection settings with the live defaults (overridable later from a settings screen).
 */
data class ConnectionSettings(
    val domain: String = "kiwifarms.st",
    val wsEndpoint: String = "wss://kiwifarms.st:9443/chat.ws",
    // Default landing room (General). Only used on first launch; thereafter the last room
    // the user switched to is persisted (KEY_ROOM) and restored on open.
    val roomId: Int = 1,
)

/**
 * AES-256 (Android Keystore) encrypted store for the session cookie jar, optional saved
 * credentials, and connection settings. See docs/06 — cookies are session-bearing, so they
 * never touch plain prefs and the app sets allowBackup=false.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sneedroid_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Serialized cookie jar (see core CookieSerialization), or null if none stored. */
    var cookies: String?
        get() = prefs.getString(KEY_COOKIES, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_COOKIES) else putString(KEY_COOKIES, value) }.apply()

    /** Whether the user opted to persist their credentials for silent re-login. */
    var rememberCredentials: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    var savedUsername: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var savedPassword: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /**
     * The signed-in user's own username, for display/own-message matching. Stored
     * independently of [savedUsername]/[rememberCredentials] (which gate the *password* for
     * silent re-login) so we can know the name even when the user didn't opt to be remembered.
     * Captured at login and learned from the first echoed own message. Cleared on logout.
     */
    var selfUsername: String?
        get() = prefs.getString(KEY_SELF_USERNAME, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_SELF_USERNAME) else putString(KEY_SELF_USERNAME, value) }.apply()

    var settings: ConnectionSettings
        get() = ConnectionSettings(
            domain = prefs.getString(KEY_DOMAIN, null) ?: ConnectionSettings().domain,
            wsEndpoint = prefs.getString(KEY_WS, null) ?: ConnectionSettings().wsEndpoint,
            roomId = prefs.getInt(KEY_ROOM, ConnectionSettings().roomId),
        )
        set(value) = prefs.edit()
            .putString(KEY_DOMAIN, value.domain)
            .putString(KEY_WS, value.wsEndpoint)
            .putInt(KEY_ROOM, value.roomId)
            .apply()

    /** Wipe everything (logout). */
    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_COOKIES = "cookies"
        const val KEY_REMEMBER = "remember"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_SELF_USERNAME = "self_username"
        const val KEY_DOMAIN = "domain"
        const val KEY_WS = "ws_endpoint"
        const val KEY_ROOM = "room_id"
    }
}
