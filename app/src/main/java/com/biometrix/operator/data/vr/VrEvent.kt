package com.biometrix.operator.data.vr

import com.biometrix.operator.data.db.ScenarioCode

/**
 * Internal representation of a VR event after the HTTP boundary has parsed and validated it.
 *
 * The Quest POSTs `{sessionId, scenarioId}` where `scenarioId` is a [ScenarioCode] enum **name**
 * (e.g. `"FALLING_PALLET"`). The route parses that name into a real [ScenarioCode] (400 on unknown)
 * and stamps [receivedAtMs] = `System.currentTimeMillis()` at the instant of arrival — this is the
 * single clock used for all timing, so no VR↔tablet clock sync is needed.
 *
 * The `scenarioCategory` is never sent by the Quest; it is derived from [ScenarioCode.category].
 */
sealed interface VrEvent {
    val sessionId: Long
    val code: ScenarioCode
    val receivedAtMs: Long

    /** A VR scenario/test began → create the scenario row and start sensor recording. */
    data class ScenarioStart(
        override val sessionId: Long,
        override val code: ScenarioCode,
        override val receivedAtMs: Long
    ) : VrEvent

    /** The critical stimulus fired inside the test (e.g. the pallet fell). */
    data class StimulusEvent(
        override val sessionId: Long,
        override val code: ScenarioCode,
        override val receivedAtMs: Long
    ) : VrEvent

    /** The user reacted to the stimulus. */
    data class Reaction(
        override val sessionId: Long,
        override val code: ScenarioCode,
        override val receivedAtMs: Long
    ) : VrEvent

    /** The VR scenario/test ended → stop sensor recording and finalize the scenario row. */
    data class ScenarioStop(
        override val sessionId: Long,
        override val code: ScenarioCode,
        override val receivedAtMs: Long
    ) : VrEvent
}

/**
 * Outcome of submitting a [VrEvent] to the receiver, mapped to an HTTP status by the route.
 *
 * For `event`/`reaction`, [Accepted] is returned **only after** the timestamp is persisted
 * (ack-after-write), so a 200 guarantees the measurement is on disk.
 */
sealed interface VrEventResult {
    /** Accepted (and, for event/reaction, persisted). [timestampMs] is the stored arrival stamp. */
    data class Accepted(val timestampMs: Long? = null) : VrEventResult

    /** Rejected for a semantic reason → HTTP 409 (not retryable by the Quest). */
    data class Rejected(val reason: String) : VrEventResult
}
