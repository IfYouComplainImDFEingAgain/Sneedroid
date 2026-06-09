package st.kiwifarms.sneedroid.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import st.kiwifarms.sneedroid.core.protocol.ChatCommand
import st.kiwifarms.sneedroid.data.AuthRepository
import st.kiwifarms.sneedroid.data.ChatRepository
import st.kiwifarms.sneedroid.data.ChatUiState
import st.kiwifarms.sneedroid.data.SecureStore
import st.kiwifarms.sneedroid.data.SettingsRepository

class ChatViewModel(
    private val auth: AuthRepository,
    private val store: SecureStore,
    private val settings: SettingsRepository,
    private val repo: ChatRepository,
    private val allowDemo: Boolean,
) : ViewModel() {

    val state: StateFlow<ChatUiState> = repo.state
    val whispers = repo.whispers

    /**
     * The signed-in user's chat username for display, once learned from an echoed message.
     * NOT the login field (that may be an email). The `'@'` guard drops a stale email that an
     * earlier build mistakenly stored here — usernames can't contain '@'.
     */
    val username: String? get() = store.selfUsername?.takeIf { '@' !in it }

    val myUserId: Long? = auth.myUserId()

    val currentRoomId: Int get() = store.settings.roomId

    private val live: Boolean = auth.sessionCookies().isNotEmpty()

    init {
        // Seed from the learned chat username only (drop a stale email a prior build stored).
        repo.myUsername = store.selfUsername?.takeIf { '@' !in it }
        repo.myUserId = myUserId
        repo.onSelfUsernameLearned = { store.selfUsername = it }
        // Mirror the persisted notification toggles into the repository, which owns the inbound
        // whisper/message paths where notifications are fired.
        viewModelScope.launch {
            settings.settings.collect {
                repo.notifyWhispers = it.notifyWhispers
                repo.notifyMentions = it.notifyMentions
            }
        }
        if (live) {
            // Restore saved whisper threads before opening the socket so the load can't race the
            // inbound-event collector that also mutates the conversation store.
            repo.whisperPersistence = true
            repo.restoreWhispers()
            connectCurrent()
        } else if (allowDemo) {
            repo.seed(roomTitle = "Keno Kasino", online = 38, seeded = SampleData.timeline())
            repo.seedMotd(SampleData.motd())
            repo.seedRoster(
                listOf(
                    "KenoGPT", "spektr", "Greasy_Pete", "noodlearms", "Vivienne",
                    "tugboat", "marlin", "Halcyon", "ScoreBot", "noodlebot",
                ).mapIndexed { i, n -> st.kiwifarms.sneedroid.core.model.ChatUser(id = (i + 1).toLong(), username = n) },
            )
            val now = System.currentTimeMillis() / 1000
            repo.seedWhisper(
                42, "Vivienne",
                listOf(
                    st.kiwifarms.sneedroid.data.WhisperLine(false, "hey, you around?", now - 120),
                    st.kiwifarms.sneedroid.data.WhisperLine(true, "yeah what's up", now - 90),
                    st.kiwifarms.sneedroid.data.WhisperLine(false, "did you see the [b]draw[/b]?", now - 30),
                ),
                unread = 1,
            )
            repo.debugLog("connection", "OPEN — joined room 15")
            repo.debugLog("Permissions", """{"permissions":{"can_view":true,"can_send":true,"can_edit_own":true}}""")
            repo.debugLog("UsersJoined", """{"users":{"1337":{"id":1337,"username":"Example","avatar_url":"/a.jpg"}}}""")
            repo.debugLog("System", """{"system":"Welcome to Keno Kasino"}""")
            repo.debugLog("UsersParted", """{"user":{"1337":false}}""")
        }
    }

    private fun connectCurrent() {
        val s = store.settings
        repo.connect(
            wsUrl = s.wsEndpoint,
            roomId = s.roomId,
            sessionCookies = { auth.sessionCookies() },
            refreshAuth = { auth.refreshSession() },
        )
    }

    fun send(text: String) {
        repo.send(ChatCommand.Send(text))
    }

    fun edit(uuid: String, text: String) {
        repo.send(ChatCommand.Edit(uuid, text))
    }

    fun delete(uuid: String) {
        repo.send(ChatCommand.Delete(uuid))
    }

    fun sendWhisper(partnerId: Long, partnerName: String, text: String) {
        repo.send(ChatCommand.Whisper(partnerId, partnerName, text))
    }

    fun markWhisperRead(partnerId: Long) {
        repo.markWhisperRead(partnerId)
    }

    /** Track which whisper thread is on screen so we don't notify for the one you're reading. */
    fun setOpenWhisper(partnerId: Long?) {
        repo.openWhisperPartner = partnerId
    }

    fun switchRoom(roomId: Int) {
        store.settings = store.settings.copy(roomId = roomId)
        settings.rememberRoom(roomId)
        if (live) {
            repo.clear()
            connectCurrent()
        }
    }

    /** Stop the connection (logout is finalized by the app-level route owner). */
    fun leave() {
        repo.disconnect()
    }

    override fun onCleared() {
        // Keep the connection alive for the background service when a background mode is active;
        // only tear it down when background delivery is Off (then nothing needs it).
        if (settings.settings.value.backgroundMode == st.kiwifarms.sneedroid.data.BackgroundMode.Off) {
            repo.disconnect()
        }
    }

    class Factory(
        private val auth: AuthRepository,
        private val store: SecureStore,
        private val settings: SettingsRepository,
        private val repo: ChatRepository,
        private val allowDemo: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(auth, store, settings, repo, allowDemo) as T
    }
}
