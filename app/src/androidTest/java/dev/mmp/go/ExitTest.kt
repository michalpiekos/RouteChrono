package dev.mmp.go

import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mmp.core.newId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun exitRejectsQueuedReenableAndSurvivesPreferenceReopen() {
        val name = "exit-test-${newId()}"
        val isolated =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                    context.getSharedPreferences(name, mode)
            }
        try {
            val before = Tracking.generation(isolated)
            assertTrue(Tracking.automatic(isolated, before, true))
            Tracking.disable(isolated)
            assertFalse(Tracking.current(isolated, before))
            assertFalse(Tracking.automatic(isolated, before, true))
            assertFalse(Tracking.enabled(isolated))
            assertTrue(
                context.getSharedPreferences(name, Context.MODE_PRIVATE).getBoolean("exited", false)
            )
            assertEquals(before + 1, Tracking.generation(isolated))
            Tracking.disable(isolated)
            assertFalse(Tracking.enabled(isolated))
            assertFalse(Tracking.current(isolated, Tracking.generation(isolated)))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun ongoingNotificationShowsModeAndExitAlsoHandlesDismissal() {
        val notification =
            TrackingNotification.build(context, "Recording journey", "Waiting for GPS")
        assertEquals(
            "Route Chrono • Recording journey",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals("Waiting for GPS", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Exit", notification.actions.single().title.toString())
        assertEquals(notification.actions.single().actionIntent, notification.deleteIntent)
        assertNotEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
    }
}
