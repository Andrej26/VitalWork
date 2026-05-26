package com.biometrix.operator.data.sensor

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSensorDevice : SensorDevice {

    override val state = MutableStateFlow(DeviceState.Disconnected)
    override val sampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)

    var startStreamingCallCount = 0
        private set

    override fun startStreaming() {
        startStreamingCallCount++
        if (state.value == DeviceState.Connected) {
            state.value = DeviceState.Streaming
        }
    }

    // Unused by SensorRecordingRepositoryImpl — stubbed
    override val deviceName: String = "FakeSensor"
    override val dataRate: StateFlow<Float> = MutableStateFlow(0f)
    override val detailedStats: StateFlow<String> = MutableStateFlow("")
    override val events: SharedFlow<String> = MutableSharedFlow()
    override fun connect(context: Context) {}
    override fun stopStreaming() {}
    override fun disconnect() {}
}
