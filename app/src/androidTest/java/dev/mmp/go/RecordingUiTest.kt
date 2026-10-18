package dev.mmp.go

import android.graphics.PointF
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mmp.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@Suppress("DEPRECATION", "UNCHECKED_CAST")
@RunWith(AndroidJUnit4::class)
class RecordingUiTest {
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(activity: MainActivity, name: String, value: Any) = field(name).set(activity, value)
    private fun draw(activity: MainActivity, fit: Boolean) {
        MainActivity::class.java.getDeclaredMethod("drawMap", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, fit)
    }

    private fun withMap(test: (MainActivity, MapLibreMap, MapView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val map = field("map").get(activity) as MapLibreMap?
                    val view = field("mapView").get(activity) as MapView
                    ready = map?.style != null && view.width > 0 && view.height > 0
                }
                if (!ready) SystemClock.sleep(50)
            }
            assertTrue("Map style and viewport must load", ready)
            scenario.onActivity { activity ->
                set(activity, "resumed", false)
                set(activity, "generation", field("generation").getInt(activity) + 1)
                set(activity, "tab", "Record")
                set(activity, "pairs", emptyList<PairRow>())
                set(activity, "journeys", emptyList<JourneyRow>())
                test(activity, field("map").get(activity) as MapLibreMap, field("mapView").get(activity) as MapView)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun fix(position: LatLng, time: Long) = Fix(Point(position.latitude, position.longitude), time, time, 5.0)

    private fun trace(activity: MainActivity, points: List<Fix>) {
        set(activity, "trace", points)
        set(activity, "recordingTraces", listOf(points))
    }

    private fun assertVisible(map: MapLibreMap, view: MapView, point: Point) {
        val pixel = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
        assertTrue("$point must stay within the map", pixel.x in 0f..view.width.toFloat() && pixel.y in 0f..view.height.toFloat())
    }

    @Test
    fun teachingRecordingZoomsOutByOneLevelAtViewportEdge() = withMap { activity, map, view ->
        val start = LatLng(52.0, 19.0)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 16.0))
        val nearEdge = map.projection.fromScreenLocation(PointF(view.width - 2f, view.height / 2f))
        val points = listOf(fix(start, 0), fix(nearEdge, 10000))
        trace(activity, points)
        draw(activity, false)
        assertEquals(15.0, map.cameraPosition.zoom, 0.001)
        points.forEach { assertVisible(map, view, it.point) }
        draw(activity, false)
        assertEquals("No repeated zoom while points remain visible", 15.0, map.cameraPosition.zoom, 0.001)
        val nextEdge = map.projection.fromScreenLocation(PointF(view.width - 2f, view.height / 2f))
        val extended = points + fix(nextEdge, 20000)
        trace(activity, extended)
        draw(activity, false)
        assertEquals(14.0, map.cameraPosition.zoom, 0.001)
        listOf(extended.first(), extended.last()).forEach { assertVisible(map, view, it.point) }
    }

    @Test
    fun pairEndpointsRemainVisibleAndConcurrentPairsDoNotLockFraming() = withMap { activity, map, view ->
        val pair = EndpointPair("ui-pair", "Test pair", Point(52.0, 19.0), Point(52.0, 19.03))
        val other = pair.copy(id = "ui-other", name = "Other pair", end = Point(52.01, 19.0))
        set(activity, "pairs", listOf(PairRow.from(pair), PairRow.from(other)))
        val journey = JourneyRow("ui-journey", pair.id, "outward", "automatic", 0, 0)
        set(activity, "journeys", listOf(journey))
        trace(activity, listOf(Fix(pair.start, 0, 0, 5.0), Fix(Point(52.0, 19.005), 10000, 10000, 5.0)))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(52.0, 19.005), 16.0))
        draw(activity, false)
        assertVisible(map, view, pair.start)
        assertVisible(map, view, pair.end)
        assertTrue(map.markers.any { it.title == "Test pair · Start" })
        assertTrue(map.markers.any { it.title == "Test pair · End" })
        set(activity, "journeys", listOf(journey, journey.copy(id = "ui-journey-2", pairId = other.id)))
        val start = LatLng(52.0, 19.0)
        trace(activity, listOf(fix(start, 0)))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 16.0))
        draw(activity, false)
        assertEquals(16.0, map.cameraPosition.zoom, 0.001)
        assertFalse(map.markers.any { it.title?.contains("pair ·") == true })
    }

    @Test
    fun navigationTabsExposeSelectedState() = withMap { activity, _, _ ->
        val tabs = field("tabViews").get(activity) as List<TextView>
        assertEquals(listOf("Record", "Pairs", "Results"), tabs.map { it.text.toString() })
        for (tab in tabs) {
            tab.performClick()
            assertTrue(tab.isSelected)
            assertEquals(1, tabs.count { it.isSelected })
            val node = tab.createAccessibilityNodeInfo()
            assertTrue(node.isSelected)
            assertEquals("android.app.ActionBar\$Tab", node.className.toString())
        }
    }

    @Test
    fun automaticControlIsLargeAccessibleAndRejectsMissingPairs() = withMap { activity, _, _ ->
        val toggle = field("autoSwitch").get(activity) as android.widget.ToggleButton
        val enabled = Tracking.enabled(activity)
        MainActivity::class.java.getDeclaredMethod("toggleAutomatic", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
        assertEquals("Missing pairs must not enable tracking", enabled, Tracking.enabled(activity))
        assertEquals(enabled, toggle.isChecked)
        val node = toggle.createAccessibilityNodeInfo()
        assertEquals("android.widget.Switch", node.className.toString())
        assertTrue(node.isCheckable)
        assertEquals(enabled, node.isChecked)
        assertTrue(toggle.minimumHeight >= 72 * activity.resources.displayMetrics.density)
        assertTrue(toggle.text.contains(if (enabled) "ON" else "OFF"))
    }

    @Test
    fun recordingStatusAndGlowFollowLiveStateAndClearWhenStopped() {
        assertFalse("Run isolated UI checks with tracking paused", Tracking.running)
        val wasManual = Tracking.manual
        val lastFix = Tracking.lastFixAt
        val update = MainActivity::class.java.getDeclaredMethod("updateStatus").apply { isAccessible = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    set(activity, "resumed", false)
                    set(activity, "generation", field("generation").getInt(activity) + 1)
                    set(activity, "journeys", emptyList<JourneyRow>())
                    MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
                    Tracking.running = true
                    Tracking.manual = false
                    Tracking.lastFixAt = SystemClock.elapsedRealtime()
                    update.invoke(activity)
                    assertEquals(RecordingAppearanceState.MONITORING, field("displayedActivity").get(activity))
                    assertTrue((field("status").get(activity) as TextView).text.contains("Monitoring"))
                    val toggle = field("autoSwitch").get(activity) as android.widget.ToggleButton
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = true
                }
                captureAppearance("monitoring")
                scenario.onActivity { activity ->
                    Tracking.manual = true
                    Tracking.lastFixAt = 0
                    update.invoke(activity)
                    assertEquals(RecordingAppearanceState.RECORDING, field("displayedActivity").get(activity))
                    val status = field("status").get(activity) as TextView
                    assertTrue(status.text.contains("Recording"))
                    assertTrue(status.text.contains("waiting for precise GPS"))
                }
                captureAppearance("recording")
                scenario.onActivity { activity ->
                    Tracking.running = false
                    update.invoke(activity)
                    assertEquals(if (Tracking.enabled(activity)) RecordingAppearanceState.STOPPED else RecordingAppearanceState.PAUSED,
                        field("displayedActivity").get(activity))
                }
                captureAppearance("paused")
            } finally {
                scenario.onActivity { activity ->
                    Tracking.running = false
                    Tracking.manual = wasManual
                    Tracking.lastFixAt = lastFix
                    update.invoke(activity)
                }
            }
        }
    }

    private fun captureAppearance(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureAppearance") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
