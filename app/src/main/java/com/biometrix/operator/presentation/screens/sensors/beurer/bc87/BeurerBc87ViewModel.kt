package com.biometrix.operator.presentation.screens.sensors.beurer.bc87

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.model.BloodPressureReading
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.sensor.ble.Bc87State
import com.biometrix.operator.presentation.log.BleLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BeurerBc87ViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val bc87State: StateFlow<Bc87State> = connectionRepository.bc87State
    val lastReading: StateFlow<BloodPressureReading?> = connectionRepository.bc87LastReading
    val bluetoothEnabled: StateFlow<Boolean> = connectionRepository.bc87BluetoothEnabled

    private val _recentReadings = MutableStateFlow<List<BloodPressureReading>>(emptyList())
    val recentReadings: StateFlow<List<BloodPressureReading>> = _recentReadings.asStateFlow()

    private val _logEntries = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val logEntries: StateFlow<List<BleLogEntry>> = _logEntries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        // Collect reading events
        viewModelScope.launch {
            connectionRepository.bc87ReadingFlow.collect { reading ->
                _recentReadings.value = (listOf(reading) + _recentReadings.value).take(10)
            }
        }

        // Collect log events
        viewModelScope.launch {
            connectionRepository.bc87LogFlow.collect { (message, isError) ->
                val entry = BleLogEntry(
                    timestamp = timeFormat.format(Date()),
                    message = message,
                    isError = isError
                )
                _logEntries.value = (listOf(entry) + _logEntries.value).take(200)
            }
        }
    }

    fun startScanning() {
        connectionRepository.startBc87Scanning()
    }

    fun stopScanning() {
        connectionRepository.stopBc87Scanning()
    }

    fun clearLog() {
        _logEntries.value = emptyList()
    }

    // Does NOT stop scanner in onCleared() — lifecycle managed by TestControlViewModel
}
