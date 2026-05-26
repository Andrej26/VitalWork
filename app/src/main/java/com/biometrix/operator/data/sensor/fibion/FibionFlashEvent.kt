package com.biometrix.operator.data.sensor.fibion

import com.biometrix.operator.data.sensor.ble.model.BleDevice

/**
 * Events emitted by the Fibion Flash manager for debug logging.
 */
sealed class FibionFlashEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String
) {
    class ScanStarted : FibionFlashEvent(message = "Fibion Flash scan started")
    class ScanStopped : FibionFlashEvent(message = "Fibion Flash scan stopped")
    class DeviceFound(val device: BleDevice) : FibionFlashEvent(
        message = "Found: ${device.name ?: device.address} (${device.rssi} dBm)"
    )
    class Connecting(val device: BleDevice) : FibionFlashEvent(
        message = "Connecting to ${device.name ?: device.address}..."
    )
    class Connected(val device: BleDevice, val serial: String) : FibionFlashEvent(
        message = "Connected to ${device.name ?: device.address} (serial: $serial)"
    )
    class Disconnected(reason: String? = null) : FibionFlashEvent(
        message = "Disconnected${reason?.let { ": $it" } ?: ""}"
    )
    class SubscriptionStarted(val path: String) : FibionFlashEvent(
        message = "Subscribed to $path"
    )
    class SubscriptionError(val path: String, error: String) : FibionFlashEvent(
        message = "Subscription error ($path): $error"
    )
    class HeartRateReceived(val bpm: Int) : FibionFlashEvent(
        message = "HR: $bpm BPM"
    )
    class BatteryLevelRead(val percent: Int) : FibionFlashEvent(
        message = "Battery: $percent%"
    )
    class DeviceInfoReceived(val serial: String, val productName: String) : FibionFlashEvent(
        message = "Device: $productName ($serial)"
    )
    class ConnectionTimeout(val deviceName: String) : FibionFlashEvent(
        message = "Connection timeout for $deviceName"
    )
    class Error(error: String) : FibionFlashEvent(message = "Error: $error")
    class Debug(debugMessage: String) : FibionFlashEvent(message = debugMessage)
}
