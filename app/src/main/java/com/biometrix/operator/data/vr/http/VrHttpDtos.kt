package com.biometrix.operator.data.vr.http

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the VR ↔ tablet HTTP contract. The Quest is the client; the tablet serves these.
 * JSON is configured lenient + ignore-unknown-keys so the VR side can add fields without breaking us.
 */

/**
 * Body of all four POSTs (`start`/`event`/`reaction`/`stop`). The Quest sends only these two fields:
 *  - [sessionId]: read from the tablet's UDP beacon and echoed back (the Quest never invents it).
 *  - [scenarioId]: a `ScenarioCode` enum **name** (e.g. `"FALLING_PALLET"`), parsed on the tablet.
 */
@Serializable
data class ScenarioRequest(
    val sessionId: Long,
    val scenarioId: String
)

/**
 * `201` response to `start`. The scenario row is created asynchronously by the ViewModel (which
 * owns session state), so the DB id isn't available synchronously at ack time — and the Quest
 * never needs it (it only ever echoes back the enum name + sessionId). A simple ack is enough.
 */
@Serializable
data class StartResponse(val status: String = "started")

/** `200` response to `event`: the arrival timestamp the tablet stored. */
@Serializable
data class EventResponse(val eventTimestampMs: Long)

/** `200` response to `reaction`: the arrival timestamp the tablet stored. */
@Serializable
data class ReactionResponse(val reactionTimestampMs: Long)

/** `200` response to `stop`. */
@Serializable
data class StopResponse(val status: String = "ended")

/** Error body for `409`/`400` so the VR side can log a specific reason. */
@Serializable
data class ErrorResponse(val reason: String, val value: String? = null)
