package com.biometrix.operator.data.sensor.fibion

import android.bluetooth.le.ScanCallback
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FibionParsersTest {

    private fun device(name: String?, address: String = "AA:BB:CC:DD:EE:FF", rssi: Int = -60) =
        BleDevice(
            name = name,
            address = address,
            rssi = rssi,
            advertisementData = emptyMap(),
            isConnectable = true,
            lastSeenTimestamp = 0L
        )

    // --- parseHeartRateNotification ------------------------------------------------

    @Test
    fun `full payload decodes hr and positive rr intervals in order`() {
        val result = FibionParsers.parseHeartRateNotification(
            """{"Body":{"average":72.4,"rrData":[800,820,790]}}"""
        )
        assertEquals(72, result.heartRate)
        assertEquals(listOf(800, 820, 790), result.rrIntervals)
    }

    @Test
    fun `non-positive average returns null hr`() {
        assertNull(
            FibionParsers.parseHeartRateNotification("""{"Body":{"average":-1,"rrData":[]}}""").heartRate
        )
        assertNull(
            FibionParsers.parseHeartRateNotification("""{"Body":{"average":0,"rrData":[]}}""").heartRate
        )
    }

    @Test
    fun `non-positive rr entries are filtered`() {
        val result = FibionParsers.parseHeartRateNotification(
            """{"Body":{"average":72,"rrData":[800,0,-5,820]}}"""
        )
        assertEquals(listOf(800, 820), result.rrIntervals)
    }

    @Test
    fun `missing Body or malformed json returns empty notification`() {
        for (input in listOf("""{"foo":1}""", "not json")) {
            val result = FibionParsers.parseHeartRateNotification(input)
            assertNull(result.heartRate)
            assertTrue(result.rrIntervals.isEmpty())
        }
    }

    // --- parseEcgSamples -----------------------------------------------------------

    @Test
    fun `mixed int and double samples decode to float preserving sign`() {
        val result = FibionParsers.parseEcgSamples(
            """{"Body":{"Samples":[1, 1.5, 2, -2.25]}}"""
        )
        assertEquals(listOf(1f, 1.5f, 2f, -2.25f), result)
    }

    @Test
    fun `NaN entries are skipped`() {
        val result = FibionParsers.parseEcgSamples(
            """{"Body":{"Samples":[1.0, "NaN", 3.0]}}"""
        )
        assertEquals(listOf(1f, 3f), result)
    }

    @Test
    fun `missing Samples or malformed json returns empty list`() {
        assertTrue(FibionParsers.parseEcgSamples("""{"Body":{}}""").isEmpty())
        assertTrue(FibionParsers.parseEcgSamples("not json").isEmpty())
    }

    // --- parseBatteryLevel ---------------------------------------------------------

    @Test
    fun `decodes Content int`() {
        assertEquals(85, FibionParsers.parseBatteryLevel("""{"Content":85}"""))
    }

    @Test
    fun `missing Content or malformed json returns null battery`() {
        assertNull(FibionParsers.parseBatteryLevel("""{"foo":1}"""))
        assertNull(FibionParsers.parseBatteryLevel("not json"))
    }

    // --- parseDeviceInfo -----------------------------------------------------------

    @Test
    fun `decodes full Content block and propagates serial`() {
        val info = FibionParsers.parseDeviceInfo(
            """{"Content":{"sw":"2.3.0","hw":"A","productName":"Fibion Flash"}}""",
            serial = "S1"
        )
        assertNotNull(info)
        assertEquals("S1", info!!.serial)
        assertEquals("2.3.0", info.swVersion)
        assertEquals("A", info.hwVersion)
        assertEquals("Fibion Flash", info.productName)
    }

    @Test
    fun `optString fallbacks fill missing fields`() {
        val info = FibionParsers.parseDeviceInfo("""{"Content":{}}""", serial = "S2")
        assertNotNull(info)
        assertEquals("", info!!.swVersion)
        assertEquals("", info.hwVersion)
        assertEquals("Fibion Flash", info.productName)
    }

    @Test
    fun `missing Content returns null device info`() {
        assertNull(FibionParsers.parseDeviceInfo("""{"foo":1}""", serial = "S3"))
    }

    // --- supportsMillivoltEcg ------------------------------------------------------

    @Test
    fun `semver at or above 2_3 is supported, below is not`() {
        assertTrue(FibionParsers.supportsMillivoltEcg("3.0"))
        assertTrue(FibionParsers.supportsMillivoltEcg("2.3.1"))
        assertTrue(FibionParsers.supportsMillivoltEcg("2.3"))
        assertFalse(FibionParsers.supportsMillivoltEcg("2.2"))
        assertFalse(FibionParsers.supportsMillivoltEcg("1.9"))
    }

    @Test
    fun `malformed version is not supported`() {
        assertFalse(FibionParsers.supportsMillivoltEcg(""))
        assertFalse(FibionParsers.supportsMillivoltEcg("abc"))
        assertFalse(FibionParsers.supportsMillivoltEcg("2"))
        assertFalse(FibionParsers.supportsMillivoltEcg("2.x"))
    }

    // --- isFibionFlashDevice -------------------------------------------------------

    @Test
    fun `filter disabled accepts anything including null name`() {
        assertTrue(FibionParsers.isFibionFlashDevice(device(name = null), filterByName = false))
        assertTrue(FibionParsers.isFibionFlashDevice(device(name = "Other"), filterByName = false))
    }

    @Test
    fun `filter enabled matches Movesense prefix case-insensitively`() {
        assertTrue(FibionParsers.isFibionFlashDevice(device("Movesense 1234"), filterByName = true))
        assertTrue(FibionParsers.isFibionFlashDevice(device("movesense 1234"), filterByName = true))
        assertFalse(FibionParsers.isFibionFlashDevice(device("Other"), filterByName = true))
        assertFalse(FibionParsers.isFibionFlashDevice(device(null), filterByName = true))
    }

    // --- mergeDiscovered -----------------------------------------------------------

    @Test
    fun `new device is appended and list sorted by rssi desc`() {
        val a = device("A", address = "AA", rssi = -50)
        val b = device("B", address = "BB", rssi = -40)
        val result = FibionParsers.mergeDiscovered(listOf(a), b)
        assertEquals(listOf(b, a), result)
    }

    @Test
    fun `existing address is replaced and new rssi drives sort`() {
        val aOld = device("A", address = "AA", rssi = -70)
        val b = device("B", address = "BB", rssi = -40)
        val aNew = device("A", address = "AA", rssi = -30)
        val result = FibionParsers.mergeDiscovered(listOf(aOld, b), aNew)
        assertEquals(2, result.size)
        assertEquals(aNew, result[0])
        assertEquals(b, result[1])
    }

    // --- scanFailureMessage --------------------------------------------------------

    @Test
    fun `maps known codes and returns unknown fallback with code`() {
        assertEquals(
            "Scan already started",
            FibionParsers.scanFailureMessage(ScanCallback.SCAN_FAILED_ALREADY_STARTED)
        )
        assertEquals(
            "App registration failed",
            FibionParsers.scanFailureMessage(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        )
        assertEquals(
            "Internal error",
            FibionParsers.scanFailureMessage(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        )
        assertEquals(
            "Feature unsupported",
            FibionParsers.scanFailureMessage(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
        )
        assertEquals("Unknown error (99)", FibionParsers.scanFailureMessage(99))
    }
}
