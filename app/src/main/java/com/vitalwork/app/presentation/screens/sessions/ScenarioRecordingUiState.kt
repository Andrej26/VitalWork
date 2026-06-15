package com.vitalwork.app.presentation.screens.sessions

import com.vitalwork.app.data.recording.model.DataRecordingState

data class ScenarioRecordingUiState(
    val recordingState: DataRecordingState = DataRecordingState.IDLE,
    val durationFormatted: String = "00:00:00",
    val isHeartRateConnected: Boolean = false,
    val isRespirationConnected: Boolean = false,
    val heartRateSampleCount: Int = 0,
    val respirationSampleCount: Int = 0,
    val esenseRrIntervalSampleCount: Int = 0,
    val edaSampleCount: Int = 0,
    val watchHrSampleCount: Int = 0,
    val watchIbiSampleCount: Int = 0,
    val scenarioIdentifier: String? = null,
    val isRecording: Boolean = false,
    val heartRateWasEnabled: Boolean = false,
    val respirationWasEnabled: Boolean = false
)
