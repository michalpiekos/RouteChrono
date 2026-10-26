package dev.mmp.go

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object TrackingNotification {
    const val CHANNEL = "journeys"
    const val ID = 1
    const val ABANDONED_ID = 2

    fun createChannel(c: Context) {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL,
                        "Journey recording",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description = c.getString(R.string.tracking_channel_description, c.getString(R.string.app_name))
                    }
            )
    }

    fun allowed(c: Context): Boolean {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        val manager = c.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL) ?: return true
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (
            Build.VERSION.SDK_INT >= 28 &&
                channel.group != null &&
                manager.getNotificationChannelGroup(channel.group)?.isBlocked == true
        )
            return false
        return true
    }

    fun visible(c: Context) =
        c.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ID }

    private fun open(c: Context) =
        PendingIntent.getActivity(
            c,
            0,
            Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun abandonedMessage(pairName: String) =
        "$pairName: moved over 50% farther than the endpoint distance. The trip was discarded and nothing was saved."

    fun abandoned(c: Context, pairId: String, detail: String) {
        val notification =
            NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_tracking)
                .setContentTitle(c.getString(R.string.abandoned_notification_title, c.getString(R.string.app_name)))
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        c.getSystemService(NotificationManager::class.java).notify(pairId, ABANDONED_ID, notification)
    }

    fun build(c: Context, title: String, detail: String): Notification {
        val open = open(c)
        val exit =
            PendingIntent.getBroadcast(
                c,
                4,
                Intent(c, ExitReceiver::class.java).setAction("exit"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(c.getString(R.string.tracking_notification_title, c.getString(R.string.app_name), title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setDeleteIntent(exit)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Exit", exit)
            .build()
    }
}
