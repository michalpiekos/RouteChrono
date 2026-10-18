package dev.mmp.go

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingAppearanceStateTest {
    @Test fun staleRecordingOrEnabledPreferenceCannotShowLiveGlow() {
        for (manual in listOf(false, true)) for (journey in listOf(false, true)) {
            assertEquals(RecordingAppearanceState.PAUSED, recordingAppearanceState(false, false, manual, journey))
            assertEquals(RecordingAppearanceState.STOPPED, recordingAppearanceState(false, true, manual, journey))
        }
    }

    @Test fun monitoringAndAutomaticJourneyHaveDistinctStates() {
        assertEquals(RecordingAppearanceState.MONITORING, recordingAppearanceState(true, true, false, false))
        assertEquals(RecordingAppearanceState.RECORDING, recordingAppearanceState(true, true, false, true))
    }

    @Test fun manualRecordingGlowsWithoutAutomaticModeOrDatabaseRefresh() {
        assertEquals(RecordingAppearanceState.RECORDING, recordingAppearanceState(true, false, true, false))
    }
}
