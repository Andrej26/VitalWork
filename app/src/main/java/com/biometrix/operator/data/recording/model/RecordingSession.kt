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
    val fibionRecording: Boolean = false,
    val heartRateSampleCount: Int = 0,
    val respirationSampleCount: Int = 0,
    val fibionHeartRateSampleCount: Int = 0,
    val fibionEcgSampleCount: Int = 0,
    val fibionRrIntervalSampleCount: Int = 0,
    val esenseRrIntervalSampleCount: Int = 0
)
