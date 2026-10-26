package dev.mmp.go

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.TextView
import android.widget.ToggleButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mmp.core.*
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap

// Regenerates README screenshots from synthetic Kraków data: -e captureReadme true
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val market = Point(50.0617, 19.9373)
    private val kazimierz = Point(50.0516, 19.9447)
    private val station = Point(50.0676, 19.9475)
    private val wawel = Point(50.0540, 19.9354)
    private val podgorze = Point(50.0440, 19.9500)
    private val oldTown = EndpointPair("old-town", "Old Town", market, kazimierz, station)
    private val river = EndpointPair("river", "Wawel ⇄ Podgórze", wawel, podgorze)

    private val toKazimierz =
        listOf(market, Point(50.0610, 19.9378), Point(50.0590, 19.9380), Point(50.0565, 19.9383),
            Point(50.0545, 19.9398), Point(50.0530, 19.9418), Point(50.0522, 19.9428), Point(50.0518, 19.9440), kazimierz)
    private val toStation =
        listOf(market, Point(50.0635, 19.9390), Point(50.0650, 19.9410), Point(50.0660, 19.9440), Point(50.0668, 19.9458), station)
    private val acrossRiver =
        listOf(wawel, Point(50.0522, 19.9382), Point(50.0505, 19.9415), Point(50.0490, 19.9440), Point(50.0478, 19.9455),
            Point(50.0468, 19.9468))

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun call(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun load(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("load", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
    }

    private fun path(waypoints: List<Point>, startMono: Long, durationMs: Long, wall: Long): List<Fix> {
        val steps = (durationMs / 5000).toInt()
        return (0..steps).map { step ->
            val t = step.toDouble() / steps * (waypoints.size - 1)
            val i = t.toInt().coerceAtMost(waypoints.size - 2)
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val f = t - i
            val mono = startMono + step * durationMs / steps
            Fix(Point(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f), wall + mono - startMono, mono, 4.0)
        }
    }

    private fun wall(day: Int, hour: Int, minute: Int) =
        Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, hour, minute, 0) }.timeInMillis

    private fun trip(store: Store, id: String, waypoints: List<Point>, direction: String, endpoint: Int?, wall: Long,
                     durationMs: Long, status: String = "complete", pair: EndpointPair = oldTown) {
        val points = path(if (direction == "return") waypoints.reversed() else waypoints, 0, durationMs, wall)
        store.dao.journey(JourneyRow(id, pair.id, direction, "automatic", wall, 0, wall + durationMs, durationMs, status, endpoint))
        points.forEach { store.dao.point(TrackRow.from(id, it)) }
    }

    private fun seed(store: Store) {
        listOf(oldTown, river).forEach { store.dao.pair(PairRow.from(it)) }
        val m = 60_000L
        trip(store, "k1", toKazimierz, "outward", 1, wall(19, 8, 5), 6 * m + 12_000)
        trip(store, "k2", toKazimierz, "outward", 1, wall(21, 8, 10), 5 * m + 48_000)
        trip(store, "k3", toKazimierz, "outward", 1, wall(23, 7, 55), 6 * m + 40_000)
        trip(store, "k4", toKazimierz, "return", 1, wall(19, 17, 20), 6 * m + 5_000)
        trip(store, "k5", toKazimierz, "return", 1, wall(21, 17, 35), 6 * m + 55_000)
        trip(store, "s1", toStation, "outward", 2, wall(20, 9, 0), 4 * m + 20_000)
        trip(store, "s2", toStation, "outward", 2, wall(22, 9, 5), 4 * m + 45_000)
        trip(store, "s3", toStation, "return", 2, wall(20, 18, 10), 4 * m + 30_000)
        trip(store, "s4", toStation, "return", 2, wall(22, 18, 0), 5 * m + 10_000)
        val now = SystemClock.elapsedRealtime()
        val live = path(acrossRiver, now - 4 * m, 4 * m, System.currentTimeMillis() - 4 * m)
        store.dao.journey(JourneyRow("live", river.id, "outward", "automatic", live.first().wallMs, live.first().monoMs))
        live.forEach { store.dao.point(TrackRow.from("live", it)) }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(4000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "readme-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun show(scenario: ActivityScenario<MainActivity>, tab: String, pair: String?, journey: String?) {
        scenario.onActivity {
            field("tab").set(it, tab)
            field("selectedPair").set(it, pair)
            field("selectedJourney").set(it, journey)
            call(it, "updateTabs")
            load(it)
        }
    }

    @Test
    fun captureReadmeScreenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureReadme") == "true")
        assertFalse("Capture with tracking paused", Tracking.running)
        val name = "readme-${newId()}.db"
        val store = Store(context, name)
        try {
            seed(store)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 15000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = (field("map").get(it) as MapLibreMap?)?.style != null }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("Map style must load", ready)
                scenario.onActivity {
                    field("resumed").setBoolean(it, false)
                    field("store").set(it, store)
                    Tracking.running = true
                    Tracking.lastFixAt = SystemClock.elapsedRealtime()
                }
                try {
                    show(scenario, "Record", null, null)
                    SystemClock.sleep(1500)
                    scenario.onActivity {
                        call(it, "updateStatus")
                        (field("autoSwitch").get(it) as ToggleButton).apply {
                            setOnCheckedChangeListener(null)
                            isChecked = true
                        }
                        (field("recordInfo").get(it) as TextView?)?.text =
                            "Automatic recording is on. Both saved pairs are watched in either direction."
                    }
                    capture("record")
                } finally {
                    scenario.onActivity {
                        Tracking.running = false
                        Tracking.lastFixAt = 0
                        call(it, "updateStatus")
                    }
                }
                show(scenario, "Pairs", oldTown.id, null)
                capture("pairs")
                show(scenario, "Results", oldTown.id, null)
                capture("results")
                show(scenario, "Results", oldTown.id, "k2")
                capture("journey")
            }
        } finally {
            store.executor.submit {}.get(10, TimeUnit.SECONDS)
            store.reader.submit {}.get(10, TimeUnit.SECONDS)
            store.db.close()
            store.executor.shutdown()
            store.reader.shutdown()
            context.deleteDatabase(name)
        }
    }
}
