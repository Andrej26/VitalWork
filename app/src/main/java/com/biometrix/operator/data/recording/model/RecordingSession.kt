package com.biometrix.operator.data.recording.model

enum class DataRecordingState {
    IDLE,
    RECORDING
}

data class RecordingMetadata(
    val recordingId: Long,
    val recordingIdentifier: String,
    val startTimestampMs: Long,
    val heartRateRecording: Boolean = false,
    val respirationRecording: Boolean = false,
    val heartRateSampleCount: Int = 0,
    val respirationSampleCount: Int = 0,
    val esenseRrIntervalSampleCount: Int = 0
)
