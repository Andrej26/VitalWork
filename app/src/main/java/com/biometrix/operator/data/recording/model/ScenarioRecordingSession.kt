package com.biometrix.operator.data.recording.model

enum class DataRecordingState {
    IDLE,
    RECORDING
}

data class ScenarioMetadata(
    val scenarioId: Long,
    val scenarioIdentifier: String,
    val startTimestampMs: Long,
    val heartRateRecording: Boolean = false,
    val respirationRecording: Boolean = false,
    val gsrRecording: Boolean = false,
    val heartRateSampleCount: Int = 0,
    val respirationSampleCount: Int = 0,
    val esenseRrIntervalSampleCount: Int = 0,
    val gsrSampleCount: Int = 0
)
