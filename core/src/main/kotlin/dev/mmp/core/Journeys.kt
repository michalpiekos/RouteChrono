package dev.mmp.core

import java.util.UUID
import kotlin.math.*

fun newId(): String = UUID.randomUUID().toString()

data class Point(val lat: Double, val lon: Double)

data class Fix(val point: Point, val wallMs: Long, val monoMs: Long, val accuracy: Double)

data class EndpointPair(
    val id: String,
    val name: String,
    val start: Point,
    val ends: List<Point>,
) {
    constructor(
        id: String,
        name: String,
        start: Point,
        end: Point,
        vararg more: Point,
    ) : this(id, name, start, listOf(end) + more)

    val end: Point
        get() = ends.first()

    val multiEnd: Boolean
        get() = ends.size > 1

    val points: List<Point>
        get() = listOf(start) + ends

    fun referenceDistance(origin: Int): Double =
        if (origin == 0) points.drop(1).maxOf { distance(start, it) }
        else distance(points[origin], start)

    fun beyondReach(origin: Int, p: Point) =
        distance(points[origin], p) > ABANDON_FACTOR * referenceDistance(origin)

    fun valid() =
        ends.size in 1..MAX_ENDS &&
            points.all(::validPoint) &&
            points.indices.all { a ->
                (a + 1 until points.size).all { b -> distance(points[a], points[b]) > 150.0 }
            }

    fun match(from: Point, to: Point): TripMatch? {
        val a = points.indexOfFirst { distance(it, from) <= 50 }
        val b = points.indexOfFirst { distance(it, to) <= 50 }
        if (a < 0 || b < 0 || (a == 0) == (b == 0)) return null
        return TripMatch(a != 0, if (multiEnd) maxOf(a, b) else null)
    }
}

const val MAX_ENDS = 5

data class TripMatch(val reverse: Boolean, val endpoint: Int?)

fun validPoint(p: Point) =
    p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0

fun distance(a: Point, b: Point): Double {
    val lat = Math.toRadians(b.lat - a.lat)
    val lon = Math.toRadians(b.lon - a.lon)
    val h =
        sin(lat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
    return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun usable(f: Fix) = validPoint(f.point) && f.accuracy.isFinite() && f.accuracy in 0.0..35.0

const val GAP_MS = 30_000L

const val ABANDON_FACTOR = 1.5

sealed interface TrackingEvent {
    data class Started(
        val id: String,
        val pair: EndpointPair,
        val reverse: Boolean,
        val fix: Fix,
        val endpoint: Int? = null,
    ) : TrackingEvent

    data class Sample(val id: String, val fix: Fix) : TrackingEvent

    data class Abandoned(val id: String, val pair: EndpointPair, val fix: Fix) : TrackingEvent

    data class Ended(
        val id: String,
        val fix: Fix,
        val complete: Boolean,
        val endpoint: Int? = null,
        val reason: DropReason? = null,
    ) : TrackingEvent
}

enum class DropReason {
    ABANDONED,
    GPS_GAP,
    GPS_JUMP,
    OTHER_END,
    RETURNED,
    PAUSED,
    PAIRS_CHANGED,
    MANUAL_STARTED,
    EXITED,
    STOPPED,
    RESTARTED,
}

const val ENDPOINT_COOLDOWN_MS = 60_000L

data class EndpointCooldown(val pairId: String, val side: Int, val arrivedMonoMs: Long)

class EndpointTracker(
    private val pairs: List<EndpointPair>,
    cooldowns: List<EndpointCooldown> = emptyList(),
) {
    private val cooldownByEndpoint = cooldowns.associateBy { it.pairId to it.side }.toMutableMap()
    val cooldowns: List<EndpointCooldown>
        get() = cooldownByEndpoint.values.toList()

    fun clearCooldowns() = cooldownByEndpoint.clear()

    private fun cooling(pairId: String, side: Int, monoMs: Long): Boolean {
        val arrival = cooldownByEndpoint[pairId to side]?.arrivedMonoMs ?: return false
        return monoMs < arrival + ENDPOINT_COOLDOWN_MS
    }

    private fun arrive(pair: EndpointPair, state: State, side: Int, fix: Fix) {
        cooldownByEndpoint[pair.id to side] = EndpointCooldown(pair.id, side, fix.monoMs)
        state.journey = null
        state.side = null
        state.anchor = null
    }

    private fun reach(
        pair: EndpointPair,
        state: State,
        inside: Int,
        fix: Fix,
        events: MutableList<TrackingEvent>,
    ) {
        val origin = state.side!!
        val complete = if (origin == 0) inside != 0 else inside == 0
        val end = if (origin == 0) inside.takeIf { complete } else origin
        val reason =
            when {
                complete -> null
                inside == origin -> DropReason.RETURNED
                else -> DropReason.OTHER_END
            }
        events += TrackingEvent.Ended(state.journey!!, fix, complete, end.takeIf { pair.multiEnd }, reason)
        if (origin != 0 && inside != 0 && inside != origin) {
            // Travel between ends is never measured; the reached end becomes the next origin.
            state.journey = null
            state.side = inside
            state.anchor = fix
        } else arrive(pair, state, inside, fix)
    }

    private data class State(
        var side: Int? = null,
        var anchor: Fix? = null,
        var journey: String? = null,
    )

    private val states = pairs.associate { it.id to State() }
    private var previous: Fix? = null
    val activeCount
        get() = states.values.count { it.journey != null }

    fun feed(fix: Fix): List<TrackingEvent> {
        if (!usable(fix)) return emptyList()
        val last = previous
        if (last != null && fix.monoMs <= last.monoMs) return emptyList()
        val events = mutableListOf<TrackingEvent>()
        val jump =
            last != null &&
                distance(last.point, fix.point) / ((fix.monoMs - last.monoMs) / 1000.0) > 80
        if (last != null && (fix.monoMs - last.monoMs > GAP_MS || jump)) {
            states.values.forEach { s ->
                val reason = if (jump) DropReason.GPS_JUMP else DropReason.GPS_GAP
                s.journey?.let { events += TrackingEvent.Ended(it, last, false, reason = reason) }
                s.side = null
                s.anchor = null
                s.journey = null
            }
        }
        previous = fix
        if (jump) return events
        for (pair in pairs) {
            if (!pair.valid()) continue
            val s = states.getValue(pair.id)
            val inside = pair.points.indexOfFirst { distance(fix.point, it) <= 50 }.takeIf { it >= 0 }
            val origin = s.side
            if (s.journey != null && inside == null && pair.beyondReach(origin!!, fix.point)) {
                events += TrackingEvent.Abandoned(s.journey!!, pair, fix)
                s.journey = null
                s.side = null
                s.anchor = null
            } else if (s.journey != null) {
                events += TrackingEvent.Sample(s.journey!!, fix)
                if (inside != null) reach(pair, s, inside, fix, events)
            } else if (origin != null && inside == null && pair.beyondReach(origin, fix.point)) {
                s.side = null
                s.anchor = null
            } else if (origin != null && distance(fix.point, pair.points[origin]) > 75) {
                val id = newId()
                val end = origin.takeIf { it != 0 && pair.multiEnd }
                events += TrackingEvent.Started(id, pair, origin != 0, s.anchor!!, end)
                events += TrackingEvent.Sample(id, fix)
                s.journey = id
                if (inside != null && inside != origin) reach(pair, s, inside, fix, events)
            } else if (inside != null && !cooling(pair.id, inside, fix.monoMs)) {
                s.side = inside
                s.anchor = fix
            }
        }
        return events
    }
}

fun segments(fixes: List<Fix>): List<List<Fix>> {
    val result = mutableListOf<MutableList<Fix>>()
    var prior: Fix? = null
    for (fix in fixes) {
        if (!usable(fix)) {
            prior = null
            continue
        }
        val last = prior
        if (last != null && fix.monoMs <= last.monoMs) continue
        if (
            last == null ||
                fix.monoMs - last.monoMs > GAP_MS ||
                distance(last.point, fix.point) / ((fix.monoMs - last.monoMs) / 1000.0) > 80
        ) {
            result += mutableListOf<Fix>()
        }
        result.last() += fix
        prior = fix
    }
    return result
}

fun routeDistance(fixes: List<Fix>): Double =
    segments(fixes).sumOf { segment ->
        var anchor = segment.first()
        var total = 0.0
        segment.drop(1).forEach { f ->
            val step = distance(anchor.point, f.point)
            if (step >= max(3.0, min(anchor.accuracy, f.accuracy) * 0.5)) {
                total += step
                anchor = f
            }
        }
        total
    }

data class TimeSummary(val count: Int, val fastest: Long, val average: Long, val slowest: Long)

fun summarize(times: List<Long>): TimeSummary? =
    times
        .filter { it > 0 }
        .let { valid ->
            if (valid.isEmpty()) null
            else TimeSummary(valid.size, valid.min(), valid.average().toLong(), valid.max())
        }
