package com.biometrix.operator.presentation.screens.sensors

import androidx.lifecycle.ViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.HeartRateDevice
import com.biometrix.operator.data.prefs.HeartRateDevicePreferences
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.sensor.DeviceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for SensorsScreen that exposes connection states from all sensors.
 */
@HiltViewModel
class SensorsViewModel @Inject constructor(
    connectionRepository: ConnectionRepository,
    heartRateDevicePreferences: HeartRateDevicePreferences
) : ViewModel() {

    val selectedHeartRateDevice: StateFlow<HeartRateDevice> = heartRateDevicePreferences.selectedDevice

    val bleConnectionState: StateFlow<ConnectionState> = connectionRepository.bleConnectionState

    val respirationState: StateFlow<DeviceState> = connectionRepository.respirationState

    val fibionFlashConnectionState: StateFlow<ConnectionState> = connectionRepository.fibionFlashConnectionState
}

fun DeviceState.toConnectionState(): ConnectionState = when (this) {
    DeviceState.Connected, DeviceState.Streaming -> ConnectionState.CONNECTED
    DeviceState.Connecting -> ConnectionState.CONNECTING
    DeviceState.Error -> ConnectionState.ERROR
    DeviceState.Disconnected -> ConnectionState.DISCONNECTED
}
