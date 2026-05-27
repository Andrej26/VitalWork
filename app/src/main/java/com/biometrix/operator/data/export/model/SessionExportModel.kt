package com.biometrix.operator.data.export.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TestExport(
    val version: String = "1.0.0",
    val exportedAt: String,
    val test: TestData
)

@Serializable
data class TestData(
    val id: String,
    val sessionNumber: String,
    val createdAt: String,
    val endedAt: String?,
    val durationMs: Long,
    val status: String,
    val notes: String,
    val statistics: TestStatistics,
    val recordings: List<RecordingData>,
    val sudsEvents: List<SudsEventExport> = emptyList()
)

@Serializable
data class TestStatistics(
    val recordingCount: Int,
    @SerialName("esensePulse_totalSamples") val totalHeartRateSamples: Int,
    @SerialName("esenseResp_totalSamples") val totalRespirationSamples: Int,
    val totalSudsEvents: Int,
    @SerialName("esensePulse_totalRrIntervalSamples") val totalEsenseRrIntervalSamples: Int = 0
)

@Serializable
data class SudsEventExport(
    @SerialName("t") val timestampMs: Long,
    @SerialName("v") val value: Int
)

@Serializable
data class RecordingData(
    val id: String,
    val sequence: Int,
    val startedAt: String,
    val endedAt: String?,
    val durationMs: Long,
    val sensors: SensorData,
    val recordingGaps: RecordingGaps? = null,
    val data: List<SensorSample>
)

@Serializable
data class SensorData(
    @SerialName("esensePulse_heartRate") val heartRate: SensorInfo?,
    @SerialName("esensePulse_rrInterval") val esenseRrInterval: SensorInfo? = null,
    @SerialName("esenseResp_respiration") val respiration: SensorInfo?
)

@Serializable
data class SensorInfo(
    val enabled: Boolean,
    val sampleCount: Int
)

@Serializable
data class SensorGapInfo(
    val gapCount: Int,
    val gapTotalMs: Long,
    val gaps: List<GapExport>
)

@Serializable
data class RecordingGaps(
    @SerialName("esensePulse_heartRate") val esensePulseHeartRate: SensorGapInfo? = null,
    @SerialName("esensePulse_rrInterval") val esensePulseRrInterval: SensorGapInfo? = null,
    @SerialName("esenseResp_respiration") val esenseRespRespiration: SensorGapInfo? = null
)

@Serializable
data class GapExport(
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val gapMs: Long
)

@Serializable
data class SensorSample(
    @SerialName("t")
    val timestampMs: Long,
    @SerialName("e")
    val elapsedMs: Long,
    @SerialName("v")
    val value: Float,
    @SerialName("s")
    val sensorType: String
)
