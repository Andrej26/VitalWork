package com.biometrix.operator.data.sensor.fibion

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.fibion.model.FibionFlashDeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for Fibion Flash device operations.
 * Uses the Movesense MDS library internally for BLE communication and sensor data subscriptions.
 *
 * The Fibion Flash advertises as "Movesense" over BLE. After connection, MDS provides
 * a REST-like API for subscribing to sensor data streams (HR, ECG).
 * Always operates in Chest mode.
 */
interface FibionFlashManager {

    // --- Connection state ---

    /** Whether Bluetooth is currently enabled on the device. */
    val bluetoothEnabled: StateFlow<Boolean>

    /** Whether a BLE scan is currently in progress. */
    val isScanning: StateFlow<Boolean>

    /** Current connection state. */
    val connectionState: StateFlow<ConnectionState>

    /** List of discovered Fibion Flash devices during scanning. */
    val discoveredDevices: StateFlow<List<BleDevice>>

    /** Currently connected device, or null if not connected. */
    val connectedDevice: StateFlow<BleDevice?>

    /** Device serial number (available after MDS connection). */
    val deviceSerial: StateFlow<String?>

    /** Device info (available after readDeviceInfo()). */
    val deviceInfo: StateFlow<FibionFlashDeviceInfo?>

    // --- Sensor data streams ---

    /** Current heart rate from /Meas/HR subscription, null if not subscribed. */
    val heartRate: StateFlow<Int?>

    /** Heart rate sample flow for recording. */
    val heartRateSampleFlow: SharedFlow<Float>

    /** ECG sample flow for recording. */
    val ecgSampleFlow: SharedFlow<Float>

    /** R-R interval flow for recording (each value is an R-R interval in milliseconds). */
    val rrIntervalSampleFlow: SharedFlow<Float>

    /** Battery level percentage (0-100), null if not read yet. */
    val batteryLevel: StateFlow<Int?>

    /** Timestamp (System.currentTimeMillis()) of the last successful battery read, null if never read. */
    val batteryLastUpdated: StateFlow<Long?>

    /** Flow of events for debug logging. */
    val events: Flow<FibionFlashEvent>

    // --- Scanning ---

    /** Start scanning for Fibion Flash (Movesense) devices.
     * @param filterByName If true (default), only shows devices with "Movesense" name prefix.
     *                     Set to false to show all nearby BLE devices for debugging. */
    fun startScan(filterByName: Boolean = true)

    /** Stop scanning. */
    fun stopScan()

    // --- Connection ---

    /** Connect to a Fibion Flash device via MDS. */
    fun connect(device: BleDevice)

    /** Disconnect from the currently connected device. */
    fun disconnect()

    // --- Data subscriptions ---

    /** Subscribe to heart rate data stream (/Meas/HR). */
    fun subscribeHeartRate()

    /** Subscribe to ECG data stream at given sample rate in Hz (/Meas/ECG/{rate}). */
    fun subscribeEcg(sampleRate: Int = 125)

    /** Unsubscribe from all active data streams. */
    fun unsubscribeAll()

    /** Unsubscribe from heart rate data stream only. */
    fun unsubscribeHeartRate()

    /** Unsubscribe from ECG data stream only. */
    fun unsubscribeEcg()

    // --- Device info ---

    /** Read battery level from the connected device. */
    fun readBatteryLevel()

    /** Read device info (serial, SW/HW version, product name). */
    fun readDeviceInfo()

    /** Clean up all resources. Should be called when the manager is no longer needed. */
    fun cleanup()
}
