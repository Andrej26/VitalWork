package com.vitalwork.app.data.recording.model

enum class DataRecordingState {
    IDLE,
    RECORDING
}

data class ScenarioMetadata(
    val scenarioId: Long,
    val scenarioIdentifier: String,
    val startTimestampMs: Long,
    /**
     * Monotonic [android.os.SystemClock.elapsedRealtime] captured at the same instant as
     * [startTimestampMs]. Used as the single shared origin for the on-screen recording-duration
     * timer and the auto-return countdown so they start/tick/end together, independent of any
     * wall-clock (NTP) adjustment. 0 only on the default (no recording in progress).
     */
    val startElapsedRealtimeMs: Long = 0L,
    val heartRateRecording: Boolean = false,
    val respirationRecording: Boolean = false,
    val edaRecording: Boolean = false,
    val heartRateSampleCount: Int = 0,
    val respirationSampleCount: Int = 0,
    val esenseRrIntervalSampleCount: Int = 0,
    val edaSampleCount: Int = 0,
    val watchHrSampleCount: Int = 0,
    val watchIbiSampleCount: Int = 0
)
