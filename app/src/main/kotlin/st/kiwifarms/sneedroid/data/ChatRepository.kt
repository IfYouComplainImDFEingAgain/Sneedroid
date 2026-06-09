package st.kiwifarms.sneedroid.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Cookie
import st.kiwifarms.sneedroid.core.model.ChatAuthor
import st.kiwifarms.sneedroid.core.model.ChatMessage
import st.kiwifarms.sneedroid.core.model.ChatUser
import st.kiwifarms.sneedroid.core.model.RoomPermissions
import st.kiwifarms.sneedroid.core.net.ChatSocket
import st.kiwifarms.sneedroid.core.net.SocketSignal
import st.kiwifarms.sneedroid.core.protocol.ChatCommand
import st.kiwifarms.sneedroid.core.protocol.ChatEvent

/** A renderable row in the timeline. */
sealed interface TimelineItem {
    val key: String

    data class Msg(val message: ChatMessage) : TimelineItem {
        override val key: String = message.uuid ?: "m${message.id ?: message.date}"
    }

    data class Sys(val id: Long, val text: String) : TimelineItem {
        override val key: String = "sys$id"
    }
}

enum class ConnectionState { Connecting, Online, Reconnecting, Disconnected, Blocked }

/**
 * A pre-connect gate. While [blocked] is true the chat connection is held closed — the IP
 * killswitch uses this to refuse connecting unless a VPN tunnel is present, so a dropped VPN
 * can never leak the user's residential IP to KiwiFarms. Evaluated before every connection
 * attempt and watched during a live connection (a mid-session block tears the socket down).
 */
interface Killswitch {
    val blocked: kotlinx.coroutines.flow.StateFlow<Boolean>
}

/** One line in a whisper thread. */
data class WhisperLine(val fromMe: Boolean, val raw: String, val date: Long)

/** A private-message conversation with one other user. */
data class WhisperConversation(
    val partnerId: Long,
    val partnerName: String,
    val partnerAvatar: String?,
    val lines: List<WhisperLine>,
    val unread: Int,
    val lastActivity: Long,
)

data class ChatUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val timeline: List<TimelineItem> = emptyList(),
    val online: Int = 0,
    val roster: List<ChatUser> = emptyList(),
    val roomTitle: String = "",
    val permissions: RoomPermissions? = null,
    /** The signed-in user's own username once known (saved at login or learned from a frame). */
    val selfName: String? = null,
    /** Pinned message(s)-of-the-day, shown collapsed above the timeline (not in the scroll). */
    val motds: List<ChatMessage> = emptyList(),
)

/**
 * Owns one chat connection and the per-room message store. Consumes [ChatSocket] as a
 * Flow, drives a reconnect/backoff loop, and reduces inbound
 * [ChatEvent]s into a [ChatUiState]. See docs/03-architecture.md.
 */
class ChatRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val socket: ChatSocket = ChatSocket(),
    private val debug: DebugLog = DebugLog(),
    private val errors: ErrorReporter = ErrorReporter(debug),
    private val whisperStore: WhisperStore? = null,
    private val notifier: Notifier? = null,
) {
    /**
     * When true, whisper threads are restored from and saved to [whisperStore]. Enabled for a
     * live session only — the demo seeds throwaway conversations we must never persist.
     */
    var whisperPersistence: Boolean = false

    /** Notification toggles, kept in sync with user settings; see [Notifier]. */
    @Volatile var notifyWhispers: Boolean = true
    @Volatile var notifyMentions: Boolean = true

    /** Set by the UI's lifecycle: true while the app is visible (used to scope notifications). */
    @Volatile var appForeground: Boolean = false

    /**
     * The partner id of the whisper thread currently on screen, or null. A whisper is only
     * suppressed when you're actively looking at *that* conversation — unlike room mentions, a
     * DM should still notify while the app is open on a different room or thread.
     */
    @Volatile var openWhisperPartner: Long? = null

    /**
     * Gates mention notifications past the initial scrollback. The server replays a room's recent
     * history as a [ChatEvent.Messages] batch on every (re)join; without this we'd fire a burst of
     * notifications for old messages. Disarmed on each socket open, armed once the first batch is
     * applied so only genuinely live messages can notify.
     */
    @Volatile private var notificationsArmed = false
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _whispers = MutableStateFlow<List<WhisperConversation>>(emptyList())
    val whispers: StateFlow<List<WhisperConversation>> = _whispers.asStateFlow()

    /** Record a non-chat frame or connection event into the debug log (also used by demo). */
    fun debugLog(type: String, raw: String) = debug.add(type, raw)

    /** Our own identity, used to classify whisper direction and own messages. */
    @Volatile
    var myUsername: String? = null

    @Volatile
    var myUserId: Long? = null

    /** Invoked once we learn our own username from a frame, so it can be persisted. */
    var onSelfUsernameLearned: ((String) -> Unit)? = null

    // Insertion-ordered, deduped by key; edits replace in place, keeping position.
    private val items = LinkedHashMap<String, TimelineItem>()
    // MOTDs are pinned (shown above the timeline), so they live outside `items` and never
    // affect scroll-to-latest. Keyed by uuid so an edited MOTD replaces in place.
    private val motds = LinkedHashMap<String, ChatMessage>()
    private val roster = LinkedHashMap<Long, ChatUser>()
    private val convs = LinkedHashMap<Long, Conv>()
    // Guards `convs`: mutated from the IO event collector (inbound whispers) and from the main
    // thread (markWhisperRead, restore), so every read/write is confined to this monitor.
    private val convsLock = Any()
    private var sysCounter = 0L

    private class Conv(val partnerId: Long) {
        var name: String = ""
        var avatar: String? = null
        val lines = mutableListOf<WhisperLine>()
        var unread: Int = 0
        var lastActivity: Long = 0
        fun toPublic() = WhisperConversation(partnerId, name, avatar, lines.toList(), unread, lastActivity)
    }

    private var connectJob: Job? = null

    /** Last connect parameters, retained so the connection can be re-opened in a different join mode. */
    private class ConnCfg(
        val wsUrl: String,
        val roomId: Int,
        val sessionCookies: () -> List<Cookie>,
        val refreshAuth: suspend () -> Boolean,
    )
    private var cfg: ConnCfg? = null

    /** Whether the live connection joined a room. False = bare connection (whispers still arrive). */
    @Volatile
    var joinedMode: Boolean = true
        private set

    /**
     * Open and maintain the chat connection for [roomId].
     *
     * [sessionCookies] is read fresh on every (re)connect so a refreshed token is picked
     * up; [refreshAuth] is invoked before reconnecting after a join rejection to renew the
     * PoW clearance / session (see [isJoinFailure]).
     *
     * When [joinRoom] is false the socket authenticates but never sends `/join`, so no room
     * traffic flows — whispers are connection-level and still arrive. Used by the background
     * whispers-only mode to stay cheap. See [whispers-without-join] design note.
     */
    /** IP killswitch gate; when its [Killswitch.blocked] is true, connections are held closed. */
    @Volatile
    var killswitch: Killswitch? = null

    fun connect(
        wsUrl: String,
        roomId: Int,
        sessionCookies: () -> List<Cookie>,
        refreshAuth: suspend () -> Boolean = { false },
        joinRoom: Boolean = true,
    ) {
        disconnect()
        cfg = ConnCfg(wsUrl, roomId, sessionCookies, refreshAuth)
        joinedMode = joinRoom
        val title = if (joinRoom) Rooms.name(roomId) else ""
        _state.update { it.copy(roomTitle = title, connection = ConnectionState.Connecting) }
        connectJob = scope.launch {
            var backoff = INITIAL_BACKOFF_MS
            var first = true
            var joinFailures = 0
            var refreshBeforeNext = false
            // Killswitch watcher: if the gate blocks mid-session (e.g. VPN dropped) while a socket
            // is live, close it so the loop falls back to the Blocked gate below. close() is a no-op
            // between connections, and this child dies with the connectJob on disconnect().
            killswitch?.let { ks ->
                launch { ks.blocked.collect { blocked -> if (blocked) socket.close() } }
            }
            while (isActive) {
                // IP killswitch gate: if blocked (e.g. VPN down), hold here in Blocked state and
                // wait until protected again before attempting (or re-attempting) the connection.
                killswitch?.let { ks ->
                    if (ks.blocked.value) {
                        _state.update { it.copy(connection = ConnectionState.Blocked) }
                        debugLog("killswitch", "connection gated — no VPN tunnel detected")
                        ks.blocked.first { !it }
                        debugLog("killswitch", "VPN tunnel detected — releasing gate")
                        first = true // fresh attempt: skip the "Reconnecting" flash below
                        _state.update { it.copy(connection = ConnectionState.Connecting) }
                    }
                }
                if (!isActive) break
                if (!first) _state.update { it.copy(connection = ConnectionState.Reconnecting) }
                first = false

                // A prior attempt was rejected at join: renew the token before reconnecting.
                // Join rejections are usually an expired PoW clearance (~18h) or session, not
                // a real permissions block, so this recovers the common case silently.
                if (refreshBeforeNext) {
                    refreshBeforeNext = false
                    _state.update { it.copy(connection = ConnectionState.Reconnecting) }
                    debugLog("auth", "join rejected — refreshing token ($joinFailures/$JOIN_FAIL_LIMIT)")
                    // refreshAuth handles routing the user to sign-in when the session is truly
                    // dead (it emits sessionExpired, which tears this connection down). A false
                    // here may just be a transient PoW/network hiccup, so don't give up — fall
                    // through and let the normal reconnect+backoff loop try again.
                    runCatching { refreshAuth() }
                        .onFailure { debugLog("auth", "refresh failed: ${it.message}") }
                }

                try {
                    socket.connect(wsUrl, sessionCookies()).collect { signal ->
                        when (signal) {
                            is SocketSignal.Open -> {
                                // Whispers-only background mode skips the join: no room subscription,
                                // no message firehose, but DMs still arrive (connection-level).
                                if (joinedMode) socket.join(roomId)
                                notificationsArmed = false // suppress notifications for the replayed scrollback
                                backoff = INITIAL_BACKOFF_MS
                                _state.update { it.copy(connection = ConnectionState.Online) }
                                debugLog("connection", "OPEN — joined room $roomId")
                            }
                            is SocketSignal.Event -> {
                                // Log everything except plain chat messages.
                                if (signal.event !is ChatEvent.Messages) {
                                    debugLog(signal.event::class.simpleName ?: "Event", signal.raw)
                                }
                                // A successful join delivers room permissions; reaching that
                                // means we're really in, so clear the join-failure counter.
                                if (signal.event is ChatEvent.Permissions) joinFailures = 0

                                val event = signal.event
                                if (event is ChatEvent.System && isJoinFailure(event.text) &&
                                    joinFailures < JOIN_FAIL_LIMIT
                                ) {
                                    // Auto-recover instead of showing the "try refreshing"
                                    // notice: bump the counter, ask for a token refresh, and
                                    // close so the loop reconnects with fresh cookies.
                                    joinFailures++
                                    refreshBeforeNext = true
                                    debugLog("connection", "join rejected: ${event.text}")
                                    socket.close()
                                } else {
                                    if (event is ChatEvent.System && isJoinFailure(event.text)) {
                                        // Retries exhausted: this may be a genuine permission
                                        // or threshold block, so let the server's notice show.
                                        errors.report("Couldn't join the room — it may be restricted.")
                                    }
                                    applyEvent(event)
                                }
                            }
                            is SocketSignal.Closed -> debugLog("connection", "CLOSED ${signal.code} ${signal.reason}")
                            is SocketSignal.Failure -> Unit // reported by the catch below (toast + log)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The UI shows a top "Reconnecting…" banner off the Reconnecting state, so
                    // no toast here; just record the failure for the debug window.
                    debugLog("connection", "FAILURE: ${e.message}")
                }
                if (!isActive) break
                _state.update { it.copy(connection = ConnectionState.Reconnecting) }
                // Skip the backoff when we're deliberately refreshing after a join reject;
                // the PoW solve + login round-trips are already a natural pause.
                if (!refreshBeforeNext) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    /** The server's join-rejection notice (KiwiFarms: "You cannot join this room…"). */
    private fun isJoinFailure(text: String): Boolean =
        text.contains("cannot join", ignoreCase = true) || text.contains("can't join", ignoreCase = true)

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        socket.close()
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    /** True once [connect] has been called and we have parameters to re-open with. */
    val isConfigured: Boolean get() = cfg != null

    /** Whether a connection is currently live (or attempting). */
    val isConnected: Boolean get() = connectJob?.isActive == true

    /**
     * Re-open the stored connection in a specific join mode, but only when something actually
     * changes (not already connected in that mode) — avoids needless reconnect churn on every
     * foreground/background flip. No-op if [connect] was never called.
     */
    fun ensureJoinMode(joinRoom: Boolean) {
        val c = cfg ?: return
        if (isConnected && joinedMode == joinRoom) return
        connect(c.wsUrl, c.roomId, c.sessionCookies, c.refreshAuth, joinRoom)
    }

    /** Outbound. Reports an error toast if the socket isn't connected. */
    fun send(command: ChatCommand): Boolean {
        val ok = socket.send(command)
        if (!ok) errors.report("Couldn't send — not connected")
        return ok
    }

    /** Clear the message store and roster (e.g. when switching rooms). */
    fun clear() {
        items.clear()
        roster.clear()
        motds.clear() // MOTDs are per-room
        _state.update { it.copy(timeline = emptyList(), online = 0, roster = emptyList(), motds = emptyList()) }
    }

    private fun applyEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Messages -> {
                event.messages.forEach { putMessage(it) }
                // The first batch after a (re)join is the room's scrollback; everything after it
                // is live and eligible to notify.
                notificationsArmed = true
            }
            is ChatEvent.Motd -> putMotd(event.message)
            is ChatEvent.Deleted -> event.uuids.forEach { items.remove(it); motds.remove(it) }
            is ChatEvent.System -> {
                val item = TimelineItem.Sys(sysCounter++, event.text)
                items[item.key] = item
            }
            is ChatEvent.UsersJoined -> event.users.forEach { roster[it.id] = it }
            is ChatEvent.UsersParted -> event.ids.forEach { roster.remove(it) }
            is ChatEvent.Permissions -> _state.update { it.copy(permissions = event.permissions) }
            is ChatEvent.Whisper -> { applyWhisper(event.whisper); return }
            is ChatEvent.Unknown -> Unit
        }
        trimToCap()
        emitTimeline()
    }

    private fun applyWhisper(w: st.kiwifarms.sneedroid.core.model.WhisperMessage) {
        val fromMe = (myUserId != null && w.author.id == myUserId) ||
            (myUsername != null && w.author.username.equals(myUsername, ignoreCase = true))
        val partner = if (fromMe) w.recipient else w.author
        val body = w.raw.ifEmpty { w.message }
        synchronized(convsLock) {
            val conv = convs.getOrPut(partner.id) { Conv(partner.id) }
            conv.name = partner.username
            conv.avatar = partner.avatarUrl
            conv.lines.add(WhisperLine(fromMe, body, w.date))
            conv.lastActivity = maxOf(conv.lastActivity, w.date)
            if (!fromMe) conv.unread++
        }
        emitWhispers()
        saveWhispers()
        // Whispers always arrive live (the server doesn't replay DM history), so no arming gate.
        // Notify unless you're actively viewing this exact thread (open on screen in the foreground).
        val viewingThisThread = appForeground && openWhisperPartner == partner.id
        if (!fromMe && notifyWhispers && !viewingThisThread) {
            notifier?.whisper(partner.id, partner.username, (w.message.ifEmpty { body }).take(NOTIFY_PREVIEW_CHARS))
        } else if (!fromMe) {
            debug.add("notify", "whisper from ${partner.username} suppressed " +
                "(notifyWhispers=$notifyWhispers fg=$appForeground openThread=$openWhisperPartner)")
        }
    }

    /**
     * Restore persisted whisper threads for the current identity. Call once, before [connect]
     * opens the socket, so it can't race the event collector that also mutates [convs]. Only
     * fills partners not already present, so a freshly-arrived live whisper is never overwritten.
     */
    fun restoreWhispers() {
        val store = whisperStore ?: return
        if (!whisperPersistence) return
        synchronized(convsLock) {
            store.load(myUserId).forEach { c ->
                if (convs.containsKey(c.partnerId)) return@forEach
                val conv = Conv(c.partnerId)
                conv.name = c.partnerName
                conv.avatar = c.partnerAvatar
                conv.lines.addAll(c.lines)
                conv.unread = c.unread
                conv.lastActivity = c.lastActivity
                convs[c.partnerId] = conv
            }
        }
        emitWhispers()
    }

    /** Snapshot the threads on the caller's thread, then write off-thread. */
    private fun saveWhispers() {
        val store = whisperStore ?: return
        if (!whisperPersistence) return
        val id = myUserId
        val snapshot = synchronized(convsLock) { convs.values.map { it.toPublic() } }
        scope.launch { store.save(id, snapshot) }
    }

    /** Seed roster users (debug preview only). */
    fun seedRoster(users: List<ChatUser>) {
        users.forEach { roster[it.id] = it }
        emitTimeline()
    }

    /** Seed a pinned MOTD (debug preview only). */
    fun seedMotd(message: ChatMessage) {
        putMotd(message)
        _state.update { it.copy(motds = motds.values.toList()) }
    }

    /** Seed a whisper conversation (debug preview only). */
    fun seedWhisper(partnerId: Long, name: String, lines: List<WhisperLine>, unread: Int) {
        synchronized(convsLock) {
            val conv = Conv(partnerId)
            conv.name = name
            conv.lines.addAll(lines)
            conv.lastActivity = lines.maxOfOrNull { it.date } ?: 0
            conv.unread = unread
            convs[partnerId] = conv
        }
        emitWhispers()
    }

    /** Clear the unread badge for a conversation (called when its window is opened). */
    fun markWhisperRead(partnerId: Long) {
        val changed = synchronized(convsLock) { convs[partnerId]?.also { it.unread = 0 } != null }
        if (changed) { emitWhispers(); saveWhispers() }
    }

    private fun emitWhispers() {
        _whispers.value = synchronized(convsLock) { convs.values.map { it.toPublic() } }
            .sortedByDescending { it.lastActivity }
    }

    private fun putMessage(message: ChatMessage) {
        learnSelf(message.author)
        val item = TimelineItem.Msg(message)
        val isNew = !items.containsKey(item.key) // an edit re-sends the same key; don't re-notify
        items[item.key] = item // replaces in place on edit, preserving order
        if (isNew) maybeNotifyMention(message)
    }

    /** Fire a mention notification for a freshly-arrived live message that @-mentions us. */
    private fun maybeNotifyMention(message: ChatMessage) {
        if (!notificationsArmed || !notifyMentions || appForeground) return
        val me = myUsername ?: return
        val author = message.author
        if (myUserId != null && author.id == myUserId) return // our own message
        if (author.username.equals(me, ignoreCase = true)) return
        val body = message.raw.ifEmpty { message.message }
        if (!mentionsUser(body, me)) return
        notifier?.mention(author.username, message.message.ifEmpty { body }.take(NOTIFY_PREVIEW_CHARS))
    }

    /** Whole-token, case-insensitive `@name` match (so `@bob` doesn't fire on `@bobby`). */
    private fun mentionsUser(text: String, name: String): Boolean =
        Regex("(?<![A-Za-z0-9_])@" + Regex.escape(name) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    /** Store/replace a pinned MOTD (keyed by uuid), kept out of the scrollable timeline. */
    private fun putMotd(message: ChatMessage) {
        val key = message.uuid ?: "motd${message.id ?: message.date}"
        motds[key] = message
    }

    /**
     * Learn our own chat username from a message authored by our own [myUserId]. The echoed
     * `author.username` is the authoritative display name (the login field may be an email),
     * so adopt it whenever it differs from what we hold — this also corrects a stale value.
     * Matched by id (always known from the xf_user cookie).
     */
    private fun learnSelf(author: ChatAuthor) {
        if (myUserId != null && author.id == myUserId && author.username.isNotBlank() && author.username != myUsername) {
            myUsername = author.username
            _state.update { it.copy(selfName = author.username) }
            onSelfUsernameLearned?.invoke(author.username)
        }
    }

    private fun trimToCap() {
        while (items.size > SCROLLBACK_CAP) {
            val eldest = items.keys.firstOrNull() ?: break
            items.remove(eldest)
        }
    }

    private fun emitTimeline() {
        _state.update {
            it.copy(
                timeline = items.values.toList(),
                online = roster.size,
                roster = roster.values.toList(),
                motds = motds.values.toList(),
            )
        }
    }

    /** Inject items for offline UI verification (no live connection). */
    fun seed(roomTitle: String, online: Int, seeded: List<TimelineItem>) {
        items.clear()
        seeded.forEach { items[it.key] = it }
        _state.update {
            it.copy(
                roomTitle = roomTitle,
                online = online,
                timeline = items.values.toList(),
                connection = ConnectionState.Online,
            )
        }
    }

    private companion object {
        const val SCROLLBACK_CAP = 500
        const val NOTIFY_PREVIEW_CHARS = 140
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
        // Token-refresh attempts on consecutive join rejections before giving up and
        // treating it as a real permission/threshold block. Mirrors the bot's JoinFailLimit.
        const val JOIN_FAIL_LIMIT = 2
    }
}
