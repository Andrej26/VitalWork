package com.biometrix.operator.data.vr.http

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the VR ↔ tablet HTTP contract. The Quest is the client; the tablet serves these.
 * JSON is configured lenient + ignore-unknown-keys so the VR side can add fields without breaking us.
 */

/**
 * Body of all scenario POSTs (`start`/`stop`). The Quest sends:
 *  - [scenarioId]: a `ScenarioCode` enum **name** (e.g. `"FALLING_PALLET"`), parsed on the tablet.
 *  - [eventTimestampMs] / [reactionTimestampMs]: only sent with `stop`, as Unix epoch milliseconds in
 *    true UTC stamped on the Quest's own NTP-synced clock. Both apps sync to NTP at startup, so these
 *    align with the tablet's NTP-corrected sample timestamps without any clock handshake. `null` for
 *    `start`, and `reactionTimestampMs` stays `null` if no reaction was recorded.
 *
 * The Quest does **not** send a session id: scenarios are attached to the tablet's own active
 * session, and identity is established by the pairing bond (`X-Vr-Quest-Id` header + source IP),
 * not by anything in this body. `ignoreUnknownKeys = true` means an extra `sessionId` field from an
 * older Quest build is simply ignored rather than rejected.
 */
@Serializable
data class ScenarioRequest(
    val scenarioId: String,
    val eventTimestampMs: Long? = null,
    val reactionTimestampMs: Long? = null
)

/**
 * `201` response to `start`. The scenario row is created asynchronously by the ViewModel (which
 * owns session state), so the DB id isn't available synchronously at ack time — and the Quest
 * never needs it (it only ever echoes back the enum name + sessionId). A simple ack is enough.
 */
@Serializable
data class StartResponse(val status: String = "started")

/** `200` response to `stop`. */
@Serializable
data class StopResponse(val status: String = "ended")

/** `200` response to `heartbeat`: a simple ack so the Quest knows the bond is still honored. */
@Serializable
data class HeartbeatResponse(val status: String = "alive")

/** Error body for `409`/`400` so the VR side can log a specific reason. */
@Serializable
data class ErrorResponse(val reason: String, val value: String? = null)
