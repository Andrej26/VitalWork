package com.vitalwork.app.data.sensor.ble

import java.util.UUID

internal const val ESENSE_MANUFACTURER_ID = 0xFF0C

internal fun ByteArray.toHexString(): String =
    joinToString(" ") { "%02X".format(it) }

internal object BleParsers {

    data class HeartRateMeasurement(
        val heartRate: Int,
        val rrIntervals: List<Float>  // in milliseconds
    )

    /**
     * Parse BLE Heart Rate Measurement characteristic per Bluetooth SIG specification.
     *
     * Byte layout:
     *   [Flags: 1 byte]
     *     Bit 0: HR format (0=UINT8, 1=UINT16)
     *     Bit 3: Energy Expended present (adds 2 bytes)
     *     Bit 4: R-R Interval(s) present (adds N*2 bytes)
     *   [Heart Rate: 1 or 2 bytes]
     *   [Energy Expended: 0 or 2 bytes]
     *   [R-R Interval(s): 0 or N*2 bytes, UINT16 LE, resolution 1/1024 sec]
     */
    fun parseHeartRateMeasurement(value: ByteArray): HeartRateMeasurement? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        var offset = 1

        val hrIs16Bit = (flags and 0x01) != 0
        val heartRate = if (hrIs16Bit) {
            if (value.size < offset + 2) return null
            val hr = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            hr
        } else {
            val hr = value[offset].toInt() and 0xFF
            offset += 1
            hr
        }

        if ((flags and 0x08) != 0) offset += 2

        val rrIntervals = mutableListOf<Float>()
        if ((flags and 0x10) != 0) {
            while (offset + 1 < value.size) {
                val raw = (value[offset].toInt() and 0xFF) or
                        ((value[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                val rrMs = raw * 1000.0f / 1024.0f
                if (rrMs > 0f) rrIntervals.add(rrMs)
            }
        }

        return HeartRateMeasurement(heartRate, rrIntervals)
    }

    fun getCompanyName(manufacturerId: Int): String? = when (manufacturerId) {
        0x004C -> "Apple"
        0x0006 -> "Microsoft"
        0x00E0 -> "Google"
        0x0075 -> "Samsung"
        0x0059 -> "Nordic Semiconductor"
        0x000D -> "Texas Instruments"
        ESENSE_MANUFACTURER_ID -> "eSense (Mindfield)"
        else -> null
    }

    fun getServiceName(uuid: UUID): String? = when (uuid.toString().uppercase().substring(4, 8)) {
        "1800" -> "Generic Access"
        "1801" -> "Generic Attribute"
        "1802" -> "Immediate Alert"
        "1803" -> "Link Loss"
        "1804" -> "Tx Power"
        "1805" -> "Current Time"
        "1806" -> "Reference Time Update"
        "1807" -> "Next DST Change"
        "1808" -> "Glucose"
        "1809" -> "Health Thermometer"
        "180A" -> "Device Information"
        "180D" -> "Heart Rate"
        "180E" -> "Phone Alert Status"
        "180F" -> "Battery Service"
        "1811" -> "Alert Notification"
        "1812" -> "Human Interface Device"
        "1813" -> "Scan Parameters"
        "1814" -> "Running Speed and Cadence"
        "1815" -> "Automation IO"
        "1816" -> "Cycling Speed and Cadence"
        "1818" -> "Cycling Power"
        "1819" -> "Location and Navigation"
        "181A" -> "Environmental Sensing"
        "181B" -> "Body Composition"
        "181C" -> "User Data"
        "181D" -> "Weight Scale"
        "181E" -> "Bond Management"
        "181F" -> "Continuous Glucose Monitoring"
        else -> null
    }

    fun getCharacteristicName(uuid: UUID): String? = when (uuid.toString().uppercase().substring(4, 8)) {
        "2A00" -> "Device Name"
        "2A01" -> "Appearance"
        "2A02" -> "Peripheral Privacy Flag"
        "2A03" -> "Reconnection Address"
        "2A04" -> "Peripheral Preferred Connection Parameters"
        "2A05" -> "Service Changed"
        "2A06" -> "Alert Level"
        "2A07" -> "Tx Power Level"
        "2A19" -> "Battery Level"
        "2A23" -> "System ID"
        "2A24" -> "Model Number String"
        "2A25" -> "Serial Number String"
        "2A26" -> "Firmware Revision String"
        "2A27" -> "Hardware Revision String"
        "2A28" -> "Software Revision String"
        "2A29" -> "Manufacturer Name String"
        "2A2A" -> "IEEE Regulatory Certification"
        "2A37" -> "Heart Rate Measurement"
        "2A38" -> "Body Sensor Location"
        "2A39" -> "Heart Rate Control Point"
        "2A3F" -> "Alert Status"
        "2A40" -> "Ringer Control Point"
        "2A41" -> "Ringer Setting"
        "2A46" -> "New Alert"
        "2A47" -> "Supported New Alert Category"
        "2A48" -> "Supported Unread Alert Category"
        "2A4A" -> "HID Information"
        "2A4B" -> "Report Map"
        "2A4C" -> "HID Control Point"
        "2A4D" -> "Report"
        "2A4E" -> "Protocol Mode"
        else -> null
    }
}
