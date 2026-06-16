package com.vitalwork.app.presentation.screens.tutorial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.prefs.TutorialPreferencesRepository
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.SensorDevice
import com.vitalwork.app.data.sensor.ble.BleManager
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.watch.WatchLinkStatus
import com.vitalwork.app.data.sensor.watch.WatchSensorReceiver
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
    // Galaxy Watch (passive — the watch companion app pushes data; the tablet only receives)
    val watchLinkStatus: WatchLinkStatus = WatchLinkStatus.DISCONNECTED,
    val watchBatteryLevel: Int? = null
)

@HiltViewModel
class TutorialViewModel @Inject constructor(
    private val bleManager: BleManager,
    @Named("respiration") private val respirationSensor: SensorDevice,
    private val watchSensorReceiver: WatchSensorReceiver,
    private val tutorialPreferences: TutorialPreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * Total tutorial slides — must match the slide list in TutorialScreen (1 welcome + 3 heart-rate
     * + 4 respiration + 4 galaxy-watch + 1 complete = 13). Used to bound step navigation. The VR phase
     * was removed.
     */
    val totalSteps: Int = 13

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
        observeWatchState()
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

    /**
     * Observe the Galaxy Watch link. Unlike the eSense sensors there is no tablet-side connect — the
     * watch companion app pushes readings and the tablet infers the link state in [WatchSensorReceiver].
     * The tutorial step just mirrors that inferred status (LIVE/DOZING/DISCONNECTED) + battery so the
     * operator can confirm the watch is actually streaming. The phone-Bluetooth warning reuses the same
     * [TutorialUiState.bluetoothEnabled] flag already driven by the heart-rate step.
     */
    private fun observeWatchState() {
        viewModelScope.launch {
            watchSensorReceiver.linkStatus.collect { status ->
                _uiState.update { it.copy(watchLinkStatus = status) }
            }
        }
        viewModelScope.launch {
            watchSensorReceiver.batteryLevel.collect { battery ->
                _uiState.update { it.copy(watchBatteryLevel = battery) }
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
