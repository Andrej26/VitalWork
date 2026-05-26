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

    // --- sfloatToInt ---------------------------------------------------------------
    // IEEE 11073 SFLOAT: raw = exponent<<12 | (mantissa & 0x0FFF); value = mantissa * 10^exponent

    @Test
    fun `sfloatToInt decodes positive mantissa with zero exponent`() {
        // raw = 0x0078 (120, exponent 0, mantissa 120) → 120
        assertEquals(120, BleParsers.sfloatToInt(0x78, 0x00))
    }

    @Test
    fun `sfloatToInt decodes negative exponent`() {
        // raw = 0xF064 → exponent -1, mantissa 100 → 100 * 10^-1 = 10
        assertEquals(10, BleParsers.sfloatToInt(0x64, 0xF0.toByte()))
    }

    @Test
    fun `sfloatToInt returns null for special values`() {
        // NaN mantissa 0x07FF (exponent 0)
        assertNull(BleParsers.sfloatToInt(0xFF.toByte(), 0x07))
        // NRes mantissa 0x0800
        assertNull(BleParsers.sfloatToInt(0x00, 0x08))
        // +INF mantissa 0x07FE
        assertNull(BleParsers.sfloatToInt(0xFE.toByte(), 0x07))
        // -INF mantissa 0x0802
        assertNull(BleParsers.sfloatToInt(0x02, 0x08))
    }

    // --- calculateMap --------------------------------------------------------------

    @Test
    fun `calculateMap uses standard clinical formula`() {
        // 120/80 → (120 + 160) / 3 = 93
        assertEquals(93, BleParsers.calculateMap(120, 80))
        // 140/90 → (140 + 180) / 3 = 106
        assertEquals(106, BleParsers.calculateMap(140, 90))
    }

    // --- parseBpMeasurement --------------------------------------------------------
    // Layout: flags(1) sys(2) dia(2) map(2) [timestamp(7)] [pulse(2)]
    // SFLOAT 120 = 0x0078 (LE: 0x78 0x00); 80 = 0x0050; 72 = 0x0048; 0 = 0x0000

    @Test
    fun `parseBpMeasurement returns null below minimum length`() {
        assertNull(BleParsers.parseBpMeasurement(byteArrayOf(0x00, 0x78, 0x00, 0x50, 0x00, 0x00)))
    }

    @Test
    fun `parseBpMeasurement minimal frame fills MAP and leaves pulse null`() {
        // flags=0x00 (no timestamp, no pulse), sys=120, dia=80, device MAP=0 (ignored)
        val result = BleParsers.parseBpMeasurement(
            byteArrayOf(0x00, 0x78, 0x00, 0x50, 0x00, 0x00, 0x00)
        )
        assertNotNull(result)
        assertEquals(120, result!!.systolicMmHg)
        assertEquals(80, result.diastolicMmHg)
        assertEquals(93, result.meanArterialMmHg)  // recomputed, not device's 0
        assertNull(result.pulseRateBpm)
    }

    @Test
    fun `parseBpMeasurement with pulse rate`() {
        // flags=0x04 (pulse present), sys=120, dia=80, MAP=0, pulse=72
        val result = BleParsers.parseBpMeasurement(
            byteArrayOf(0x04, 0x78, 0x00, 0x50, 0x00, 0x00, 0x00, 0x48, 0x00)
        )
        assertEquals(72, result!!.pulseRateBpm)
    }

    @Test
    fun `parseBpMeasurement skips 7-byte timestamp before pulse`() {
        // flags=0x06 (timestamp + pulse). 7 timestamp bytes are arbitrary.
        val frame = byteArrayOf(
            0x06,                                       // flags
            0x78, 0x00, 0x50, 0x00, 0x00, 0x00,         // sys, dia, map
            0xE8.toByte(), 0x07, 0x03, 0x0F, 0x0A, 0x1E, 0x2D, // timestamp (2026, Mar, 15, 10:30:45)
            0x48, 0x00                                   // pulse = 72
        )
        val result = BleParsers.parseBpMeasurement(frame)
        assertEquals(120, result!!.systolicMmHg)
        assertEquals(72, result.pulseRateBpm)
    }

    @Test
    fun `parseBpMeasurement with pulse flag but truncated pulse bytes leaves pulse null`() {
        // flags=0x04 says pulse present, but only 1 pulse byte supplied
        val result = BleParsers.parseBpMeasurement(
            byteArrayOf(0x04, 0x78, 0x00, 0x50, 0x00, 0x00, 0x00, 0x48)
        )
        assertNotNull(result)
        assertNull(result!!.pulseRateBpm)
    }

    @Test
    fun `parseBpMeasurement returns null when systolic SFLOAT is NaN`() {
        // systolic mantissa 0x07FF → NaN
        val result = BleParsers.parseBpMeasurement(
            byteArrayOf(0x00, 0xFF.toByte(), 0x07, 0x50, 0x00, 0x00, 0x00)
        )
        assertNull(result)
    }

    // --- parseRacpResponse ---------------------------------------------------------

    @Test
    fun `parseRacpResponse decodes success frame`() {
        // opcode 0x06, operator 0x00, request 0x01 (Report Stored), response 0x01 (Success)
        val result = BleParsers.parseRacpResponse(byteArrayOf(0x06, 0x00, 0x01, 0x01))
        assertNotNull(result)
        assertEquals(0x01, result!!.requestOpcode)
        assertEquals(BleParsers.RacpResponse.RESPONSE_SUCCESS, result.responseCode)
    }

    @Test
    fun `parseRacpResponse decodes no-records frame`() {
        val result = BleParsers.parseRacpResponse(byteArrayOf(0x06, 0x00, 0x01, 0x06))
        assertEquals(BleParsers.RacpResponse.RESPONSE_NO_RECORDS, result!!.responseCode)
    }

    @Test
    fun `parseRacpResponse returns null for frames shorter than 4 bytes`() {
        assertNull(BleParsers.parseRacpResponse(byteArrayOf(0x06, 0x00, 0x01)))
    }

    @Test
    fun `parseRacpResponse returns null for non-response opcodes`() {
        // opcode 0x01 is "Report Stored Records" (a request, not a response)
        assertNull(BleParsers.parseRacpResponse(byteArrayOf(0x01, 0x01, 0x00, 0x00)))
    }
}
