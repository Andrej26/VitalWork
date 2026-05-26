package com.biometrix.operator.data.sensor

import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class DeviceState { Disconnected, Connecting, Connected, Streaming, Error }

/**
 * Common contract for all sensor devices.
 * Future devices (e.g., PolarH10, Muse) should implement this interface.
 */
interface SensorDevice {
    val deviceName: String

    // Reactive Streams (UI listens to these)
    val state: StateFlow<DeviceState>
    val dataRate: StateFlow<Float>      // Primary metric (br/min for respiration, BPM for heart, etc.)
    val detailedStats: StateFlow<String> // Debug info (e.g. "RA: 23.0")
    val events: SharedFlow<String>       // One-off logs (e.g. "Connection lost", "Battery low")
    val sampleFlow: SharedFlow<Float>    // Every sample, for recording (RA waveform for respiration)

    // Lifecycle Commands
    fun connect(context: Context)
    fun startStreaming()
    fun stopStreaming()
    fun disconnect()
}
