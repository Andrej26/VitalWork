package com.biometrix.operator.data.sensor.watch

/**
 * Battery-level thresholds (percent) at which the tablet warns the operator that the Galaxy Watch
 * needs charging. Evaluated as a snapshot when the Home screen is shown (between sessions), never
 * during a running scenario — so the warning never injects a stimulus that could contaminate
 * reaction-time data.
 *
 * [WARNING_PCT] matches the eSense Pulse low-battery threshold so the two sensors behave
 * consistently. These are sensible starting points for the Watch 8's heavy streaming drain profile
 * (continuous HR+EDA + 1 Hz flush + foreground service) and are kept here so they can be retuned in
 * one place after a measured full-length session.
 */
object WatchBatteryThresholds {
    /** At or below this level: amber "charge as soon as possible" banner. */
    const val WARNING_PCT = 30

    /** At or below this level: red "do not start a new session without charging" banner. */
    const val CRITICAL_PCT = 16
}

/** Severity tier derived from the watch's last-known battery level. */
enum class WatchBatteryAlert { NONE, WARNING, CRITICAL }
