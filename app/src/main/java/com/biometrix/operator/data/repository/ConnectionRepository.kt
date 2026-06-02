package com.biometrix.operator.data.repository

import android.content.Context
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.SensorDevice
import com.biometrix.operator.data.sensor.audio.LowSignalWarning
import com.biometrix.operator.data.sensor.audio.MindfieldRespiration
import com.biometrix.operator.data.sensor.ble.BleEvent
import com.biometrix.operator.data.sensor.ble.BleManager
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.watch.WatchSensorReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import com.biometrix.operator.data.vr.VRConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Repository that aggregates connection states from all devices.
 * Provides a unified way to observe connection status and initiate connections across the app.
 */
@Singleton
class ConnectionRepository @Inject constructor(
    private val vrWebSocketClient: VRConnectionManager,
    private val bleManager: BleManager,
    @Named("respiration") private val respirationDevice: SensorDevice,
    private val watchReceiver: WatchSensorReceiver,
    @Named("lanAvailable") private val lanAvailableFlow: StateFlow<Boolean>
) {
    /** VR headset WebSocket connection state */
    val vrConnectionState: StateFlow<ConnectionState> = vrWebSocketClient.connectionState

    /** Whether VR connection is currently auto-reconnecting */
    val vrIsReconnecting: StateFlow<Boolean> = vrWebSocketClient.isReconnecting

    /** Whether the StressChamber scene is currently active (shared lock across ViewModels) */
    private val _isStressChamberSceneActive = MutableStateFlow(false)
    val isStressChamberSceneActive: StateFlow<Boolean> = _isStressChamberSceneActive

    fun setStressChamberSceneActive(active: Boolean) {
        _isStressChamberSceneActive.value = active
    }

    /** BLE sensor (eSense Pulse) connection state */
    val bleConnectionState: StateFlow<ConnectionState> = bleManager.connectionState

    /** Audio sensor (eSense Respiration) connection state */
    val respirationState: StateFlow<DeviceState> = respirationDevice.state

    /** Galaxy Watch (Data Layer channel) connection state */
    val watchConnectionState: StateFlow<ConnectionState> = watchReceiver.connectionState

    /** Heart rate from BLE sensor (null if not receiving) */
    val heartRate: StateFlow<Int?> = bleManager.heartRate

    /** Battery level percentage (0-100) from BLE sensor, null if not yet read */
    val batteryLevel: StateFlow<Int?> = bleManager.batteryLevel

    /** True for 5 s after each (re)connection while first HR readings may be inaccurate */
    val isHeartRateWarmingUp: StateFlow<Boolean> = bleManager.isHeartRateWarmingUp

    /** R-R interval sample flow from BLE HR sensor (ms) */
    val bleRrIntervalSampleFlow: SharedFlow<Float> = bleManager.rrIntervalSampleFlow

    /** Breathing rate (br/min) from respiration sensor */
    val respirationRate: StateFlow<Float> = respirationDevice.dataRate

    /** Low signal warning from respiration sensor */
    val respirationLowSignalWarning: StateFlow<LowSignalWarning> =
        (respirationDevice as? MindfieldRespiration)?.lowSignalWarning
            ?: MutableStateFlow(LowSignalWarning.NONE)

    /** Last disconnect/error reason from respiration sensor (null = no error) */
    val respirationDisconnectReason: StateFlow<String?> =
        (respirationDevice as? MindfieldRespiration)?.lastDisconnectReason
            ?: MutableStateFlow(null)

    /** Discovered BLE devices during scanning */
    val bleDiscoveredDevices: StateFlow<List<BleDevice>> = bleManager.discoveredDevices

    /** Whether a BLE scan is currently in progress */
    val bleIsScanning: StateFlow<Boolean> = bleManager.isScanning

    /** Whether Bluetooth is currently enabled on the device */
    val bluetoothEnabled: StateFlow<Boolean> = bleManager.bluetoothEnabled

    /** Whether WiFi/LAN is available (for VR headset connection) */
    val wifiEnabled: StateFlow<Boolean> = lanAvailableFlow

    /** Flow of BLE events (connection timeout, battery, disconnection, etc.) */
    val bleEvents: Flow<BleEvent> = bleManager.bleEvents

    fun startBleScan() {
        bleManager.startScan()
    }

    fun stopBleScan() {
        bleManager.stopScan()
    }

    fun connectBleDevice(device: BleDevice) {
        bleManager.connect(device)
    }

    fun enableHeartRateNotifications() {
        bleManager.enableHeartRateNotifications()
    }

    fun disableHeartRateNotifications() {
        bleManager.disableHeartRateNotifications()
    }

    fun connectRespiration(context: Context) {
        respirationDevice.connect(context)
    }

    fun disconnectBle() {
        bleManager.disconnect()
    }

    fun disconnectRespiration() {
        respirationDevice.disconnect()
    }

    fun clearRespirationDisconnectReason() {
        (respirationDevice as? MindfieldRespiration)?.clearDisconnectReason()
    }
}
