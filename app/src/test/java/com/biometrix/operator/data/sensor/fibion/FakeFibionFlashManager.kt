package com.biometrix.operator.data.sensor.fibion

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.fibion.model.FibionFlashDeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeFibionFlashManager : FibionFlashManager {

    override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val heartRateSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    override val ecgSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    override val rrIntervalSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)

    var subscribeHeartRateCallCount = 0
        private set
    var subscribeEcgCallCount = 0
        private set
    var lastSubscribeEcgSampleRate: Int? = null
        private set

    override fun subscribeHeartRate() { subscribeHeartRateCallCount++ }
    override fun subscribeEcg(sampleRate: Int) {
        subscribeEcgCallCount++
        lastSubscribeEcgSampleRate = sampleRate
    }

    // Unused by SensorRecordingRepositoryImpl — stubbed
    override val bluetoothEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val isScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val discoveredDevices: StateFlow<List<BleDevice>> = MutableStateFlow(emptyList())
    override val connectedDevice: StateFlow<BleDevice?> = MutableStateFlow(null)
    override val deviceSerial: StateFlow<String?> = MutableStateFlow(null)
    override val deviceInfo: StateFlow<FibionFlashDeviceInfo?> = MutableStateFlow(null)
    override val heartRate: StateFlow<Int?> = MutableStateFlow(null)
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)
    override val batteryLastUpdated: StateFlow<Long?> = MutableStateFlow(null)
    val eventsFlow = MutableSharedFlow<FibionFlashEvent>(extraBufferCapacity = 16)
    override val events: Flow<FibionFlashEvent> = eventsFlow
    override fun startScan(filterByName: Boolean) {}
    override fun stopScan() {}
    override fun connect(device: BleDevice) {}
    override fun disconnect() {}
    override fun unsubscribeAll() {}
    override fun unsubscribeHeartRate() {}
    override fun unsubscribeEcg() {}
    override fun readBatteryLevel() {}
    override fun readDeviceInfo() {}
    override fun cleanup() {}
}
