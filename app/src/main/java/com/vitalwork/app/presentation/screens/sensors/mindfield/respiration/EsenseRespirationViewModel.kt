package com.vitalwork.app.presentation.screens.sensors.mindfield.respiration

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.SensorDevice
import com.vitalwork.app.data.sensor.audio.LowSignalWarning
import com.vitalwork.app.data.sensor.audio.MindfieldRespiration
import com.vitalwork.app.presentation.log.LogEntry
import com.vitalwork.app.presentation.log.LogType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named

/**
 * UI state for the eSense Respiration sensor detail screen.
 */
data class EsenseRespirationUiState(
    val state: DeviceState = DeviceState.Disconnected,
    val rate: Float = 0f,
    val stats: String = "Not Connected",
    val logEntries: List<LogEntry> = emptyList(),
    val permissionsGranted: Boolean = false,
    val showStreamData: Boolean = false,
    val lowSignalWarning: LowSignalWarning = LowSignalWarning.NONE,
    val disconnectReason: String? = null
)

/**
 * ViewModel for the eSense Respiration (audio jack) sensor detail screen.
 * Uses the MindfieldRespiration singleton for sensor management.
 */
@HiltViewModel
class EsenseRespirationViewModel @Inject constructor(
    @Named("respiration") private val sensorDevice: SensorDevice,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(EsenseRespirationUiState())
    val uiState: StateFlow<EsenseRespirationUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        observeSensorDevice()
    }

    private fun observeSensorDevice() {
        viewModelScope.launch {
            sensorDevice.state.collect { state ->
                _uiState.update {
                    it.copy(
                        state = state,
                        showStreamData = if (state == DeviceState.Disconnected) false else it.showStreamData
                    )
                }
            }
        }
        viewModelScope.launch {
            sensorDevice.dataRate.collect { rate ->
                _uiState.update { it.copy(rate = rate) }
            }
        }
        viewModelScope.launch {
            sensorDevice.detailedStats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        }
        viewModelScope.launch {
            sensorDevice.events.collect { log ->
                val entry = LogEntry(
                    timestamp = timeFormatter.format(Date()),
                    type = LogType.INFO,
                    message = log
                )
                _uiState.update { it.copy(logEntries = (listOf(entry) + it.logEntries).take(100)) }
            }
        }
        viewModelScope.launch {
            MindfieldRespiration.lowSignalWarning.collect { warning ->
                _uiState.update { it.copy(lowSignalWarning = warning) }
            }
        }
        viewModelScope.launch {
            MindfieldRespiration.lastDisconnectReason.collect { reason ->
                _uiState.update { it.copy(disconnectReason = reason) }
            }
        }
    }

    fun toggleConnection() {
        val currentState = _uiState.value.state
        if (currentState == DeviceState.Disconnected || currentState == DeviceState.Error) {
            sensorDevice.connect(context)
        } else {
            sensorDevice.disconnect()
        }
    }

    fun toggleStreamDisplay() {
        _uiState.update { it.copy(showStreamData = !it.showStreamData) }
    }

    fun clearLog() {
        _uiState.update { it.copy(logEntries = emptyList()) }
    }

    fun clearDisconnectReason() {
        MindfieldRespiration.clearDisconnectReason()
    }

    /**
     * Update permission state after checking or requesting permissions.
     */
    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    // Note: No onCleared() cleanup - sensor persists as app-scoped singleton
}
