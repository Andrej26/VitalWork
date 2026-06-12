package com.vitalwork.app.data.sensor.ble

import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.ble.model.BleGattService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for BLE operations including scanning, connecting, and service discovery.
 */
interface BleManager {
    /** Whether Bluetooth is currently enabled on the device. */
    val bluetoothEnabled: StateFlow<Boolean>

    /** Whether a BLE scan is currently in progress. */
    val isScanning: StateFlow<Boolean>

    /** Current connection state. */
    val connectionState: StateFlow<ConnectionState>

    /** List of discovered BLE devices during scanning. */
    val discoveredDevices: StateFlow<List<BleDevice>>

    /** Currently connected device, or null if not connected. */
    val connectedDevice: StateFlow<BleDevice?>

    /** Discovered GATT services on the connected device. */
    val discoveredServices: StateFlow<List<BleGattService>>

    /** Current heart rate value from notifications, null if not receiving. */
    val heartRate: StateFlow<Int?>

    /** Every heart rate sample for recording (includes consecutive duplicates). */
    val heartRateSampleFlow: SharedFlow<Float>

    /** R-R interval samples in milliseconds from BLE Heart Rate Measurement characteristic. */
    val rrIntervalSampleFlow: SharedFlow<Float>

    /** True for 5 s after each CCCD write — first readings may be inaccurate (motion artifacts). */
    val isHeartRateWarmingUp: StateFlow<Boolean>

    /** Current battery level percentage (0-100), null if not read yet. */
    val batteryLevel: StateFlow<Int?>

    /** Flow of BLE events for debug logging. */
    val bleEvents: Flow<BleEvent>

    /** Start scanning for BLE devices. */
    fun startScan()

    /** Stop scanning for BLE devices. */
    fun stopScan()

    /** Connect to a specific BLE device. */
    fun connect(device: BleDevice)

    /** Disconnect from the currently connected device. */
    fun disconnect()

    /** Enable heart rate notifications from the connected device. */
    fun enableHeartRateNotifications()

    /** Disable heart rate notifications from the connected device. */
    fun disableHeartRateNotifications()

    /** Read battery level from the connected device. */
    fun readBatteryLevel()

    /** Force-reset scan state. Use as a recovery mechanism if scanning gets stuck. */
    fun resetScanState()

    /** Clean up resources. Should be called when the manager is no longer needed. */
    fun cleanup()
}

/**
 * Events emitted by the BLE manager for debug logging.
 */
sealed class BleEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String
) {
    class ScanStarted : BleEvent(message = "BLE scan started")
    class ScanStopped : BleEvent(message = "BLE scan stopped")
    class DeviceFound(val device: BleDevice) : BleEvent(
        message = "Found: ${device.name ?: device.address} (${device.rssi} dBm)"
    )
    class Connecting(val device: BleDevice) : BleEvent(
        message = "Connecting to ${device.name ?: device.address}..."
    )
    class Connected(val device: BleDevice) : BleEvent(
        message = "Connected to ${device.name ?: device.address}"
    )
    class Disconnected(reason: String? = null) : BleEvent(
        message = "Disconnected${reason?.let { ": $it" } ?: ""}"
    )
    class DiscoveringServices : BleEvent(message = "Discovering services...")
    class ServiceDiscovered(val service: BleGattService) : BleEvent(
        message = "Service: ${service.displayName} (${service.characteristics.size} characteristics)"
    )
    class ServicesDiscoveryComplete(val count: Int) : BleEvent(
        message = "Service discovery complete: $count services found"
    )
    class HeartRateNotificationEnabled : BleEvent(message = "Heart rate notifications enabled")
    class HeartRateNotificationDisabled : BleEvent(message = "Heart rate notifications disabled")
    class HeartRateReceived(val bpm: Int) : BleEvent(message = "Heart rate: $bpm BPM")
    class BatteryLevelRead(val percent: Int) : BleEvent(message = "Battery: $percent%")
    class UnexpectedDisconnection(val deviceName: String, val status: Int) : BleEvent(
        message = "Unexpected disconnection from $deviceName (status: $status)"
    )
    class ConnectionTimeout(val deviceName: String) : BleEvent(
        message = "Connection timeout for $deviceName"
    )
    class Error(error: String) : BleEvent(message = "Error: $error")
    class Debug(debugMessage: String) : BleEvent(message = debugMessage)
}
