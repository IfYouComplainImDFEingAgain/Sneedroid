package st.kiwifarms.sneedroid.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import st.kiwifarms.sneedroid.MainActivity
import st.kiwifarms.sneedroid.R

/**
 * Posts system notifications for incoming whispers and @-mentions. Channels are created up front;
 * posting is a no-op when the runtime POST_NOTIFICATIONS permission (API 33+) hasn't been granted,
 * so callers never have to guard the permission themselves.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CH_WHISPERS, "Whispers", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Private messages sent to you"
                },
            )
            mgr.createNotificationChannel(
                NotificationChannel(CH_MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When someone @-mentions you in a room"
                },
            )
            mgr.createNotificationChannel(
                NotificationChannel(CH_BACKGROUND, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "The ongoing notification shown while listening in the background"
                    setShowBadge(false)
                },
            )
        }
    }

    /** The ongoing notification a foreground service must display while it holds the connection. */
    fun ongoing(text: String): android.app.Notification =
        NotificationCompat.Builder(context, CH_BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sneedroid")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, CH_BACKGROUND.hashCode(),
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    /** Stable id for the foreground-service notification. */
    val ongoingId: Int get() = ONGOING_ID

    /** True only when we're actually allowed to post (always true below API 33). */
    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** A whisper from [partnerName]. Notifications for one partner collapse onto a stable id. */
    fun whisper(partnerId: Long, partnerName: String, text: String) {
        post(CH_WHISPERS, id = "w$partnerId".hashCode(), title = partnerName, text = text)
    }

    /** An @-mention by [author] in the current room. */
    fun mention(author: String, text: String) {
        post(CH_MENTIONS, id = "m$author".hashCode(), title = "$author mentioned you", text = text)
    }

    private fun post(channel: String, id: Int, title: String, text: String) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, channel.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    private companion object {
        const val CH_WHISPERS = "whispers"
        const val CH_MENTIONS = "mentions"
        const val CH_BACKGROUND = "background"
        const val ONGOING_ID = 1
    }
}
