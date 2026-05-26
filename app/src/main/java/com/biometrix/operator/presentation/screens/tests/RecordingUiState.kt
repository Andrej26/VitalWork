package com.biometrix.operator.presentation.screens.tests

import com.biometrix.operator.data.recording.model.DataRecordingState

data class RecordingUiState(
    val recordingState: DataRecordingState = DataRecordingState.IDLE,
    val durationFormatted: String = "00:00:00",
    val isHeartRateConnected: Boolean = false,
    val isRespirationConnected: Boolean = false,
    val heartRateSampleCount: Int = 0,
    val respirationSampleCount: Int = 0,
    val esenseRrIntervalSampleCount: Int = 0,
    val recordingIdentifier: String? = null,
    val isRecording: Boolean = false,
    val heartRateWasEnabled: Boolean = false,
    val respirationWasEnabled: Boolean = false
)
