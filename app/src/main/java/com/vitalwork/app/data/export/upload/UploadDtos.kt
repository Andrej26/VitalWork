package com.vitalwork.app.data.export.upload

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the VitalWork server's full-session upload endpoint
 * (`POST /api/sessions/upload`, see `test/VitalWork_API_Service_Documentation.docx` §4 & §6).
 *
 * These are deliberately **separate** from the local-export models in
 * `data.export.model.SessionExportModel`: the server accepts epoch-millisecond values for the
 * `startedAt`/`endedAt`/`timestampMs` fields and enum-NAME sensor types (e.g. `ESENSE_RR_INTERVAL`),
 * whereas the local export uses ISO date strings and lowercase labels (e.g. `rr_interval`).
 *
 * The server accepts camelCase keys (it normalizes to snake_case internally).
 */
@Serializable
data class SessionUploadRequest(
    val participant: ParticipantUpload,
    val session: SessionUpload,
    val scenarios: List<ScenarioUpload>
)

@Serializable
data class ParticipantUpload(
    val participantCode: String,
    val age: Int? = null,
    val gender: String? = null
)

@Serializable
data class SessionUpload(
    val sessionCode: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String,
    val statistics: SessionStatisticsUpload
)

/**
 * Per-session aggregate sample counters (doc §4.2). Copied straight off [SessionEntity]'s stored
 * counters — the same source the local export uses — so the server's count columns match the export
 * instead of defaulting to 0.
 */
@Serializable
data class SessionStatisticsUpload(
    val scenarioCount: Int,
    val hrSampleCount: Int,
    val respirationSampleCount: Int,
    val rrIntervalSampleCount: Int,
    val edaSampleCount: Int,
    val watchHrSampleCount: Int,
    val watchIbiSampleCount: Int
)

@Serializable
data class ScenarioUpload(
    val scenarioCode: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val samples: List<SampleUpload>
)

@Serializable
data class SampleUpload(
    val sensorType: String,
    val timestampMs: Long,
    val elapsedMs: Long,
    val value: Float
)

/**
 * Minimal parse of the 201 response body (doc §4). Only the human-readable [message] is surfaced;
 * `ignoreUnknownKeys` lets the rest of the `data` block be ignored.
 */
@Serializable
data class SessionUploadResponse(
    val message: String = ""
)
