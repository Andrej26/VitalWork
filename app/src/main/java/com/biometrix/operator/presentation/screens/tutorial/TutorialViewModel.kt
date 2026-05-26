package com.biometrix.operator.presentation.screens.tutorial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.HeartRateDevice
import com.biometrix.operator.data.prefs.HeartRateDevicePreferences
import com.biometrix.operator.data.prefs.TutorialPreferencesRepository
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.SensorDevice
import com.biometrix.operator.data.sensor.ble.BleManager
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.fibion.FibionFlashManager
import com.biometrix.operator.data.vr.VRConnectionManager
import com.biometrix.operator.data.vr.VrDeviceDiscovery
import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class TutorialUiState(
    val currentStep: Int = 0,
    // BLE (eSense Pulse)
    val bleConnectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val scannedDevices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val blePermissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val locationEnabled: Boolean = true,
    // Respiration
    val respirationState: DeviceState = DeviceState.Disconnected,
    val audioPermissionGranted: Boolean = false,
    // Fibion Flash
    val fibionConnectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val fibionScannedDevices: List<BleDevice> = emptyList(),
    val fibionIsScanning: Boolean = false,
    val fibionDeviceSerial: String? = null,
    // VR
    val vrConnectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val discoveredVrDevices: List<DiscoveredVrDevice> = emptyList(),
    val isVrDiscovering: Boolean = false,
    val selectedVrDevice: DiscoveredVrDevice? = null,
    val isWifiAvailable: Boolean = true
)

@HiltViewModel
class TutorialViewModel @Inject constructor(
    private val bleManager: BleManager,
    @Named("respiration") private val respirationSensor: SensorDevice,
    private val fibionFlashManager: FibionFlashManager,
    private val vrWebSocketClient: VRConnectionManager,
    private val mdnsDiscovery: VrDeviceDiscovery,
    private val tutorialPreferences: TutorialPreferencesRepository,
    private val heartRateDevicePreferences: HeartRateDevicePreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** Selected heart rate device — drives tutorial slide filtering */
    val selectedHeartRateDevice: StateFlow<HeartRateDevice> = heartRateDevicePreferences.selectedDevice

    /** Whether the user has ever explicitly chosen a device — frozen at ViewModel creation */
    private val showDeviceSelection = !heartRateDevicePreferences.hasExplicitSelection

    /** True if the device selection slide is included in this tutorial session */
    val showDeviceSelectionSlide: Boolean get() = showDeviceSelection

    /** Total steps: 13 with device selection slide, 12 without */
    val totalSteps: Int
        get() = if (showDeviceSelection) 13 else 12

    fun selectHeartRateDevice(device: HeartRateDevice) {
        heartRateDevicePreferences.select(device)
    }

    private val _uiState = MutableStateFlow(TutorialUiState())
    val uiState: StateFlow<TutorialUiState> = _uiState.asStateFlow()

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
        observeRespirationState()
        observeFibionState()
        observeVrState()
        mdnsDiscovery.startDiscovery()
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { it.copy(bleConnectionState = state) }
            }
        }
        viewModelScope.launch {
            bleManager.discoveredDevices.collect { devices ->
                _uiState.update { it.copy(scannedDevices = devices) }
            }
        }
        viewModelScope.launch {
            bleManager.bluetoothEnabled.collect { enabled ->
                _uiState.update { it.copy(bluetoothEnabled = enabled) }
            }
        }
    }

    private fun observeRespirationState() {
        viewModelScope.launch {
            respirationSensor.state.collect { state ->
                _uiState.update { it.copy(respirationState = state) }
            }
        }
    }

    private fun observeVrState() {
        viewModelScope.launch {
            vrWebSocketClient.connectionState.collect { state ->
                _uiState.update { it.copy(vrConnectionState = state) }
            }
        }
        viewModelScope.launch {
            mdnsDiscovery.discoveredDevices.collect { devices ->
                _uiState.update { it.copy(discoveredVrDevices = devices) }
            }
        }
        viewModelScope.launch {
            mdnsDiscovery.isDiscovering.collect { discovering ->
                _uiState.update { it.copy(isVrDiscovering = discovering) }
            }
        }
        viewModelScope.launch {
            var wasUnavailable = false
            mdnsDiscovery.isWifiAvailable.collect { available ->
                _uiState.update { it.copy(isWifiAvailable = available) }
                if (available && wasUnavailable &&
                    !mdnsDiscovery.isDiscovering.value &&
                    _uiState.value.vrConnectionState != ConnectionState.CONNECTED
                ) {
                    mdnsDiscovery.startDiscovery()
                }
                wasUnavailable = !available
            }
        }
    }

    // ── Step navigation ──────────────────────────────────────────────────────

    fun nextStep() {
        val current = _uiState.value.currentStep
        if (current < totalSteps - 1) {
            _uiState.update { it.copy(currentStep = current + 1) }
        }
    }

    fun previousStep() {
        val current = _uiState.value.currentStep
        if (current > 0) {
            _uiState.update { it.copy(currentStep = current - 1) }
        }
    }

    fun goToStep(step: Int) {
        if (step in 0 until totalSteps) {
            _uiState.update { it.copy(currentStep = step) }
        }
    }

    // ── BLE ──────────────────────────────────────────────────────────────────

    fun setBlePermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(blePermissionsGranted = granted) }
    }

    fun toggleBleScan() {
        if (_uiState.value.isScanning) {
            bleManager.stopScan()
        } else {
            bleManager.startScan()
        }
    }

    fun connectBleDevice(device: BleDevice) {
        bleManager.connect(device)
    }

    fun disconnectBle() {
        bleManager.disconnect()
    }

    // ── Respiration ──────────────────────────────────────────────────────────

    fun setAudioPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(audioPermissionGranted = granted) }
    }

    fun toggleRespirationConnection() {
        val state = _uiState.value.respirationState
        if (state == DeviceState.Disconnected || state == DeviceState.Error) {
            respirationSensor.connect(context)
        } else {
            respirationSensor.disconnect()
        }
    }

    private fun observeFibionState() {
        viewModelScope.launch {
            fibionFlashManager.isScanning.collect { scanning ->
                _uiState.update { it.copy(fibionIsScanning = scanning) }
            }
        }
        viewModelScope.launch {
            fibionFlashManager.connectionState.collect { state ->
                _uiState.update { it.copy(fibionConnectionState = state) }
            }
        }
        viewModelScope.launch {
            fibionFlashManager.discoveredDevices.collect { devices ->
                _uiState.update { it.copy(fibionScannedDevices = devices) }
            }
        }
        viewModelScope.launch {
            fibionFlashManager.deviceSerial.collect { serial ->
                _uiState.update { it.copy(fibionDeviceSerial = serial) }
            }
        }
    }

    // ── Fibion Flash ──────────────────────────────────────────────────────────

    fun toggleFibionScan() {
        if (_uiState.value.fibionIsScanning) {
            fibionFlashManager.stopScan()
        } else {
            fibionFlashManager.startScan()
        }
    }

    fun connectFibionDevice(device: BleDevice) {
        fibionFlashManager.connect(device)
    }

    fun disconnectFibion() {
        fibionFlashManager.disconnect()
    }

    // ── VR ───────────────────────────────────────────────────────────────────

    fun selectAndConnectVrDevice(device: DiscoveredVrDevice) {
        _uiState.update { it.copy(selectedVrDevice = device) }
        mdnsDiscovery.stopDiscovery()
        vrWebSocketClient.connect(device.host)
    }

    fun rescanVrDevices() {
        vrWebSocketClient.disconnect()
        _uiState.update { it.copy(selectedVrDevice = null) }
        mdnsDiscovery.startDiscovery()
    }

    fun disconnectVr() {
        vrWebSocketClient.disconnect()
        _uiState.update { it.copy(selectedVrDevice = null) }
        mdnsDiscovery.startDiscovery()
    }

    fun recheckLocationEnabled() {
        _uiState.update { it.copy(locationEnabled = isLocationEnabled()) }
    }

    // ── Prefs ─────────────────────────────────────────────────────────────────

    fun markFirstLaunchDone() {
        tutorialPreferences.markFirstLaunchDone()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(locationReceiver)
        mdnsDiscovery.stopDiscovery()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
