package dev.mmp.go

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mmp.core.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteRecordingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun load(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("load", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun click(text: String) {
        waitFor("Clickable $text must appear") {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true && it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun texts(scenario: ActivityScenario<MainActivity>): List<String> {
        var result = emptyList<String>()
        scenario.onActivity {
            val body = field("body").get(it) as LinearLayout
            result = (0 until body.childCount).mapNotNull { index ->
                (body.getChildAt(index) as? TextView)?.text?.toString()
            }
        }
        return result
    }

    private fun withRecording(
        pair: PairRow?,
        second: Boolean = false,
        test: (Store, ActivityScenario<MainActivity>, JourneyRow) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delete-ui-${newId()}.db"
        val store = Store(context, name)
        val recording = JourneyRow("chosen", pair?.id, "outward", "manual", 100000, 0, 110000, 10000, "complete")
        try {
            pair?.let { store.dao.pair(it) }
            store.dao.journey(recording)
            store.dao.point(TrackRow.from(recording.id, Fix(Point(52.0, 19.0), 100000, 0, 5.0)))
            if (second) store.dao.journey(recording.copy(id = "keep", durationMs = 20000))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    field("generation").setInt(activity, field("generation").getInt(activity) + 1)
                    field("resumed").setBoolean(activity, false)
                    field("store").set(activity, store)
                    field("tab").set(activity, "Results")
                    field("selectedPair").set(activity, pair?.id ?: "unpaired")
                    field("selectedJourney").set(activity, recording.id)
                    load(activity)
                }
                waitFor("Recording details must load") { texts(scenario).contains("Delete recording") }
                test(store, scenario, recording)
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

    @Test
    fun cancelKeepsRecordingAndConfirmRefreshesSummary() {
        val pair = PairRow.from(EndpointPair("pair", "Test endpoints", Point(52.0, 19.0), Point(52.0, 19.01)))
        withRecording(pair, second = true) { store, scenario, recording ->
            val points = store.dao.points(recording.id)
            click("Delete recording")
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow
                .findAccessibilityNodeInfosByText("This cannot be undone.").isNotEmpty())
            click("Cancel")
            assertEquals(recording, store.dao.journey(recording.id))
            assertEquals(points, store.dao.points(recording.id))
            assertTrue(texts(scenario).contains("Delete recording"))
            click("Delete recording")
            click("Delete")
            waitFor("Deleted recording must leave storage") { store.dao.journey(recording.id) == null }
            waitFor("Results must show remaining summary") {
                texts(scenario).any { it.startsWith("1 completed") && it.contains("0:20") }
            }
            assertTrue(store.dao.points(recording.id).isEmpty())
            assertEquals(listOf(pair), store.dao.pairs())
            assertEquals(listOf("keep"), store.dao.journeys().map { it.id })
            scenario.onActivity {
                assertNull(field("selectedJourney").get(it))
                assertEquals(pair.id, field("selectedPair").get(it))
                assertTrue((field("trace").get(it) as List<*>).isEmpty())
            }
        }
    }

    @Test
    fun deletingLastUnpairedRecordingReturnsToResultsRoot() {
        withRecording(null) { store, scenario, _ ->
            click("Delete recording")
            click("Delete")
            waitFor("Empty Results must appear") { texts(scenario).contains("Your recorded routes will appear here.") }
            assertTrue(store.dao.journeys().isEmpty())
            scenario.onActivity { assertNull(field("selectedPair").get(it)) }
        }
    }

    @Test
    fun deletingLastArchivedPairRecordingPreservesPairButHidesEmptyGroup() {
        val pair = PairRow.from(EndpointPair("archived", "Old endpoints", Point(52.0, 19.0), Point(52.0, 19.01))).copy(archived = true)
        withRecording(pair) { store, scenario, _ ->
            click("Delete recording")
            click("Delete")
            waitFor("Empty archived group must disappear") { texts(scenario).contains("Your recorded routes will appear here.") }
            assertEquals(listOf(pair), store.dao.pairs())
            scenario.onActivity { assertNull(field("selectedPair").get(it)) }
        }
    }

    @Test
    fun deletingLastRecordingOfSavedPairKeepsPairResultsAvailable() {
        val pair = PairRow.from(EndpointPair("saved", "Saved endpoints", Point(52.0, 19.0), Point(52.0, 19.01)))
        withRecording(pair) { store, scenario, _ ->
            click("Delete recording")
            click("Delete")
            waitFor("Saved pair must show its empty journey list") { texts(scenario).any { it.startsWith("No journeys recorded yet.") } }
            assertEquals(listOf(pair), store.dao.pairs())
            scenario.onActivity { assertEquals(pair.id, field("selectedPair").get(it)) }
        }
    }
}
