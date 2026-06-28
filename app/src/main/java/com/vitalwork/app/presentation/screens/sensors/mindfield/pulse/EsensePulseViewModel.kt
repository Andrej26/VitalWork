package com.vitalwork.app.presentation.screens.sensors.mindfield.pulse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.ble.BleEvent
import com.vitalwork.app.data.sensor.ble.BleManager
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.ble.model.BleGattService
import com.vitalwork.app.presentation.components.BleDialogState
import com.vitalwork.app.presentation.components.DialogAction
import com.vitalwork.app.presentation.components.gattStatusToString
import com.vitalwork.app.presentation.log.BleLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vitalwork.app.util.TimeFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for the eSense Pulse sensor detail screen.
 */
data class EsensePulseUiState(
    val isScanning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val discoveredDevices: List<BleDevice> = emptyList(),
    val connectedDevice: BleDevice? = null,
    val discoveredServices: List<BleGattService> = emptyList(),
    val heartRate: Int? = null,
    val isMonitoringHeartRate: Boolean = false,
    val batteryLevel: Int? = null,
    val latestRrInterval: Int? = null,
    val rrIntervalHistory: List<Float> = emptyList(),
    val logEntries: List<BleLogEntry> = emptyList(),
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val locationEnabled: Boolean = true,
    val dialogState: BleDialogState? = null
)

/**
 * ViewModel for the eSense Pulse (BLE heart rate) sensor detail screen.
 * Manages BLE scanning, connection, and service discovery.
 */
@HiltViewModel
class EsensePulseViewModel @Inject constructor(
    private val bleManager: BleManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(EsensePulseUiState())
    val uiState: StateFlow<EsensePulseUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        .apply { timeZone = TimeFormats.UTC }
    private var lowBatteryShownForConnection = false
    private var lastConnectedDevice: BleDevice? = null
    private var scanTimeoutJob: Job? = null
    private var scanTimeoutShown = false

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                _uiState.update { it.copy(locationEnabled = isLocationEnabled()) }
            }
        }
    }

    init {
        _uiState.update { it.copy(locationEnabled = isLocationEnabled()) }
        context.registerReceiver(
            locationReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        )
        observeBleState()
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
                if (!scanning) {
                    cancelScanTimeout()
                }
            }
        }

        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
                if (state == ConnectionState.DISCONNECTED) {
                    _uiState.update {
                        it.copy(
                            isMonitoringHeartRate = false,
                            latestRrInterval = null,
                            rrIntervalHistory = emptyList()
                        )
                    }
                    lowBatteryShownForConnection = false
                }
            }
        }

        viewModelScope.launch {
            bleManager.discoveredDevices.collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
                if (devices.isNotEmpty()) {
                    cancelScanTimeout()
                }
            }
        }

        viewModelScope.launch {
            bleManager.connectedDevice.collect { device ->
                _uiState.update { it.copy(connectedDevice = device) }
                if (device != null) {
                    lastConnectedDevice = device
                }
            }
        }

        viewModelScope.launch {
            bleManager.discoveredServices.collect { services ->
                _uiState.update { it.copy(discoveredServices = services) }
                // Auto-read battery level when services are discovered
                if (services.isNotEmpty()) {
                    bleManager.readBatteryLevel()
                }
            }
        }

        viewModelScope.launch {
            bleManager.heartRate.collect { hr ->
                _uiState.update { it.copy(heartRate = hr) }
            }
        }

        viewModelScope.launch {
            bleManager.batteryLevel.collect { level ->
                _uiState.update { it.copy(batteryLevel = level) }
            }
        }

        viewModelScope.launch {
            bleManager.rrIntervalSampleFlow.collect { rr ->
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
            bleManager.bleEvents.collect { event ->
                addLogEntry(event)

                when (event) {
                    is BleEvent.UnexpectedDisconnection -> {
                        showDialog(
                            BleDialogState.UnexpectedDisconnection(
                                deviceName = event.deviceName,
                                reason = gattStatusToString(event.status)
                            )
                        )
                    }
                    is BleEvent.ConnectionTimeout -> {
                        showDialog(BleDialogState.ConnectionTimeout(event.deviceName))
                    }
                    is BleEvent.HeartRateNotificationEnabled -> {
                        _uiState.update { it.copy(isMonitoringHeartRate = true) }
                    }
                    is BleEvent.HeartRateNotificationDisabled -> {
                        _uiState.update {
                            it.copy(
                                isMonitoringHeartRate = false,
                                latestRrInterval = null,
                                rrIntervalHistory = emptyList()
                            )
                        }
                    }
                    is BleEvent.BatteryLevelRead -> {
                        if (event.percent <= 30 && !lowBatteryShownForConnection) {
                            lowBatteryShownForConnection = true
                            showDialog(BleDialogState.LowBattery(event.percent))
                        }
                    }
                    else -> { /* no dialog */ }
                }
            }
        }

        viewModelScope.launch {
            bleManager.bluetoothEnabled.collect { enabled ->
                _uiState.update { it.copy(bluetoothEnabled = enabled) }
                if (!enabled) {
                    addLogEntry("Bluetooth is disabled", isError = true)
                } else {
                    addLogEntry("Bluetooth is enabled", isError = false)
                }
            }
        }
    }

    /**
     * Update permission state after checking or requesting permissions.
     */
    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
        if (granted) {
            addLogEntry("Permissions granted", isError = false)
        } else {
            addLogEntry("Permissions not granted", isError = true)
        }
    }

    /**
     * Toggle BLE scanning on/off.
     */
    fun toggleScan() {
        if (_uiState.value.isScanning) {
            cancelScanTimeout()
            bleManager.stopScan()
        } else {
            if (!isLocationEnabled()) {
                showDialog(BleDialogState.LocationServicesRequired)
                return
            }
            scanTimeoutShown = false
            bleManager.startScan()
            startScanTimeout()
        }
    }

    /**
     * Start BLE scanning.
     */
    fun startScan() {
        bleManager.startScan()
    }

    /**
     * Stop BLE scanning.
     */
    fun stopScan() {
        bleManager.stopScan()
    }

    /**
     * Connect to a specific BLE device.
     */
    fun connectToDevice(device: BleDevice) {
        bleManager.connect(device)
    }

    /**
     * Disconnect from the currently connected device.
     */
    fun disconnect() {
        bleManager.disconnect()
    }

    /**
     * Start heart rate monitoring by enabling notifications.
     */
    fun startHeartRateMonitoring() {
        _uiState.update { it.copy(isMonitoringHeartRate = true) }
        bleManager.enableHeartRateNotifications()
    }

    /**
     * Stop heart rate monitoring by disabling notifications.
     */
    fun stopHeartRateMonitoring() {
        bleManager.disableHeartRateNotifications()
        _uiState.update {
            it.copy(
                isMonitoringHeartRate = false,
                latestRrInterval = null,
                rrIntervalHistory = emptyList()
            )
        }
    }

    /**
     * Clear the debug log.
     */
    fun clearLog() {
        _uiState.update { it.copy(logEntries = emptyList()) }
    }

    private fun addLogEntry(event: BleEvent) {
        val timestamp = timeFormatter.format(Date(event.timestamp))
        val entry = BleLogEntry(
            timestamp = timestamp,
            message = event.message,
            isError = event is BleEvent.Error
        )
        _uiState.update { state ->
            state.copy(logEntries = (listOf(entry) + state.logEntries).take(200))
        }
    }

    private fun addLogEntry(message: String, isError: Boolean) {
        val timestamp = timeFormatter.format(Date())
        val entry = BleLogEntry(
            timestamp = timestamp,
            message = message,
            isError = isError
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
                showDialog(BleDialogState.ScanTimeout)
            }
        }
    }

    private fun cancelScanTimeout() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
    }

    private fun showDialog(dialog: BleDialogState) {
        _uiState.update { it.copy(dialogState = dialog) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialogState = null) }
    }

    fun onDialogAction(action: DialogAction) {
        when (action) {
            DialogAction.OpenLocationSettings -> {
                dismissDialog()
            }
            DialogAction.RetryConnection -> {
                dismissDialog()
                lastConnectedDevice?.let { bleManager.connect(it) }
            }
            DialogAction.Reconnect -> {
                dismissDialog()
                lastConnectedDevice?.let { bleManager.connect(it) }
            }
            DialogAction.Dismiss -> {
                dismissDialog()
            }
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

    override fun onCleared() {
        super.onCleared()
        // Stop heart rate monitoring when navigating away
        if (_uiState.value.isMonitoringHeartRate) {
            bleManager.disableHeartRateNotifications()
        }
        context.unregisterReceiver(locationReceiver)
        // Note: BleManager is app-scoped singleton and does not need cleanup here
    }
}
