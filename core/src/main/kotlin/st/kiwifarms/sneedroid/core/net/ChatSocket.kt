package st.kiwifarms.sneedroid.core.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import st.kiwifarms.sneedroid.core.protocol.ChatCommand
import st.kiwifarms.sneedroid.core.protocol.ChatEvent
import st.kiwifarms.sneedroid.core.protocol.ChatProtocol

/** A connection-level signal from the chat WebSocket. */
sealed interface SocketSignal {
    data object Open : SocketSignal
    /** A parsed inbound frame, with the original [raw] text for debugging. */
    data class Event(val event: ChatEvent, val raw: String) : SocketSignal
    data class Closed(val code: Int, val reason: String) : SocketSignal
    data class Failure(val error: Throwable) : SocketSignal
}

/**
 * The SneedChat WebSocket client. Connects with the session cookies in the upgrade
 * request, parses inbound frames via [ChatProtocol], and sends outbound [ChatCommand]s.
 *
 * One [ChatSocket] drives one connection at a time; the reconnect/backoff loop lives a
 * layer up (the repository) per docs/03-architecture.md.
 *
 * **No keep-alive ping.** There is no valid `/ping` chat command, and the server does **not**
 * answer WebSocket PING control frames with a PONG — so OkHttp's `pingInterval` can't be used
 * (it would fail the socket every interval for a missing pong, causing a reconnect loop). The
 * connection is kept warm by normal room traffic; if it goes idle and the server drops it
 * (~300s inactivity), the repository's reconnect/backoff loop re-establishes it.
 */
class ChatSocket(private val client: OkHttpClient = OkHttpClient()) {

    @Volatile
    private var webSocket: WebSocket? = null

    /**
     * Open a connection and emit [SocketSignal]s as a cold [Flow]. Collecting starts the
     * connection; cancelling the collector tears it down.
     */
    fun connect(wsUrl: String, cookies: List<Cookie>): Flow<SocketSignal> = callbackFlow {
        val request = Request.Builder()
            .url(wsUrl)
            .header("Cookie", cookies.toCookieHeader())
            .header("User-Agent", KiwiFarmsClient.USER_AGENT)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                trySend(SocketSignal.Open)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                trySend(SocketSignal.Event(ChatProtocol.parse(text), text))
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                trySend(SocketSignal.Closed(code, reason))
                channel.close()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                trySend(SocketSignal.Failure(t))
                channel.close(t)
            }
        }

        val ws = client.newWebSocket(request, listener)
        webSocket = ws
        awaitClose {
            ws.cancel()
            webSocket = null
        }
    }

    /** Send a command on the current connection. Returns false if not connected. */
    fun send(command: ChatCommand): Boolean =
        webSocket?.send(ChatProtocol.encode(command)) ?: false

    /** Convenience: join a room. */
    fun join(roomId: Int): Boolean = send(ChatCommand.Join(roomId))

    fun close(code: Int = 1000, reason: String? = null) {
        webSocket?.close(code, reason)
    }

    companion object {
        fun List<Cookie>.toCookieHeader(): String = joinToString("; ") { "${it.name}=${it.value}" }
    }
}
