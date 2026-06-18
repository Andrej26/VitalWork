package com.vitalwork.app.data.sensor.watch

/**
 * Battery-level thresholds (percent) at which the tablet warns the operator that the Galaxy Watch
 * needs charging. Evaluated as a snapshot when the Home screen is shown (between sessions), never
 * during a running scenario — so the warning never injects a stimulus that could contaminate
 * reaction-time data.
 *
 * The real risk to screen-off recording is the watch enabling Power Saving / "Limit health
 * features", which **turns off background sensor sampling** — not a fixed battery percentage.
 * Measured 2026-06-15: with Power Saving off the watch recorded gaplessly all the way down to 4%,
 * but an earlier run with Power Saving (auto-)on lost screen-off data around 7%. The thresholds are
 * therefore an operator nudge to charge before the battery gets low enough for Power Saving to kick
 * in, kept here so they can be retuned in one place.
 */
object WatchBatteryThresholds {
    /** At or below this level: amber "charge as soon as possible" banner. */
    const val WARNING_PCT = 20

    /**
     * At or below this level: red "may stop recording / lose data — charge before a new session"
     * banner. By this point the watch can enter low-battery Power Saving and stop sampling
     * screen-off (see class doc), so a new session should not be started without charging.
     */
    const val CRITICAL_PCT = 10
}

/** Severity tier derived from the watch's last-known battery level. */
enum class WatchBatteryAlert { NONE, WARNING, CRITICAL }
