package dev.mmp.core

import org.junit.Assert.*
import org.junit.Test

class RecordingMapTest {
    private val a = EndpointPair("a", "A", Point(0.0, 0.0), Point(0.0, 0.01))
    private val b = a.copy(id = "b")

    @Test
    fun selectsOnlyAnUnambiguousRecordingPair() {
        assertEquals(a, recordingPair(listOf(a), emptyList()))
        assertNull(recordingPair(listOf(a, b), emptyList()))
        assertEquals(b, recordingPair(listOf(a, b), listOf("b")))
        assertNull(recordingPair(listOf(a, b), listOf("a", "b")))
        assertNull(recordingPair(emptyList(), emptyList()))
        assertNull(recordingPair(listOf(a), listOf("removed")))
    }

    @Test
    fun approachingAnyPaddedEdgeNeedsMoreSpace() {
        assertTrue(insideRecordingViewport(200.0, 120.0, 400, 250, 36))
        for ((x, y) in listOf(36.0 to 120.0, 364.0 to 120.0, 200.0 to 36.0, 200.0 to 214.0, -1.0 to 120.0)) {
            assertFalse(insideRecordingViewport(x, y, 400, 250, 36))
        }
    }
}
