package com.vitalwork.app.data.repository

import android.content.Context
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.SensorDevice
import com.vitalwork.app.data.sensor.audio.LowSignalWarning
import com.vitalwork.app.data.sensor.audio.MindfieldRespiration
import com.vitalwork.app.data.sensor.ble.BleEvent
import com.vitalwork.app.data.sensor.ble.BleManager
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.watch.WatchBatteryAlert
import com.vitalwork.app.data.sensor.watch.WatchSensorReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import com.vitalwork.app.data.vr.VrEventReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Repository that aggregates connection states from all devices.
 * Provides a unified way to observe connection status and initiate connections across the app.
 */
@Singleton
class ConnectionRepository @Inject constructor(
    private val vrEventReceiver: VrEventReceiver,
    private val bleManager: BleManager,
    @Named("respiration") private val respirationDevice: SensorDevice,
    private val watchReceiver: WatchSensorReceiver,
    @Named("lanAvailable") private val lanAvailableFlow: StateFlow<Boolean>
) {
    /** Long-lived scope for repository-owned derived flows (process-scoped singleton). */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * VR headset connection state — driven by the bonded Quest's heartbeat (~5 s), not by sparse
     * scenario events, so it stays CONNECTED through a long quiet scenario and only drops ~10 s after
     * heartbeats stop. (The event-activity state lives on as an internal log in [VrEventReceiver].)
     */
    val vrConnectionState: StateFlow<ConnectionState> = vrEventReceiver.heartbeatState

    /** BLE sensor (eSense Pulse) connection state */
    val bleConnectionState: StateFlow<ConnectionState> = bleManager.connectionState

    /** Audio sensor (eSense Respiration) connection state */
    val respirationState: StateFlow<DeviceState> = respirationDevice.state

    /** Galaxy Watch (Data Layer channel) connection state — coarse CONNECTED/DISCONNECTED. */
    val watchConnectionState: StateFlow<ConnectionState> = watchReceiver.connectionState

    /** Galaxy Watch finer link state: LIVE / DOZING (buffering) / DISCONNECTED. */
    val watchLinkStatus: StateFlow<com.vitalwork.app.data.sensor.watch.WatchLinkStatus> =
        watchReceiver.linkStatus

    /** Progress of the End-Session store-flush handshake (Idle / InProgress / Complete). */
    val watchFlushState: StateFlow<com.vitalwork.app.data.sensor.watch.WatchFlushState> =
        watchReceiver.flushState

    /** Reset the flush handshake before sending a fresh `FLUSH` to the watch. */
    fun beginWatchFlush() = watchReceiver.onFlushStarted()

    /** Latest EDA value (µS) from the Galaxy Watch for live display, null until first reading. */
    val watchEda: StateFlow<Float?> = watchReceiver.latestByType
        .map { it["WATCH_EDA"]?.value }
        .stateIn(repoScope, SharingStarted.Eagerly, null)

    /** Galaxy Watch battery level percentage (0-100), null until first reading. */
    val watchBatteryLevel: StateFlow<Int?> = watchReceiver.batteryLevel

    /** Map a watch-stamped timestamp onto the phone clock (phone↔watch offset correction). */
    fun watchCorrectedTimestamp(watchTimestampMs: Long): Long =
        watchReceiver.correctedTimestamp(watchTimestampMs)

    /** Current low-battery alert tier from the watch's last-known battery level. */
    fun watchBatteryAlert(): WatchBatteryAlert = watchReceiver.currentBatteryAlert()

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
