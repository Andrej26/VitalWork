package com.vitalwork.app.data.time

import com.lyft.kronos.KronosClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of NTP-corrected wall-clock time for everything the app **persists or exports**
 * (sensor sample timestamps, session/scenario start-end times, the export-file stamp).
 *
 * An Android app cannot set the OS system clock, so this does not change the device time: it returns
 * `System.currentTimeMillis()` plus the NTP offset that [KronosClock] computed at startup (see
 * [com.vitalwork.app.VitalWorkApplication]). Routing every persisted stamp through here puts the
 * tablet's data on a single, NTP-corrected UTC timeline so all sensor streams align without any
 * device-to-device clock handshake.
 *
 * The primary constructor takes a raw `() -> Long` so tests can inject a fixed clock; the [Inject]
 * constructor wires the real Kronos-backed source. Production code always receives the Kronos-backed
 * singleton via Hilt — call sites that default to [system] only do so in tests.
 */
@Singleton
class TimeProvider(private val source: () -> Long) {

    /** Production constructor used by Hilt. */
    @Inject
    constructor(kronos: KronosClock) : this({ kronos.getCurrentTimeMs() })

    /**
     * NTP-corrected wall-clock time in epoch milliseconds. Falls back to the device clock until the
     * first NTP sync completes (and when offline); Kronos also re-triggers a background sync on use.
     */
    fun nowMs(): Long = source()

    /**
     * The constant NTP correction to add onto a value already stamped with raw
     * `System.currentTimeMillis()`. 0 until synced. Used to lift an existing device-clock timestamp
     * (e.g. the Galaxy Watch's device-clock-aligned reading) onto the NTP timeline without recomputing
     * a device→reference offset that was captured on the raw clock.
     */
    fun ntpOffsetMs(): Long = source() - System.currentTimeMillis()

    companion object {
        /** Device-clock provider; the default for non-Hilt (test) construction. */
        fun system() = TimeProvider { System.currentTimeMillis() }
    }
}
