package com.vitalwork.app.data.recording

import kotlin.math.abs

/**
 * Outcome of the session-end Galaxy Watch reconciliation (see
 * [ScenarioRecordingRepository.drainAndFinalizeWatchEda]).
 *
 * It confirms the phone DB holds **exactly** the in-scenario rows the watch recorded, by treating the
 * complete durable flush as ground truth. On the authoritative-rebuild path [ok] holds by construction
 * (the DB is rebuilt from the flush), so a mismatch here means a real coding/attribution bug — worth
 * surfacing rather than silently trusting the export.
 *
 * @param claimed     rows the watch reported in its store (`FLUSH_COMPLETE` rowCount).
 * @param received    rows the phone actually buffered from the flush (transport check vs [claimed]).
 * @param inScenario  flush rows whose corrected time fell inside a scenario window (study data).
 * @param dbRows      watch-type rows persisted to the DB for the session after finalize.
 * @param clockSkewMs min observed `(arrival − captureTime)` over live readings, or null if none seen.
 */
data class WatchReconciliationReport(
    val claimed: Int,
    val received: Int,
    val inScenario: Int,
    val dbRows: Int,
    val clockSkewMs: Long?,
) {
    /** Rows recorded in between-scenario gaps — deliberately not persisted (no scenario was active). */
    val betweenScenario: Int get() = received - inScenario

    /** Every recorded row is accounted for: fully transferred, and all in-window rows are in the DB. */
    val ok: Boolean get() = received == claimed && dbRows == inScenario

    /** Watch clock looks mis-set (auto-time off) — a non-blocking warning, never a correction. */
    val clockSkewSuspect: Boolean
        get() = clockSkewMs != null && abs(clockSkewMs) > CLOCK_SKEW_WARN_MS

    /** One-line operator summary for the session-end banner / logs. */
    fun summary(): String {
        val verdict = if (ok) "OK" else "MISMATCH"
        val skew = clockSkewMs?.let { " · skew ${it}ms${if (clockSkewSuspect) " ⚠" else ""}" } ?: ""
        return "Watch: $claimed recorded · $inScenario in-scenario · $betweenScenario between · " +
            "DB $dbRows · $verdict$skew"
    }

    companion object {
        /** Beyond this |skew| the watch clock is probably wrong (auto-time off). */
        const val CLOCK_SKEW_WARN_MS = 3_000L
    }
}
