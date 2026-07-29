package com.vitalwork.app.data.export.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionExport(
    // 2.2.0: added ScenarioExport.respirationIssues (purely additive; every other field unchanged).
    val version: String = "2.2.0",
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
    val statistics: SessionStatistics
)

@Serializable
data class SessionStatistics(
    val scenarioCount: Int,
    val hrSampleCount: Int,
    val respirationSampleCount: Int,
    val rrIntervalSampleCount: Int,
    val edaSampleCount: Int,
    val watchHrSampleCount: Int,
    val watchIbiSampleCount: Int
)

@Serializable
data class ScenarioExport(
    val scenarioCode: String,
    val startedAt: String,
    val endedAt: String? = null,
    val gaps: ScenarioGaps? = null,
    val respirationIssues: RespirationIssues? = null,
    val samples: List<SensorSampleExport>
)

/**
 * Stretches where the respiration recording is unusable even though samples kept arriving — a strap
 * that slips while the cable stays plugged in produces no [ScenarioGaps] at all.
 *
 * `null` when there is nothing to report, including when the scenario has no respiration samples
 * (the sensor was simply not connected — that is not a lost signal).
 */
@Serializable
data class RespirationIssues(
    val signalLostCount: Int,
    val signalLostTotalMs: Long,
    val noBreathingCount: Int,
    val noBreathingTotalMs: Long,
    val events: List<RespirationIssueExport>
)

/**
 * Times are `elapsedMs` from the scenario start, the same unit [GapExport] uses — one convention for
 * the whole file. Absolute time is one subtraction away (`timestampMs - elapsedMs` from any sample).
 */
@Serializable
data class RespirationIssueExport(
    val reason: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val durationMs: Long
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
