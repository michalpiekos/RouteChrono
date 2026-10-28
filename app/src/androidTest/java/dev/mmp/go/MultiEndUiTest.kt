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
class MultiEndUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fork =
        EndpointPair("fork", "Home routes", Point(52.0, 19.0), Point(52.0, 19.03), Point(52.03, 19.0))
    private val star =
        EndpointPair(
            "star",
            "Star routes",
            Point(52.0, 19.0),
            Point(52.0, 19.03),
            Point(52.03, 19.0),
            Point(52.0, 18.97),
            Point(51.97, 19.0),
            Point(52.03, 19.03),
        )

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
        val name = "multi-end-ui-${newId()}.db"
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
    fun resultsGroupTripsByEachOfFiveEnds() {
        withActivity(
            setup = { store ->
                store.dao.pair(PairRow.from(star))
                (1..5).forEach { end ->
                    store.dao.journey(JourneyRow("o$end", star.id, "outward", "automatic", 100000L + end, 0, 110000, 10000L, "complete", end))
                    store.dao.journey(JourneyRow("r$end", star.id, "return", "automatic", 200000L + end, 0, 210000, 10000L, "complete", end))
                }
            },
            prepare = {
                field("tab").set(it, "Results")
                field("selectedPair").set(it, star.id)
            },
        ) { _, scenario ->
            val headings = (1..5).flatMap { listOf("Start → End $it", "End $it → Start") }
            waitFor("All five-end direction groups must appear") { texts(scenario).containsAll(headings) }
            assertEquals(headings, texts(scenario).filter { it in headings })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun draftEnds(activity: MainActivity) = field("draftEnds").get(activity) as MutableList<Point?>

    @Test
    fun editorAddsUpToFiveValidEnds() {
        withActivity(
            setup = {},
            prepare = {
                field("tab").set(it, "Pairs")
                field("editing").setBoolean(it, true)
                field("draftStart").set(it, star.start)
                draftEnds(it)[0] = star.ends[0]
            },
        ) { store, scenario ->
            waitFor("Single end uses the classic label") { texts(scenario).contains("Set end ✓") }
            assertFalse(texts(scenario).contains("Remove last end"))
            for (n in 2..5) {
                click("Add end")
                waitFor("End $n must be offered") { texts(scenario).contains("Set end $n") }
                assertTrue(texts(scenario).contains("Tap the map for end $n"))
                scenario.onActivity {
                    assertEquals(n, field("pointToSet").getInt(it))
                    draftEnds(it)[n - 1] = star.ends[n - 1]
                }
            }
            scenario.onActivity { MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(it) }
            waitFor("All five ends must be shown as set") { (1..5).all { texts(scenario).contains("Set end $it ✓") } }
            assertFalse(texts(scenario).contains("Add end"))
            // Saving enables automatic recording in the real app preferences, so only the draft is checked.
            scenario.onActivity {
                val draft = EndpointPair("draft", "Draft", field("draftStart").get(it) as Point, draftEnds(it).filterNotNull())
                assertEquals(star.ends, draft.ends)
                assertTrue(draft.valid())
            }
            assertTrue(store.dao.pairs().isEmpty())
        }
    }

    @Test
    fun editorRemovesLastEndAndValidatesSpacing() {
        withActivity(
            setup = {},
            prepare = {
                field("tab").set(it, "Pairs")
                field("editing").setBoolean(it, true)
                field("draftStart").set(it, fork.start)
                field("draftEnds").set(it, mutableListOf<Point?>(fork.ends[0], fork.ends[1], null))
                field("pointToSet").setInt(it, 3)
            },
        ) { store, scenario ->
            waitFor("Second end must be shown as set") { texts(scenario).contains("Set end 2 ✓") }
            click("Save pair")
            scenario.onActivity { assertTrue(field("editing").getBoolean(it)) }
            assertTrue(store.dao.pairs().isEmpty())
            click("Remove last end")
            waitFor("Third end must be removed") { !texts(scenario).contains("Set end 3") }
            scenario.onActivity {
                assertEquals(listOf(fork.ends[0], fork.ends[1]), draftEnds(it))
                assertEquals(2, field("pointToSet").getInt(it))
                draftEnds(it)[1] = Point(52.0, 19.0305)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(it)
            }
            click("Save pair")
            scenario.onActivity { assertTrue(field("editing").getBoolean(it)) }
            assertTrue(store.dao.pairs().isEmpty())
        }
    }
}
