package com.biometrix.operator.data.sensor.watch

/**
 * Battery-level thresholds (percent) at which the tablet warns the operator that the Galaxy Watch
 * needs charging. Evaluated as a snapshot when the Home screen is shown (between sessions), never
 * during a running scenario — so the warning never injects a stimulus that could contaminate
 * reaction-time data.
 *
 * [WARNING_PCT] matches the eSense Pulse low-battery threshold so the two sensors behave
 * consistently. [CRITICAL_PCT] is set above Samsung's Power-Saving auto-trigger range because on a
 * low battery the watch enables Power Saving / "Limit health features", which **turns off background
 * sensor sampling** — verified on-device 2026-06-12: at 7% the watch left a ~99 s hole in the
 * screen-off data (lost), while at full battery it recorded gaplessly through deep Doze. So below
 * this level a screen-off session can silently lose data, which the critical banner now spells out.
 * Kept here so they can be retuned in one place after a measured full-length session.
 */
object WatchBatteryThresholds {
    /** At or below this level: amber "charge as soon as possible" banner. */
    const val WARNING_PCT = 30

    /**
     * At or below this level: red "may stop recording / lose data — charge before a new session"
     * banner. Set to 20 (not 16) to stay clear of the low-battery Power-Saving range where the watch
     * stops sampling screen-off (see class doc).
     */
    const val CRITICAL_PCT = 20
}

/** Severity tier derived from the watch's last-known battery level. */
enum class WatchBatteryAlert { NONE, WARNING, CRITICAL }
