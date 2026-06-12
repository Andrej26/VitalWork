package com.vitalwork.app.data.export.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionExport(
    val version: String = "2.0.0",
    val exportedAt: String,
    val participant: ParticipantExport,
    val session: SessionInfo,
    val scenarios: List<ScenarioExport>
)

@Serializable
data class ParticipantExport(
    val participantCode: String,
    val age: Int? = null,
    val gender: String? = null
)

@Serializable
data class SessionInfo(
    val sessionCode: String,
    val startedAt: String,
    val endedAt: String? = null,
    val status: String,
    val notes: String,
    val statistics: SessionStatistics
)

@Serializable
data class SessionStatistics(
    val scenarioCount: Int,
    val hrSampleCount: Int,
    val respirationSampleCount: Int,
    val rrIntervalSampleCount: Int,
    val edaSampleCount: Int
)

@Serializable
data class ScenarioExport(
    val scenarioCode: String,
    val scenarioCategory: String,
    val startedAt: String,
    val endedAt: String? = null,
    val eventTimestampMs: Long? = null,
    val reactionTimestampMs: Long? = null,
    val reactionTimeMs: Long? = null,
    val gaps: ScenarioGaps? = null,
    val samples: List<SensorSampleExport>
)

@Serializable
data class ScenarioGaps(
    val heartRate: SensorGapInfo? = null,
    val rrInterval: SensorGapInfo? = null,
    val respiration: SensorGapInfo? = null
)

@Serializable
data class SensorGapInfo(
    val gapCount: Int,
    val gapTotalMs: Long,
    val gaps: List<GapExport>
)

@Serializable
data class GapExport(
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val gapMs: Long
)

@Serializable
data class SensorSampleExport(
    val timestampMs: Long,
    val elapsedMs: Long,
    val sensorType: String,
    val value: Float
)
