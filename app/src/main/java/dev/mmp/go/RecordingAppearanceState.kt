package dev.mmp.go

internal enum class RecordingAppearanceState { PAUSED, STOPPED, MONITORING, RECORDING }

internal fun recordingAppearanceState(
    running: Boolean,
    enabled: Boolean,
    manual: Boolean,
    activeJourney: Boolean,
): RecordingAppearanceState =
    if (!running) {
        if (enabled) RecordingAppearanceState.STOPPED else RecordingAppearanceState.PAUSED
    } else if (manual || activeJourney) RecordingAppearanceState.RECORDING
    else RecordingAppearanceState.MONITORING
