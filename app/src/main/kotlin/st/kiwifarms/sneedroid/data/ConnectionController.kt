package st.kiwifarms.sneedroid.data

/**
 * Coordinates the single chat connection across the foreground UI and the background service.
 *
 * The connection is owned by [ChatRepository]; this decides what *mode* it should be in based on
 * app visibility and the user's [BackgroundMode]:
 *  - foreground            → joined to the current room (the UI needs room messages)
 *  - background, whispers  → bare connection, no room join (cheap; DMs still arrive)
 *  - background, +mentions → stay joined to the current room
 *  - Off                   → leave the connection alone (the service isn't running to hold it)
 */
class ConnectionController(
    private val repo: ChatRepository,
    private val auth: AuthRepository,
    private val store: SecureStore,
    private val settings: SettingsRepository,
) {
    val mode: BackgroundMode get() = settings.settings.value.backgroundMode
    val loggedIn: Boolean get() = auth.sessionCookies().isNotEmpty()

    /** App returned to the foreground: make sure we're joined to the current room for the UI. */
    fun goForeground() {
        if (repo.isConfigured) repo.ensureJoinMode(true)
    }

    /** App went to the background: drop or keep the room join according to the selected mode. */
    fun goBackground() {
        when (mode) {
            BackgroundMode.Off -> Unit
            BackgroundMode.WhispersOnly -> repo.ensureJoinMode(false)
            BackgroundMode.WhispersAndMentions -> repo.ensureJoinMode(true)
        }
    }

    /**
     * Establish the connection from scratch with no Activity/ViewModel alive — e.g. the service
     * was restarted by the system (START_STICKY) after a process kill. Mirrors the identity and
     * whisper-restore setup the ViewModel does on a live launch, then connects per [mode].
     */
    fun ensureBackgroundConnection() {
        if (!loggedIn || mode == BackgroundMode.Off) return
        val s = settings.settings.value
        repo.myUsername = store.selfUsername?.takeIf { '@' !in it }
        repo.myUserId = auth.myUserId()
        repo.notifyWhispers = s.notifyWhispers
        repo.notifyMentions = s.notifyMentions
        repo.onSelfUsernameLearned = { store.selfUsername = it }
        repo.whisperPersistence = true
        repo.restoreWhispers()

        val join = mode == BackgroundMode.WhispersAndMentions
        if (repo.isConfigured) {
            repo.ensureJoinMode(join)
        } else {
            val cs = store.settings
            repo.connect(
                wsUrl = cs.wsEndpoint,
                roomId = cs.roomId,
                sessionCookies = { auth.sessionCookies() },
                refreshAuth = { auth.refreshSession() },
                joinRoom = join,
            )
        }
    }
}
