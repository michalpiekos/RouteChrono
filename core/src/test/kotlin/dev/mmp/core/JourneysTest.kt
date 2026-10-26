package dev.mmp.core

import org.junit.Assert.*
import org.junit.Test

class JourneysTest {
    private val pair = EndpointPair("p", "Home – work", Point(0.0, 0.0), Point(0.0, 0.01))

    private fun f(x: Double, t: Long, y: Double = 0.0, accuracy: Double = 5.0) =
        Fix(Point(y, x), 100000 + t, t, accuracy)

    private fun run(
        points: List<Fix>,
        pairs: List<EndpointPair> = listOf(pair),
    ): List<TrackingEvent> {
        val engine = EndpointTracker(pairs)
        return points.flatMap(engine::feed)
    }

    @Test
    fun recordsForwardAndReturnIndependently() {
        val events =
            run(
                listOf(
                    f(0.0, 0),
                    f(0.001, 10000),
                    f(0.005, 20000),
                    f(0.01, 30000),
                    f(0.01, 50000),
                    f(0.01, 70000),
                    f(0.01, 90000),
                    f(0.009, 100000),
                    f(0.005, 110000),
                    f(0.0, 120000),
                )
            )
        assertEquals(
            listOf(false, true),
            events.filterIsInstance<TrackingEvent.Started>().map { it.reverse },
        )
        assertEquals(2, events.filterIsInstance<TrackingEvent.Ended>().count { it.complete })
    }

    @Test
    fun differentPhysicalRoutesBothCount() {
        for (offset in listOf(0.003, -0.003)) {
            val events =
                run(listOf(f(0.0, 0), f(0.001, 10000), f(0.005, 20000, offset), f(0.01, 30000)))
            assertTrue(events.filterIsInstance<TrackingEvent.Ended>().single().complete)
            assertTrue(
                events.filterIsInstance<TrackingEvent.Sample>().any { it.fix.point.lat == offset }
            )
        }
    }

    @Test
    fun stationaryNoiseDoesNotCreateTrips() {
        val events = run((0..40).map { f(if (it % 2 == 0) 0.0002 else -0.0002, it * 1000L) })
        assertTrue(events.isEmpty())
    }

    @Test
    fun dwellTimeAtOriginIsExcluded() {
        val events =
            run(listOf(f(0.0, 0), f(0.0, 10000), f(0.0, 20000), f(0.001, 25000), f(0.01, 40000)))
        assertEquals(20000L, events.filterIsInstance<TrackingEvent.Started>().single().fix.monoMs)
    }

    @Test
    fun gapInterruptsAndDoesNotInventArrival() {
        val events = run(listOf(f(0.0, 0), f(0.001, 10000), f(0.01, 80000)))
        assertFalse(events.filterIsInstance<TrackingEvent.Ended>().single().complete)
    }

    @Test
    fun midJourneyStartupDoesNotInventOrigin() {
        val events = run(listOf(f(0.005, 0), f(0.008, 10000), f(0.01, 20000)))
        assertTrue(events.isEmpty())
    }

    @Test
    fun overlappingPairsRecordIndependently() {
        val second = pair.copy(id = "p2", start = Point(0.0, 0.005))
        val events =
            run(
                listOf(
                    f(0.0, 0),
                    f(0.001, 10000),
                    f(0.005, 20000),
                    f(0.006, 30000),
                    f(0.01, 40000),
                ),
                listOf(pair, second),
            )
        assertEquals(2, events.filterIsInstance<TrackingEvent.Ended>().count { it.complete })
    }

    @Test
    fun returnToOriginIsIncompleteAndNextTripCanStart() {
        val events =
            run(listOf(f(0.0, 0), f(0.001, 10000), f(0.0, 20000), f(0.0, 40000), f(0.0, 60000), f(0.0, 80000), f(0.001, 90000), f(0.01, 110000)))
        assertEquals(
            listOf(false, true),
            events.filterIsInstance<TrackingEvent.Ended>().map { it.complete },
        )
    }

    @Test
    fun poorAccuracyAndOldPointsCannotEndTrip() {
        val events =
            run(
                listOf(
                    f(0.0, 0),
                    f(0.001, 10000),
                    f(0.01, 9000),
                    f(0.01, 15000, accuracy = 100.0),
                    f(0.005, 20000),
                    f(0.01, 30000),
                )
            )
        assertEquals(30000L, events.filterIsInstance<TrackingEvent.Ended>().single().fix.monoMs)
    }

    @Test
    fun longStationaryPauseDuringTripDoesNotStopIt() {
        val points =
            listOf(f(0.0, 0), f(0.001, 10000)) +
                (2..400).map { f(0.005, it * 10000L) } +
                f(0.01, 4010000)
        assertTrue(run(points).filterIsInstance<TrackingEvent.Ended>().single().complete)
    }

    @Test
    fun traceGapsAndTeleportsAreNotDrawnOrCounted() {
        val points =
            listOf(f(0.0, 0), f(0.001, 10000), f(0.1, 100000), f(0.101, 110000), f(1.0, 111000))
        assertEquals(3, segments(points).size)
        assertEquals(222.39, routeDistance(points), 1.0)
    }

    @Test
    fun impossibleJumpCannotCompleteATrip() {
        val events = run(listOf(f(0.0, 0), f(0.001, 10000), f(0.01, 11000)))
        assertFalse(events.filterIsInstance<TrackingEvent.Ended>().single().complete)
    }

    @Test
    fun summariesAndEndpointValidation() {
        assertFalse(pair.copy(end = Point(0.0, 0.0001)).valid())
        assertFalse(pair.copy(end = Point(Double.NaN, 0.0)).valid())
        assertEquals(TimeSummary(3, 10, 20, 30), summarize(listOf(10, 20, 30)))
        assertNull(summarize(emptyList()))
    }
    @Test
    fun arrivalCooldownBlocksImmediateReturnInBothDirections() {
        for (reverse in listOf(false, true)) {
            val engine = EndpointTracker(listOf(pair))
            fun fix(x: Double, time: Long) = f(if (reverse) 0.01 - x else x, time)
            val events = listOf(fix(0.0, 0), fix(0.001, 10000), fix(0.005, 20000), fix(0.01, 30000),
                fix(0.0098, 40000), fix(0.009, 50000), fix(0.005, 70000), fix(0.001, 90000))
                .flatMap(engine::feed)
            assertEquals(1, events.filterIsInstance<TrackingEvent.Started>().size)
            assertEquals(1, events.filterIsInstance<TrackingEvent.Ended>().count { it.complete })
            assertEquals(0, engine.activeCount)
        }
    }

    @Test
    fun cooldownBoundaryRequiresFreshEndpointFixAndDoesNotBackdateDeparture() {
        val engine = EndpointTracker(listOf(pair), listOf(EndpointCooldown(pair.id, 1, 30000)))
        assertTrue(engine.feed(f(0.01, 89999)).isEmpty())
        assertTrue(engine.feed(f(0.009, 94999)).isEmpty())
        assertTrue(engine.feed(f(0.01, 100000)).isEmpty())
        val start = engine.feed(f(0.009, 105000)).filterIsInstance<TrackingEvent.Started>().single()
        assertEquals(100000L, start.fix.monoMs)
        val exact = EndpointTracker(listOf(pair), listOf(EndpointCooldown(pair.id, 1, 30000)))
        assertTrue(exact.feed(f(0.01, 90000)).isEmpty())
        assertEquals(1, exact.feed(f(0.009, 95000)).filterIsInstance<TrackingEvent.Started>().size)
    }

    @Test
    fun cooldownSurvivesTrackerRecreationAndGpsGap() {
        val original = EndpointTracker(listOf(pair))
        listOf(f(0.0, 0), f(0.001, 10000), f(0.005, 20000), f(0.01, 30000)).forEach(original::feed)
        val restored = EndpointTracker(listOf(pair), original.cooldowns)
        assertTrue(restored.feed(f(0.01, 70000)).isEmpty())
        assertTrue(restored.feed(f(0.009, 75000)).isEmpty())
        assertTrue(original.feed(f(0.01, 80000)).isEmpty())
        assertTrue(original.feed(f(0.009, 85000)).isEmpty())
        assertTrue(restored.feed(f(0.01, 90000).copy(wallMs = -500000)).isEmpty())
        assertEquals(1, restored.feed(f(0.009, 95000)).filterIsInstance<TrackingEvent.Started>().size)
    }

    @Test
    fun cooldownDoesNotPauseOtherPairOrOppositeEndpoint() {
        val other = pair.copy(id = "other")
        val engine = EndpointTracker(listOf(pair, other), listOf(EndpointCooldown(pair.id, 1, 30000)))
        engine.feed(f(0.01, 40000))
        val events = engine.feed(f(0.009, 50000))
        assertEquals(other.id, events.filterIsInstance<TrackingEvent.Started>().single().pair.id)
        val opposite = EndpointTracker(listOf(pair), listOf(EndpointCooldown(pair.id, 1, 30000)))
        opposite.feed(f(0.0, 40000))
        assertEquals(1, opposite.feed(f(0.001, 50000)).filterIsInstance<TrackingEvent.Started>().size)
    }

    @Test
    fun incompleteArrivalAlsoCoolsDown() {
        val events = run(listOf(f(0.0, 0), f(0.001, 10000), f(0.0, 20000), f(0.001, 30000), f(0.0, 40000), f(0.001, 50000)))
        assertEquals(1, events.filterIsInstance<TrackingEvent.Started>().size)
        assertFalse(events.filterIsInstance<TrackingEvent.Ended>().single().complete)
    }

    @Test
    fun directEndpointHopAlsoCoolsDown() {
        val engine = EndpointTracker(listOf(pair))
        engine.feed(f(0.0, 0))
        val events = engine.feed(f(0.01, 20000))
        assertTrue(events.filterIsInstance<TrackingEvent.Ended>().single().complete)
        assertTrue(engine.feed(f(0.009, 30000)).isEmpty())
    }

    private val fork = pair.copy(end2 = Point(0.01, 0.0))
    private val line = pair.copy(end2 = Point(0.0, 0.02))

    private fun ended(events: List<TrackingEvent>) =
        events.filterIsInstance<TrackingEvent.Ended>().map { it.complete to it.endpoint }

    private fun started(events: List<TrackingEvent>) =
        events.filterIsInstance<TrackingEvent.Started>().map { it.reverse to it.endpoint }

    @Test
    fun twoPointPairsReportNoEndpoint() {
        val events = run(listOf(f(0.0, 0), f(0.001, 10000), f(0.01, 30000)))
        assertEquals(listOf(false to null), started(events))
        assertEquals(listOf(true to null), ended(events))
    }

    @Test
    fun startTripFinishesAtEitherEnd() {
        val toFirst = run(listOf(f(0.0, 0), f(0.001, 10000), f(0.005, 20000), f(0.01, 30000)), listOf(fork))
        assertEquals(listOf(false to null), started(toFirst))
        assertEquals(listOf(true to 1), ended(toFirst))
        val toSecond =
            run(listOf(f(0.0, 0), f(0.0, 10000, 0.001), f(0.0, 20000, 0.005), f(0.0, 30000, 0.01)), listOf(fork))
        assertEquals(listOf(true to 2), ended(toSecond))
    }

    @Test
    fun startTripFinishesAtFirstEndReached() {
        val events =
            run(listOf(f(0.0, 0), f(0.001, 10000), f(0.005, 20000), f(0.01, 30000), f(0.015, 40000), f(0.02, 50000)), listOf(line))
        assertEquals(listOf(true to 1), ended(events))
        assertEquals(1, events.filterIsInstance<TrackingEvent.Started>().size)
    }

    @Test
    fun eitherEndReturnsToStart() {
        val fromFirst = run(listOf(f(0.01, 0), f(0.009, 10000), f(0.005, 20000), f(0.0, 30000)), listOf(fork))
        assertEquals(listOf(true to 1), started(fromFirst))
        assertEquals(listOf(true to 1), ended(fromFirst))
        val fromSecond =
            run(listOf(f(0.0, 0, 0.01), f(0.0, 10000, 0.009), f(0.0, 20000, 0.005), f(0.0, 30000)), listOf(fork))
        assertEquals(listOf(true to 2), started(fromSecond))
        assertEquals(listOf(true to 2), ended(fromSecond))
    }

    @Test
    fun reachingOtherEndInterruptsAndStartsReturnFromIt() {
        val events =
            run(
                listOf(
                    f(0.02, 0),
                    f(0.019, 10000),
                    f(0.015, 20000),
                    f(0.01, 30000),
                    f(0.009, 40000),
                    f(0.005, 50000),
                    f(0.0, 60000),
                ),
                listOf(line),
            )
        assertEquals(listOf(true to 2, true to 1), started(events))
        assertEquals(listOf(false to 2, true to 1), ended(events))
        assertEquals(30000L, events.filterIsInstance<TrackingEvent.Started>().last().fix.monoMs)
    }

    @Test
    fun endToEndHopIsNeverComplete() {
        val engine = EndpointTracker(listOf(fork))
        engine.feed(f(0.01, 0))
        assertEquals(listOf(false to 1), ended(engine.feed(f(0.0, 20000, 0.01))))
        assertEquals(0, engine.activeCount)
    }

    @Test
    fun threePointValidationAndMatching() {
        assertTrue(fork.valid())
        assertFalse(fork.copy(end2 = Point(0.0, 0.0101)).valid())
        assertFalse(fork.copy(end2 = Point(0.0005, 0.0)).valid())
        assertFalse(fork.copy(end2 = Point(Double.NaN, 0.0)).valid())
        assertEquals(TripMatch(false, 2), fork.match(fork.start, fork.end2!!))
        assertEquals(TripMatch(true, 1), fork.match(fork.end, fork.start))
        assertNull(fork.match(fork.end, fork.end2!!))
        assertNull(fork.match(fork.start, fork.start))
        assertEquals(TripMatch(true, null), pair.match(pair.end, pair.start))
    }
}
