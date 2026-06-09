package st.kiwifarms.sneedroid

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import st.kiwifarms.sneedroid.data.BackgroundMode

/**
 * Foreground service that keeps the process alive (and exempt from Doze network limits) so the
 * chat connection can deliver whisper/mention notifications while the app is closed.
 *
 * It does not own the connection mode by itself: while the app is visible the Activity +
 * [st.kiwifarms.sneedroid.data.ConnectionController] drive it. The service only takes over when
 * it starts with no foreground Activity present — i.e. the system restarted it (START_STICKY)
 * after a process kill — in which case it re-establishes the background connection from scratch.
 */
class BackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var killswitchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = applicationContext.appContainer
        val mode = container.settingsRepository.settings.value.backgroundMode
        val loggedIn = container.authRepository.sessionCookies().isNotEmpty()

        // We were started with startForegroundService(), so the system requires startForeground()
        // within a few seconds — call it before any early return or it crashes the service.
        ServiceCompat.startForeground(
            this,
            container.notifier.ongoingId,
            container.notifier.ongoing(ongoingText(mode)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )

        if (mode == BackgroundMode.Off || !loggedIn) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // Keep the ongoing notification honest: show "paused" while the killswitch blocks (no VPN),
        // since the connection it claims to hold is gated shut. Set up once for the service's life.
        if (killswitchJob == null) {
            killswitchJob = scope.launch {
                container.killswitchGate.blocked.collect { blocked ->
                    val now = container.settingsRepository.settings.value.backgroundMode
                    val text = if (blocked) "Paused — connect a VPN (killswitch)" else ongoingText(now)
                    runCatching {
                        NotificationManagerCompat.from(this@BackgroundService)
                            .notify(container.notifier.ongoingId, container.notifier.ongoing(text))
                    }
                }
            }
        }

        // If no Activity is in the foreground, the connection isn't being driven by the UI
        // (cold restart after a kill) — bring it up ourselves. When the app is visible, the
        // Activity/controller already manage the connection, so we just hold the process.
        if (!container.chatRepository.appForeground) {
            container.connectionController.ensureBackgroundConnection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ongoingText(mode: BackgroundMode): String = when (mode) {
        BackgroundMode.WhispersAndMentions -> "Listening for whispers & mentions"
        else -> "Listening for whispers"
    }

    private fun stopForegroundCompat() =
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
}
