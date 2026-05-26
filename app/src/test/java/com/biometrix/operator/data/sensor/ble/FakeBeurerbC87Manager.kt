package com.biometrix.operator.data.sensor.ble

import com.biometrix.operator.data.model.BloodPressureReading
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeBeurerbC87Manager : BeurerbC87Manager {

    override val state: StateFlow<Bc87State> = MutableStateFlow(Bc87State.Idle)
    override val lastReading: StateFlow<BloodPressureReading?> = MutableStateFlow(null)
    override val readingFlow = MutableSharedFlow<BloodPressureReading>(extraBufferCapacity = 16)
    override val logFlow: SharedFlow<Pair<String, Boolean>> = MutableSharedFlow()
    override val bluetoothEnabled: StateFlow<Boolean> = MutableStateFlow(true)

    var startScanningCallCount = 0
        private set
    var stopScanningCallCount = 0
        private set

    override fun startScanning() { startScanningCallCount++ }
    override fun stopScanning() { stopScanningCallCount++ }
}
