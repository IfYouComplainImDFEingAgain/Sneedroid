package st.kiwifarms.sneedroid.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A captured entry for the debug window: a WS frame, a connection event, or an error. */
data class DebugFrame(val time: Long, val type: String, val raw: String)

/** Bounded, app-wide log shown in the debug window (WS frames + errors). */
class DebugLog(private val cap: Int = 300) {
    private val _frames = MutableStateFlow<List<DebugFrame>>(emptyList())
    val frames: StateFlow<List<DebugFrame>> = _frames.asStateFlow()

    fun add(type: String, raw: String) {
        _frames.value = (_frames.value + DebugFrame(System.currentTimeMillis(), type, raw)).takeLast(cap)
    }

    fun clear() {
        _frames.value = emptyList()
    }
}

/**
 * Central place to surface non-fatal application errors. Each report shows a transient
 * toast (via [toasts]) and is recorded in the [DebugLog] so it can be inspected later.
 */
class ErrorReporter(private val log: DebugLog) {
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    fun report(message: String, detail: String? = null) {
        log.add("error", if (detail.isNullOrBlank()) message else "$message — $detail")
        _toasts.tryEmit(message)
    }
}
