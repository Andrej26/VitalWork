package com.vitalwork.app.presentation.screens.sensors

import androidx.lifecycle.ViewModel
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.repository.ConnectionRepository
import com.vitalwork.app.data.sensor.DeviceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for SensorsScreen that exposes connection states from all sensors.
 */
@HiltViewModel
class SensorsViewModel @Inject constructor(
    connectionRepository: ConnectionRepository
) : ViewModel() {

    val bleConnectionState: StateFlow<ConnectionState> = connectionRepository.bleConnectionState

    val respirationState: StateFlow<DeviceState> = connectionRepository.respirationState

    val watchConnectionState: StateFlow<ConnectionState> = connectionRepository.watchConnectionState
}

fun DeviceState.toConnectionState(): ConnectionState = when (this) {
    DeviceState.Connected, DeviceState.Streaming -> ConnectionState.CONNECTED
    DeviceState.Connecting -> ConnectionState.CONNECTING
    DeviceState.Error -> ConnectionState.ERROR
    DeviceState.Disconnected -> ConnectionState.DISCONNECTED
}
