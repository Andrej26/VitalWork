package com.biometrix.operator.data.sensor.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleParsersTest {

    // --- parseHeartRateMeasurement -------------------------------------------------

    @Test
    fun `uint8 HR with no optional fields`() {
        val result = BleParsers.parseHeartRateMeasurement(byteArrayOf(0x00, 72))
        assertNotNull(result)
        assertEquals(72, result!!.heartRate)
        assertTrue(result.rrIntervals.isEmpty())
    }

    @Test
    fun `uint16 HR decoded little-endian`() {
        // flags=0x01 (UINT16), value=0x0140 LE = 320
        val result = BleParsers.parseHeartRateMeasurement(
            byteArrayOf(0x01, 0x40, 0x01)
        )
        assertEquals(320, result!!.heartRate)
    }

    @Test
    fun `single RR interval converts 1024 raw units to 1000 ms`() {
        // flags=0x10 (RR present), HR=72, RR raw=1024 (0x0400 LE)
        val result = BleParsers.parseHeartRateMeasurement(
            byteArrayOf(0x10, 72, 0x00, 0x04)
        )
        assertEquals(1, result!!.rrIntervals.size)
        assertEquals(1000f, result.rrIntervals[0], 0.001f)
    }

    @Test
    fun `multiple RR intervals preserve order and conversion`() {
        // raws 1024, 512 -> 1000f, 500f
        val result = BleParsers.parseHeartRateMeasurement(
            byteArrayOf(0x10, 72, 0x00, 0x04, 0x00, 0x02)
        )
        assertEquals(2, result!!.rrIntervals.size)
        assertEquals(1000f, result.rrIntervals[0], 0.001f)
        assertEquals(500f, result.rrIntervals[1], 0.001f)
    }

    @Test
    fun `energy expended flag skips two bytes before RR`() {
        // flags=0x18 (energy + RR), HR=72, energy=0xFFFF (skipped), RR raw=1024
        val result = BleParsers.parseHeartRateMeasurement(
            byteArrayOf(0x18, 72, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x04)
        )
        assertEquals(72, result!!.heartRate)
        assertEquals(1, result.rrIntervals.size)
        assertEquals(1000f, result.rrIntervals[0], 0.001f)
    }

    @Test
    fun `zero RR interval is filtered out`() {
        // RR raws: 0 (filtered), 1024 (kept)
        val result = BleParsers.parseHeartRateMeasurement(
            byteArrayOf(0x10, 72, 0x00, 0x00, 0x00, 0x04)
        )
        assertEquals(1, result!!.rrIntervals.size)
        assertEquals(1000f, result.rrIntervals[0], 0.001f)
    }

    @Test
    fun `buffer smaller than flags plus uint8 HR returns null`() {
        assertNull(BleParsers.parseHeartRateMeasurement(byteArrayOf(0x00)))
    }

    @Test
    fun `uint16 HR flag with only one HR byte returns null`() {
        // flags=0x01 demands 2 HR bytes; only 1 supplied after flags
        assertNull(BleParsers.parseHeartRateMeasurement(byteArrayOf(0x01, 0x64)))
    }

    @Test
    fun `odd trailing byte after RR intervals is ignored`() {
        // One full RR (1024) + one dangling byte
        val result = BleParsers.parseHeartRateMeasurement(
            byteArrayOf(0x10, 72, 0x00, 0x04, 0x00)
        )
        assertEquals(1, result!!.rrIntervals.size)
        assertEquals(1000f, result.rrIntervals[0], 0.001f)
    }

    @Test
    fun `RR flag set but no RR bytes yields empty list not null`() {
        val result = BleParsers.parseHeartRateMeasurement(byteArrayOf(0x10, 72))
        assertNotNull(result)
        assertEquals(72, result!!.heartRate)
        assertTrue(result.rrIntervals.isEmpty())
    }

    // --- getCompanyName ------------------------------------------------------------

    @Test
    fun `getCompanyName maps Apple id`() {
        assertEquals("Apple", BleParsers.getCompanyName(0x004C))
    }

    @Test
    fun `getCompanyName maps eSense manufacturer id`() {
        assertEquals("eSense (Mindfield)", BleParsers.getCompanyName(ESENSE_MANUFACTURER_ID))
    }

    @Test
    fun `getCompanyName returns null for unknown id`() {
        assertNull(BleParsers.getCompanyName(0x1234))
    }

    // --- getServiceName ------------------------------------------------------------

    @Test
    fun `getServiceName maps Heart Rate UUID`() {
        val uuid = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        assertEquals("Heart Rate", BleParsers.getServiceName(uuid))
    }

    @Test
    fun `getServiceName is case-insensitive on UUID input`() {
        val uuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        assertEquals("Heart Rate", BleParsers.getServiceName(uuid))
    }

    @Test
    fun `getServiceName returns null for unrelated UUID`() {
        val uuid = UUID.fromString("12345678-0000-1000-8000-00805F9B34FB")
        assertNull(BleParsers.getServiceName(uuid))
    }

    // --- getCharacteristicName -----------------------------------------------------

    @Test
    fun `getCharacteristicName maps Heart Rate Measurement UUID`() {
        val uuid = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
        assertEquals("Heart Rate Measurement", BleParsers.getCharacteristicName(uuid))
    }

    @Test
    fun `getCharacteristicName returns null for unrelated UUID`() {
        val uuid = UUID.fromString("00009999-0000-1000-8000-00805F9B34FB")
        assertNull(BleParsers.getCharacteristicName(uuid))
    }

    // --- toHexString ---------------------------------------------------------------

    @Test
    fun `toHexString of empty array is empty string`() {
        assertEquals("", byteArrayOf().toHexString())
    }

    @Test
    fun `toHexString formats bytes as uppercase hex separated by spaces`() {
        val bytes = byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte())
        assertEquals("01 AB FF", bytes.toHexString())
    }

}
