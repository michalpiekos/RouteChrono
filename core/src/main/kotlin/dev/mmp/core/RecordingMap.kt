package dev.mmp.core

fun recordingPair(pairs: List<EndpointPair>, activePairIds: List<String>): EndpointPair? {
    val active = activePairIds.distinct()
    return when {
        active.size > 1 -> null
        active.size == 1 -> pairs.firstOrNull { it.id == active.single() }
        else -> pairs.singleOrNull()
    }
}

fun insideRecordingViewport(x: Double, y: Double, width: Int, height: Int, padding: Int): Boolean =
    x.isFinite() && y.isFinite() &&
        x > padding && x < width - padding && y > padding && y < height - padding
