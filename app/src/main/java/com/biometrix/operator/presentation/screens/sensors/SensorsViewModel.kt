package com.biometrix.operator.presentation.screens.sensors

import androidx.lifecycle.ViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.HeartRateDevice
import com.biometrix.operator.data.prefs.HeartRateDevicePreferences
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.ble.Bc87State
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * ViewModel for SensorsScreen that exposes connection states from all sensors.
 */
@HiltViewModel
class SensorsViewModel @Inject constructor(
    connectionRepository: ConnectionRepository,
    heartRateDevicePreferences: HeartRateDevicePreferences
) : ViewModel() {

    /** Selected heart rate device */
    val selectedHeartRateDevice: StateFlow<HeartRateDevice> = heartRateDevicePreferences.selectedDevice

    /** BLE sensor (eSense Pulse) connection state */
    val bleConnectionState: StateFlow<ConnectionState> = connectionRepository.bleConnectionState

    /** Audio sensor (eSense Respiration) state */
    val respirationState: StateFlow<DeviceState> = connectionRepository.respirationState

    /** Fibion Flash wearable sensor connection state */
    val fibionFlashConnectionState: StateFlow<ConnectionState> = connectionRepository.fibionFlashConnectionState

    /** Beurer BC 87 blood pressure monitor state */
    val bc87State: StateFlow<Bc87State> = connectionRepository.bc87State
}

/**
 * Converts DeviceState to ConnectionState for UI consistency.
 */
fun DeviceState.toConnectionState(): ConnectionState = when (this) {
    DeviceState.Connected, DeviceState.Streaming -> ConnectionState.CONNECTED
    DeviceState.Connecting -> ConnectionState.CONNECTING
    DeviceState.Error -> ConnectionState.ERROR
    DeviceState.Disconnected -> ConnectionState.DISCONNECTED
}

fun Bc87State.toConnectionState(): ConnectionState = when (this) {
    is Bc87State.Idle -> ConnectionState.DISCONNECTED
    is Bc87State.Scanning, is Bc87State.Connecting, is Bc87State.Receiving -> ConnectionState.CONNECTING
    is Bc87State.Error -> ConnectionState.ERROR
}
