package com.biometrix.operator.presentation.screens.sensors.fibion.flash

import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.fibion.FibionFlashEvent
import com.biometrix.operator.data.sensor.fibion.model.FibionFlashDeviceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class FfLogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false
)

data class FibionFlashUiState(
    val isScanning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val discoveredDevices: List<BleDevice> = emptyList(),
    val connectedDevice: BleDevice? = null,
    val deviceSerial: String? = null,
    val deviceInfo: FibionFlashDeviceInfo? = null,
    val heartRate: Int? = null,
    val isEcgSubscribed: Boolean = false,
    val ecgLatestSample: Float? = null,
    val batteryLevel: Int? = null,
    val batteryLastUpdated: Long? = null,
    val logEntries: List<FfLogEntry> = emptyList(),
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val dialogState: FfDialogState? = null,
    val latestRrInterval: Int? = null,
    val rrIntervalHistory: List<Float> = emptyList()
)

sealed class FfDialogState {
    data object LocationServicesRequired : FfDialogState()
    data object ScanTimeout : FfDialogState()
    data class ConnectionTimeout(val deviceName: String) : FfDialogState()
    data class LowBattery(val percent: Int) : FfDialogState()
    data class UnexpectedDisconnection(val reason: String) : FfDialogState()
}

@HiltViewModel
class FibionFlashViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(FibionFlashUiState())
    val uiState: StateFlow<FibionFlashUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var lowBatteryShownForConnection = false
    private var lastConnectedDevice: BleDevice? = null
    private var scanTimeoutJob: Job? = null
    private var scanTimeoutShown = false

    init {
        observeFibionFlashState()
    }

    private fun observeFibionFlashState() {
        viewModelScope.launch {
            connectionRepository.fibionFlashIsScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
                if (!scanning) cancelScanTimeout()
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashConnectionState.collect { state ->
                _uiState.update {
                    if (state == ConnectionState.DISCONNECTED) {
                        it.copy(connectionState = state, isEcgSubscribed = false, ecgLatestSample = null, latestRrInterval = null, rrIntervalHistory = emptyList())
                    } else {
                        it.copy(connectionState = state)
                    }
                }
                if (state == ConnectionState.DISCONNECTED) {
                    lowBatteryShownForConnection = false
                }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashConnectedDevice.collect { device ->
                _uiState.update { it.copy(connectedDevice = device) }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashDeviceSerial.collect { serial ->
                _uiState.update { it.copy(deviceSerial = serial) }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashDeviceInfo.collect { info ->
                if (info != null) {
                    _uiState.update { it.copy(deviceInfo = info) }
                }
                // When null (disconnect), keep the cached value for reference
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashDiscoveredDevices.collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
                if (devices.isNotEmpty()) cancelScanTimeout()
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashHeartRate.collect { hr ->
                _uiState.update { it.copy(heartRate = hr) }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashEcgSampleFlow.collect { sample ->
                _uiState.update { it.copy(isEcgSubscribed = true, ecgLatestSample = sample) }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashRrIntervalSampleFlow.collect { rr ->
                _uiState.update { state ->
                    val history = (state.rrIntervalHistory + rr).takeLast(30)
                    state.copy(
                        latestRrInterval = rr.toInt(),
                        rrIntervalHistory = history
                    )
                }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashBatteryLevel.collect { level ->
                _uiState.update { it.copy(batteryLevel = level) }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashBatteryLastUpdated.collect { ts ->
                _uiState.update { it.copy(batteryLastUpdated = ts) }
            }
        }

        viewModelScope.launch {
            connectionRepository.bluetoothEnabled.collect { enabled ->
                val wasEnabled = _uiState.value.bluetoothEnabled
                _uiState.update { it.copy(bluetoothEnabled = enabled) }
                if (enabled && !wasEnabled) {
                    viewModelScope.launch {
                        delay(3000L)
                        val state = _uiState.value
                        if (state.bluetoothEnabled &&
                            state.permissionsGranted &&
                            !state.isScanning &&
                            state.connectionState == ConnectionState.DISCONNECTED
                        ) {
                            toggleScan()
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            connectionRepository.fibionFlashEvents.collect { event ->
                addLogEntry(event)

                when (event) {
                    is FibionFlashEvent.ConnectionTimeout -> {
                        showDialog(FfDialogState.ConnectionTimeout(event.deviceName))
                    }
                    is FibionFlashEvent.BatteryLevelRead -> {
                        if (event.percent <= 30 && !lowBatteryShownForConnection) {
                            lowBatteryShownForConnection = true
                            showDialog(FfDialogState.LowBattery(event.percent))
                        }
                    }
                    is FibionFlashEvent.Disconnected -> {
                        if (_uiState.value.connectionState != ConnectionState.DISCONNECTED) {
                            showDialog(FfDialogState.UnexpectedDisconnection(event.message))
                        }
                    }
                    else -> { /* no dialog */ }
                }
            }
        }
    }

    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    fun toggleScan() {
        if (_uiState.value.isScanning) {
            cancelScanTimeout()
            connectionRepository.stopFibionFlashScan()
        } else {
            if (!isLocationEnabled()) {
                showDialog(FfDialogState.LocationServicesRequired)
                return
            }
            scanTimeoutShown = false
            connectionRepository.startFibionFlashScan(filterByName = true)
            startScanTimeout()
        }
    }

    fun connectToDevice(device: BleDevice) {
        lastConnectedDevice = device
        _uiState.update { it.copy(deviceInfo = null) }
        connectionRepository.connectFibionFlashDevice(device)
    }

    fun disconnect() {
        connectionRepository.disconnectFibionFlashDevice()
    }

    fun subscribeHeartRate() {
        connectionRepository.subscribeFibionFlashHeartRate()
    }

    fun subscribeEcg() {
        connectionRepository.subscribeFibionFlashEcg()
    }

    fun subscribeAll() {
        subscribeHeartRate()
        subscribeEcg()
    }

    fun unsubscribeAll() {
        connectionRepository.unsubscribeFibionFlashAll()
        _uiState.update { it.copy(isEcgSubscribed = false, ecgLatestSample = null, latestRrInterval = null, rrIntervalHistory = emptyList()) }
    }

    fun readBatteryLevel() {
        connectionRepository.readFibionFlashBatteryLevel()
    }

    fun clearLog() {
        _uiState.update { it.copy(logEntries = emptyList()) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialogState = null) }
    }

    fun onRetryConnection() {
        dismissDialog()
        lastConnectedDevice?.let { connectionRepository.connectFibionFlashDevice(it) }
    }

    private fun addLogEntry(event: FibionFlashEvent) {
        val timestamp = timeFormatter.format(Date(event.timestamp))
        val entry = FfLogEntry(
            timestamp = timestamp,
            message = event.message,
            isError = event is FibionFlashEvent.Error
        )
        _uiState.update { state ->
            state.copy(logEntries = (listOf(entry) + state.logEntries).take(200))
        }
    }

    private fun startScanTimeout() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = viewModelScope.launch {
            delay(15_000L)
            if (_uiState.value.isScanning &&
                _uiState.value.discoveredDevices.isEmpty() &&
                !scanTimeoutShown
            ) {
                scanTimeoutShown = true
                showDialog(FfDialogState.ScanTimeout)
            }
        }
    }

    private fun cancelScanTimeout() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
    }

    private fun showDialog(dialog: FfDialogState) {
        _uiState.update { it.copy(dialogState = dialog) }
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isScanning) {
            connectionRepository.stopFibionFlashScan()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
