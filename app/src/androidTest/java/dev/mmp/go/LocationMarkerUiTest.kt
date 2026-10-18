package dev.mmp.go

import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.CancellationTokenSource
import dev.mmp.core.EndpointPair
import dev.mmp.core.Point
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class LocationMarkerUiTest {
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun position(latitude: Double, longitude: Double) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        time = 1700000000000
        accuracy = 5f
    }
    private fun current(activity: MainActivity) = field("locatedPosition").get(activity) as Location?
    private fun map(activity: MainActivity) = field("map").get(activity) as MapLibreMap
    private fun request(activity: MainActivity) = CancellationTokenSource().also { field("locating").set(activity, it) }
    private fun complete(activity: MainActivity, token: CancellationTokenSource, location: Location?) {
        MainActivity::class.java.getDeclaredMethod("completeLocation", CancellationTokenSource::class.java, Location::class.java)
            .apply { isAccessible = true }.invoke(activity, token, location)
    }
    private fun draw(activity: MainActivity, fit: Boolean) {
        MainActivity::class.java.getDeclaredMethod("drawMap", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, fit)
    }
    private fun ready(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val m = field("map").get(activity) as MapLibreMap?
                val view = field("mapView").get(activity) as MapView
                ready = m?.style != null && view.width > 0 && view.height > 0
                if (ready) {
                    field("resumed").setBoolean(activity, false)
                    field("generation").setInt(activity, field("generation").getInt(activity) + 1)
                    field("journeys").set(activity, emptyList<JourneyRow>())
                    field("pairs").set(activity, emptyList<PairRow>())
                }
            }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("Map must load", ready)
    }
    private fun markerVisible(scenario: ActivityScenario<MainActivity>, expected: Location) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var visible = false
        while (!visible && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val m = map(activity)
                val pixel = m.projection.toScreenLocation(LatLng(expected.latitude, expected.longitude))
                visible = m.queryRenderedFeatures(pixel, "my-location-dot").size == 1 &&
                    kotlin.math.abs(m.cameraPosition.target!!.latitude - expected.latitude) < 0.00001 &&
                    kotlin.math.abs(m.cameraPosition.target!!.longitude - expected.longitude) < 0.00001
            }
            if (!visible) SystemClock.sleep(50)
        }
        assertTrue("Exactly one location dot must render at the centered fix", visible)
    }

    @Test fun successfulLookupMovesOneDotAndSurvivesRedrawsAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            val first = position(52.0, 19.0)
            val next = position(52.001, 19.002)
            scenario.onActivity { complete(it, request(it), first) }
            markerVisible(scenario, first)
            scenario.onActivity { activity ->
                complete(activity, request(activity), next)
                repeat(3) { draw(activity, false) }
                field("tab").set(activity, "Pairs")
                field("editing").setBoolean(activity, true)
                field("draftStart").set(activity, Point(52.0, 19.0))
                draw(activity, false)
                assertEquals(1, map(activity).style!!.layers.count { it.id == "my-location-dot" })
                assertTrue(map(activity).markers.any { it.title == "Start" })
                assertEquals(next.latitude, current(activity)!!.latitude, 0.0)
            }
            markerVisible(scenario, next)
            scenario.recreate()
            ready(scenario)
            scenario.onActivity { activity ->
                assertEquals(next.latitude, current(activity)!!.latitude, 0.0)
                assertEquals(next.longitude, current(activity)!!.longitude, 0.0)
                assertEquals(next.time, current(activity)!!.time)
                draw(activity, false)
                assertNotNull(map(activity).style!!.getLayer("my-location-dot"))
            }
        }
    }

    @Test fun failedCancelledSupersededAndClosingRequestsCannotReplaceTheFix() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                complete(activity, request(activity), null)
                assertNull(current(activity))
                assertNull(map(activity).style!!.getLayer("my-location-dot"))
                val old = request(activity)
                val latest = request(activity)
                complete(activity, latest, position(52.0, 19.0))
                complete(activity, old, position(10.0, 10.0))
                val cancelled = request(activity)
                cancelled.cancel()
                complete(activity, cancelled, position(11.0, 11.0))
                complete(activity, request(activity), null)
                complete(activity, request(activity), position(Double.NaN, 19.0))
                val failed = request(activity)
                MainActivity::class.java.getDeclaredMethod("failLocation", CancellationTokenSource::class.java)
                    .apply { isAccessible = true }.invoke(activity, failed)
                field("exiting").setBoolean(activity, true)
                try { complete(activity, request(activity), position(12.0, 12.0)) }
                finally { field("exiting").setBoolean(activity, false) }
                assertEquals(52.0, current(activity)!!.latitude, 0.0)
                assertEquals(19.0, current(activity)!!.longitude, 0.0)
                assertEquals(1, map(activity).style!!.layers.count { it.id == "my-location-dot" })
            }
        }
    }

    @Test fun locationDotDoesNotChangeEndpointFramingOrDrafts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                complete(activity, request(activity), position(0.0, 0.0))
                map(activity).cancelTransitions()
                val pair = EndpointPair("test", "Test pair", Point(52.0, 19.0), Point(52.0, 19.03))
                field("pairs").set(activity, listOf(PairRow.from(pair)))
                field("tab").set(activity, "Pairs")
                field("editing").setBoolean(activity, false)
                draw(activity, true)
                val target = map(activity).cameraPosition.target!!
                assertEquals(52.0, target.latitude, 0.01)
                assertEquals(19.015, target.longitude, 0.01)
                assertEquals(2, map(activity).markers.size)
                assertNull(field("draftStart").get(activity))
                assertNull(field("draftEnd").get(activity))
            }
        }
    }
}
