package com.biometrix.operator.data.sensor.ble

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.ble.model.BleGattService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

class FakeBleManager : BleManager {

    override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val heartRateSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    override val rrIntervalSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)

    var enableHrNotificationsCallCount = 0
        private set

    override fun enableHeartRateNotifications() { enableHrNotificationsCallCount++ }

    // Unused by SensorRecordingRepositoryImpl — stubbed
    override val bluetoothEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val isScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val discoveredDevices: StateFlow<List<BleDevice>> = MutableStateFlow(emptyList())
    override val connectedDevice: StateFlow<BleDevice?> = MutableStateFlow(null)
    override val discoveredServices: StateFlow<List<BleGattService>> = MutableStateFlow(emptyList())
    override val heartRate: StateFlow<Int?> = MutableStateFlow(null)
    override val isHeartRateWarmingUp: StateFlow<Boolean> = MutableStateFlow(false)
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)
    override val bleEvents: Flow<BleEvent> = emptyFlow()
    override fun startScan() {}
    override fun stopScan() {}
    override fun connect(device: BleDevice) {}
    override fun disconnect() {}
    override fun disableHeartRateNotifications() {}
    override fun readBatteryLevel() {}
    override fun resetScanState() {}
    override fun cleanup() {}
}
