package dev.mmp.go

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mmp.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AbandonTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun fix(x: Double, mono: Long) = Fix(Point(0.0, x), 100000 + mono, mono, 5.0)

    @Test
    fun abandonedTripLeavesNoTraceAndOtherTripsRemain() {
        val name = "abandon-${newId()}.db"
        val store = Store(context, name)
        try {
            val pair = EndpointPair("short", "Short", Point(0.0, 0.0), Point(0.0, 0.01))
            val longer = EndpointPair("long", "Long", Point(0.0, 0.0), Point(0.0, -0.03))
            listOf(pair, longer).forEach { store.dao.pair(PairRow.from(it)) }
            val kept = JourneyRow("kept", pair.id, "outward", "automatic", 1, 1, 2, 1, "complete")
            store.dao.journey(kept)
            store.dao.point(TrackRow.from(kept.id, fix(0.0, 1)))
            val tracker = EndpointTracker(listOf(pair, longer))
            val events =
                listOf(fix(0.0, 0), fix(0.001, 10000), fix(-0.005, 20000), fix(-0.0151, 35000))
                    .flatMap { tracker.feed(it).also(store::accept) }
            val gone = events.filterIsInstance<TrackingEvent.Abandoned>().single()
            assertEquals(pair.id, gone.pair.id)
            assertNull(store.dao.journey(gone.id))
            assertTrue(store.dao.points(gone.id).isEmpty())
            val ongoing = store.dao.journeys().filter { it.status == "recording" }
            assertEquals(listOf(longer.id), ongoing.map { it.pairId })
            assertEquals(4, store.dao.points(ongoing.single().id).size)
            assertEquals(kept, store.dao.journey(kept.id))
            assertEquals(1, store.dao.points(kept.id).size)
        } finally {
            store.db.close()
            store.executor.shutdown()
            store.reader.shutdown()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun abandonNotificationIsSeparateAndDismissible() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = "abandon-test-${newId()}"
        TrackingNotification.createChannel(context)
        try {
            TrackingNotification.abandoned(context, tag, TrackingNotification.abandonedMessage("Home routes"))
            val posted =
                manager.activeNotifications.single {
                    it.tag == tag && it.id == TrackingNotification.ABANDONED_ID
                }
            val notification = posted.notification
            assertEquals(
                "Route Chrono • Trip abandoned",
                notification.extras.getString(Notification.EXTRA_TITLE),
            )
            assertTrue(
                notification.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("Home routes: moved over 50% farther")
            )
            assertEquals(TrackingNotification.CHANNEL, notification.channelId)
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertNotEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
            assertFalse(TrackingNotification.visible(context))
        } finally {
            manager.cancel(tag, TrackingNotification.ABANDONED_ID)
        }
    }
}
