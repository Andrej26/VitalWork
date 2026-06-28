package com.vitalwork.app.data.sensor.watch

import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.watch.model.WatchReading
import com.vitalwork.app.data.time.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the immediate [WatchSensorReceiver.linkStatus] transitions driven synchronously by the
 * message entry points (the time-decay LIVE→DOZING path is exercised on-device, since it is wall-clock
 * + watchdog driven). Confirms a fresh reading reads LIVE, a heartbeat-only reads DOZING, and an
 * explicit STOP reads DISCONNECTED — and that the coarse [connectionState] mirrors them. Note the
 * watchdog never auto-declares DISCONNECTED: a connected watch stays LIVE/DOZING until an explicit Stop,
 * because during a session it is expected to sleep and buffer locally (no silence-based false alarm).
 */
class WatchLinkStatusTest {

    private fun freshReading(type: String = "WATCH_EDA") =
        WatchReading(type = type, value = 1.0f, accuracy = 0, timestampMs = System.currentTimeMillis())

    @Test
    fun freshState_isDisconnected() {
        val r = WatchSensorReceiver(TimeProvider.system())
        assertEquals(WatchLinkStatus.DISCONNECTED, r.linkStatus.value)
        assertEquals(ConnectionState.DISCONNECTED, r.connectionState.value)
    }

    @Test
    fun aRecentReading_isLive_andConnected() {
        val r = WatchSensorReceiver(TimeProvider.system()).apply { onReading(freshReading()) }
        assertEquals(WatchLinkStatus.LIVE, r.linkStatus.value)
        assertEquals(ConnectionState.CONNECTED, r.connectionState.value)
    }

    @Test
    fun heartbeatWithoutReading_isDozing_butStillConnected() {
        val r = WatchSensorReceiver(TimeProvider.system()).apply { onHeartbeat() }
        // A heartbeat is activity (not gone) but not a reading → DOZING, and CONNECTED at the coarse level.
        assertEquals(WatchLinkStatus.DOZING, r.linkStatus.value)
        assertEquals(ConnectionState.CONNECTED, r.connectionState.value)
    }

    @Test
    fun explicitStop_isDisconnected() {
        val r = WatchSensorReceiver(TimeProvider.system()).apply {
            onReading(freshReading())
            onStop()
        }
        assertEquals(WatchLinkStatus.DISCONNECTED, r.linkStatus.value)
        assertEquals(ConnectionState.DISCONNECTED, r.connectionState.value)
    }
}
