package dev.mmp.go

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
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
class ThreePointUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fork =
        EndpointPair("fork", "Home routes", Point(52.0, 19.0), Point(52.0, 19.03), Point(52.03, 19.0))

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
        val result = mutableListOf<String>()
        fun collect(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                when (val child = group.getChildAt(index)) {
                    is TextView -> result += child.text.toString()
                    is ViewGroup -> collect(child)
                }
            }
        }
        scenario.onActivity { collect(field("body").get(it) as LinearLayout) }
        return result
    }

    private fun withActivity(
        setup: (Store) -> Unit,
        prepare: (MainActivity) -> Unit,
        test: (Store, ActivityScenario<MainActivity>) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "three-point-ui-${newId()}.db"
        val store = Store(context, name)
        try {
            setup(store)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    field("generation").setInt(activity, field("generation").getInt(activity) + 1)
                    field("resumed").setBoolean(activity, false)
                    field("store").set(activity, store)
                    prepare(activity)
                    load(activity)
                }
                test(store, scenario)
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
    fun resultsGroupTripsByReachedEnd() {
        val trips =
            listOf(
                Triple("outward", 1, "complete"),
                Triple("return", 1, "complete"),
                Triple("outward", 2, "complete"),
                Triple("return", 2, "interrupted"),
                Triple("outward", null, "interrupted"),
            )
        withActivity(
            setup = { store ->
                store.dao.pair(PairRow.from(fork))
                trips.forEachIndexed { index, (direction, end, status) ->
                    store.dao.journey(
                        JourneyRow("j$index", fork.id, direction, "automatic", 100000L + index, 0, 110000, 10000L * (index + 1), status, end)
                    )
                }
            },
            prepare = {
                field("tab").set(it, "Results")
                field("selectedPair").set(it, fork.id)
            },
        ) { _, scenario ->
            val headings = listOf("Start → End 1", "End 1 → Start", "Start → End 2", "End 2 → Start", "Start → no end reached")
            waitFor("All direction groups must appear") { texts(scenario).containsAll(headings) }
            assertEquals(headings, texts(scenario).filter { it in headings })
            assertFalse(texts(scenario).contains("Start → End"))
        }
    }

    @Test
    fun editorSetsClearsAndValidatesSecondEnd() {
        withActivity(
            setup = {},
            prepare = {
                field("tab").set(it, "Pairs")
                field("editing").setBoolean(it, true)
                field("draftStart").set(it, fork.start)
                field("draftEnd").set(it, fork.end)
                field("draftEnd2").set(it, fork.end2)
                field("pointToSet").setInt(it, 2)
            },
        ) { store, scenario ->
            waitFor("Second end must be shown as set") { texts(scenario).contains("Set end 2 ✓") }
            assertTrue(texts(scenario).contains("Tap the map for the optional second end point"))
            click("Clear end 2")
            waitFor("Second end must be optional again") { texts(scenario).contains("Set end 2 (optional)") }
            assertFalse(texts(scenario).contains("Clear end 2"))
            scenario.onActivity {
                assertNull(field("draftEnd2").get(it))
                assertEquals(1, field("pointToSet").getInt(it))
                field("draftEnd2").set(it, Point(52.0, 19.0305))
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(it)
            }
            click("Save pair")
            scenario.onActivity { assertTrue(field("editing").getBoolean(it)) }
            assertTrue(store.dao.pairs().isEmpty())
        }
    }
}
