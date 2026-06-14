package com.vitalwork.app.data.sensor.watch

import com.vitalwork.app.data.time.TimeProvider
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simplified watch time-sync: `correctedTimestamp(t) = t + ntpOffsetMs()` — one NTP lift onto UTC,
 * no per-connection offset capture. (The watch already stamps true capture time on its auto-synced clock.)
 */
class WatchCorrectedTimestampTest {

    @Test
    fun correctedTimestamp_isWatchTimePlusNtpOffset() {
        val ntpAhead = 5_000L // pretend the NTP clock is 5 s ahead of the raw device clock
        val provider = TimeProvider { System.currentTimeMillis() + ntpAhead }
        val receiver = WatchSensorReceiver(provider)

        val watchTs = 1_781_449_253_402L
        val corrected = receiver.correctedTimestamp(watchTs)

        // corrected ≈ watchTs + ntpOffset; allow slack for the two currentTimeMillis reads in ntpOffsetMs.
        assertTrue(
            "corrected=$corrected expected≈${watchTs + ntpAhead}",
            corrected in (watchTs + ntpAhead - 50)..(watchTs + ntpAhead + 50)
        )
    }
}
