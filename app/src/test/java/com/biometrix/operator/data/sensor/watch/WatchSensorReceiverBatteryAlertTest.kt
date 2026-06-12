package com.biometrix.operator.data.sensor.watch

import com.biometrix.operator.data.sensor.watch.model.WatchReading
import com.biometrix.operator.data.time.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [WatchSensorReceiver.currentBatteryAlert] — the snapshot the Home screen reads on resume.
 * Drives the receiver through its public message entry points exactly as [WatchListenerService]
 * would, then asserts the derived tier. Boundary semantics are `<=` (matches eSense Pulse).
 */
class WatchSensorReceiverBatteryAlertTest {

    private fun receiverWithBattery(level: Int): WatchSensorReceiver =
        WatchSensorReceiver(TimeProvider.system()).apply {
            onReading(WatchReading(type = "BATTERY", value = level.toFloat(), accuracy = 0, timestampMs = 0L))
        }

    @Test
    fun noReadingYet_isNone() {
        assertEquals(WatchBatteryAlert.NONE, WatchSensorReceiver(TimeProvider.system()).currentBatteryAlert())
    }

    @Test
    fun aboveWarning_isNone() {
        assertEquals(WatchBatteryAlert.NONE, receiverWithBattery(31).currentBatteryAlert())
    }

    @Test
    fun exactlyWarningThreshold_isWarning() {
        // 30% boundary: <= 30 → WARNING.
        assertEquals(WatchBatteryAlert.WARNING, receiverWithBattery(30).currentBatteryAlert())
    }

    @Test
    fun midWarningBand_isWarning() {
        assertEquals(WatchBatteryAlert.WARNING, receiverWithBattery(25).currentBatteryAlert())
    }

    @Test
    fun justAboveCritical_isWarning() {
        assertEquals(WatchBatteryAlert.WARNING, receiverWithBattery(21).currentBatteryAlert())
    }

    @Test
    fun exactlyCriticalThreshold_isCritical() {
        // 20% boundary: <= 20 → CRITICAL (precedence over WARNING). Raised from 16 to stay clear of
        // the low-battery Power-Saving range where the watch stops sampling screen-off (data loss).
        assertEquals(WatchBatteryAlert.CRITICAL, receiverWithBattery(20).currentBatteryAlert())
    }

    @Test
    fun belowCritical_isCritical() {
        assertEquals(WatchBatteryAlert.CRITICAL, receiverWithBattery(12).currentBatteryAlert())
    }

    @Test
    fun stopClearsTheAlert() {
        // Watch sends explicit STOP (disconnect) after a low reading. A disconnected watch should not
        // leave a stale low-battery warning on Home, so onStop() clears the last-seen level → NONE.
        val receiver = receiverWithBattery(10)
        receiver.onStop()
        assertEquals(WatchBatteryAlert.NONE, receiver.currentBatteryAlert())
    }

    @Test
    fun freshHealthyReading_clearsAlert() {
        val receiver = receiverWithBattery(10)
        assertEquals(WatchBatteryAlert.CRITICAL, receiver.currentBatteryAlert())
        // After charging, a fresh reading clears the banner.
        receiver.onReading(WatchReading("BATTERY", 80f, 0, 1L))
        assertEquals(WatchBatteryAlert.NONE, receiver.currentBatteryAlert())
    }
}
