package com.biometrix.operator.data.sensor.ble.model

/**
 * Represents a discovered BLE device with its advertisement data.
 */
data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val advertisementData: Map<String, String>,
    val isConnectable: Boolean,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = name ?: "Unknown Device"

    val signalStrength: SignalStrength
        get() = when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -60 -> SignalStrength.GOOD
            rssi >= -70 -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }
}

enum class SignalStrength {
    EXCELLENT,
    GOOD,
    FAIR,
    WEAK
}
