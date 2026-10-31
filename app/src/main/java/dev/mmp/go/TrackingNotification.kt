package dev.mmp.go

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import dev.mmp.core.DropReason
import java.text.DateFormat
import java.util.Date

object TrackingNotification {
    const val CHANNEL = "journeys"
    const val ID = 1
    const val DROPPED_ID = 2

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

    fun reasonText(reason: DropReason) =
        when (reason) {
            DropReason.ABANDONED -> "it moved over 50% farther than the endpoint distance"
            DropReason.GPS_GAP -> "GPS was lost for over 30 s"
            DropReason.GPS_JUMP -> "the GPS position jumped implausibly far"
            DropReason.OTHER_END -> "it reached another end instead of the start"
            DropReason.RETURNED -> "it came back to where it started"
            DropReason.PAUSED -> "automatic recording was turned off"
            DropReason.PAIRS_CHANGED -> "the endpoint pairs were changed"
            DropReason.MANUAL_STARTED -> "a manual recording was started"
            DropReason.EXITED -> "tracking was exited"
            DropReason.STOPPED -> "tracking stopped"
            DropReason.RESTARTED -> "the app was restarted"
        }

    fun droppedMessage(drop: DroppedJourney) =
        "${journeyLabel(drop.pair, drop.journey)}: dropped because ${reasonText(drop.reason)}. Nothing was saved."

    fun dropped(c: Context, drops: List<DroppedJourney>) {
        if (drops.isEmpty()) return
        val messages = drops.map(::droppedMessage)
        Tracking.prefs(c).edit { putString("last_result", messages.joinToString("\n")) }
        drops.zip(messages).forEach { (drop, message) -> dropped(c, drop.journey.id, message) }
    }

    fun dropped(c: Context, tag: String, detail: String) {
        val notification =
            NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_tracking)
                .setContentTitle(c.getString(R.string.dropped_notification_title, c.getString(R.string.app_name)))
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        c.getSystemService(NotificationManager::class.java).notify(tag, DROPPED_ID, notification)
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

fun directionLabel(pair: PairRow?, j: JourneyRow): String {
    val multiEnd = pair?.spec()?.multiEnd == true
    val end = if (!multiEnd) "End" else j.endpoint?.let { "End $it" }
    return if (j.direction == "return") "${end ?: "End"} → Start" else "Start → ${end ?: "an end"}"
}

fun journeyLabel(pair: PairRow?, j: JourneyRow): String {
    val started = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(j.started))
    return if (j.source == "manual") "Manual recording, started $started"
    else "${pair?.name ?: "Recorded journey"} (${directionLabel(pair, j)}), started $started"
}
