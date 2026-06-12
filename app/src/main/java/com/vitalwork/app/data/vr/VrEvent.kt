package com.vitalwork.app.data.vr

import com.vitalwork.app.data.db.ScenarioCode

/**
 * Internal representation of a VR event after the HTTP boundary has parsed and validated it.
 *
 * The Quest POSTs `{scenarioId, ...}` where `scenarioId` is a [ScenarioCode] enum
 * **name** (e.g. `"FALLING_PALLET"`). The route parses that name into a real [ScenarioCode] (400
 * on unknown) and stamps [receivedAtMs] = `System.currentTimeMillis()` at the instant of arrival.
 * The scenario is attached to the tablet's own active session (the VM owns that), so the Quest
 * does not send a session id.
 *
 * `eventTimestampMs`/`reactionTimestampMs` (carried by [ScenarioStop]) are Unix epoch ms in true UTC,
 * stamped on the Quest's own NTP-synced clock (both apps sync to NTP at startup) — they are *not*
 * derived from [receivedAtMs].
 *
 * The `scenarioCategory` is never sent by the Quest; it is derived from [ScenarioCode.category].
 */
sealed interface VrEvent {
    val code: ScenarioCode
    val receivedAtMs: Long

    /** A VR scenario/test began → create the scenario row and start sensor recording. */
    data class ScenarioStart(
        override val code: ScenarioCode,
        override val receivedAtMs: Long
    ) : VrEvent

    /**
     * The VR scenario/test ended → stop sensor recording and finalize the scenario row.
     *
     * @param eventTimestampMs when the critical stimulus fired (e.g. the pallet fell), on the
     *   Quest's clock, offset-corrected onto the tablet's clock. `null` if not provided.
     * @param reactionTimestampMs when the user reacted, offset-corrected the same way. `null` if
     *   no reaction was recorded.
     */
    data class ScenarioStop(
        override val code: ScenarioCode,
        override val receivedAtMs: Long,
        val eventTimestampMs: Long? = null,
        val reactionTimestampMs: Long? = null
    ) : VrEvent
}

/**
 * Outcome of submitting a [VrEvent] to the receiver, mapped to an HTTP status by the route.
 *
 * For `stop`, [Accepted] is returned **only after** any provided timestamps are persisted
 * (ack-after-write), so a 200 guarantees the measurements are on disk.
 */
sealed interface VrEventResult {
    /** Accepted (and, for stop, any timestamps persisted). [timestampMs] is unused. */
    data class Accepted(val timestampMs: Long? = null) : VrEventResult

    /** Rejected for a semantic reason → HTTP 409 (not retryable by the Quest). */
    data class Rejected(val reason: String) : VrEventResult
}
