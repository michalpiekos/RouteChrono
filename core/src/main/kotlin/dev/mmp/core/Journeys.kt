package dev.mmp.core

import java.util.UUID
import kotlin.math.*

fun newId(): String = UUID.randomUUID().toString()

data class Point(val lat: Double, val lon: Double)

data class Fix(val point: Point, val wallMs: Long, val monoMs: Long, val accuracy: Double)

data class EndpointPair(val id: String, val name: String, val start: Point, val end: Point) {
    fun valid() = validPoint(start) && validPoint(end) && distance(start, end) > 150.0
}

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

sealed interface TrackingEvent {
    data class Started(val id: String, val pair: EndpointPair, val reverse: Boolean, val fix: Fix) :
        TrackingEvent

    data class Sample(val id: String, val fix: Fix) : TrackingEvent

    data class Ended(val id: String, val fix: Fix, val complete: Boolean) : TrackingEvent
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
                s.journey?.let { events += TrackingEvent.Ended(it, last, false) }
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
            val ds = distance(fix.point, pair.start)
            val de = distance(fix.point, pair.end)
            val inside =
                when {
                    ds <= 50 -> 0
                    de <= 50 -> 1
                    else -> null
                }
            val origin = s.side
            if (s.journey != null) {
                val id = s.journey!!
                events += TrackingEvent.Sample(id, fix)
                if (inside != null) {
                    events += TrackingEvent.Ended(id, fix, inside != origin)
                    arrive(pair, s, inside, fix)
                }
            } else if (origin != null && (if (origin == 0) ds else de) > 75) {
                val id = newId()
                events += TrackingEvent.Started(id, pair, origin == 1, s.anchor!!)
                events += TrackingEvent.Sample(id, fix)
                s.journey = id
                if (inside != null && inside != origin) {
                    events += TrackingEvent.Ended(id, fix, true)
                    arrive(pair, s, inside, fix)
                }
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
