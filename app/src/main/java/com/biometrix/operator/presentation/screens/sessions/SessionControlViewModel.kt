package com.biometrix.operator.presentation.screens.sessions

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.ScenarioRecordingRepository
import com.biometrix.operator.data.system.LocationChecker
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.sensor.audio.LowSignalWarning
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.repository.SessionRepository
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.ble.BleEvent
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.vr.VRConnectionManager
import com.biometrix.operator.data.vr.VrDeviceDiscovery
import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import com.biometrix.operator.data.vr.model.WebSocketMessage
import com.biometrix.operator.presentation.components.BleDialogState
import com.biometrix.operator.presentation.components.DialogAction
import com.biometrix.operator.presentation.components.gattStatusToString
import com.biometrix.operator.presentation.screens.sessions.components.NotesSaveStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SessionControlViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val sensorRecordingRepository: ScenarioRecordingRepository,
    private val sessionRepository: SessionRepository,
    private val scenarioRepository: ScenarioRepository,
    private val vrWebSocketClient: VRConnectionManager,
    private val mdnsDiscovery: VrDeviceDiscovery,
    private val locationChecker: LocationChecker,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        // Legacy VR events from the previous NarrowingChamber/StressChamber project. The new
        // logistics VR app will not emit these; the wiring is kept inert until the new
        // protocol (scenario_start / event_triggered / reaction_recorded / scenario_end)
        // replaces it. Tracked as a follow-up cleanup.
        private const val VR_EVENT_START_BIOFEEDBACK = "start_recording"
        private const val VR_EVENT_STOP_BIOFEEDBACK = "stop_recording"

        // Placeholder scenario used when the legacy VR `start_recording` message arrives
        // (which only happens if you point this app at an old-project VR build during
        // development). Will be removed when the new VR protocol lands.
        private val LEGACY_VR_PLACEHOLDER_SCENARIO = ScenarioCode.FALLING_PALLET
    }

    val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _session = MutableStateFlow<SessionEntity?>(null)
    val session: StateFlow<SessionEntity?> = _session.asStateFlow()

    /** VR headset WebSocket connection state */
    val vrConnectionState: StateFlow<ConnectionState> = connectionRepository.vrConnectionState

    /** BLE sensor (eSense Pulse) connection state */
    val bleConnectionState: StateFlow<ConnectionState> = connectionRepository.bleConnectionState

    /** Audio sensor (eSense Respiration) state */
    val respirationState: StateFlow<DeviceState> = connectionRepository.respirationState

    /** Live heart rate value */
    val heartRate: StateFlow<Int?> = connectionRepository.heartRate

    /** Battery level of connected BLE device (null if not yet read) */
    val bleBatteryLevel: StateFlow<Int?> = connectionRepository.batteryLevel

    /** True for 5 s after each BLE (re)connection while first readings may be inaccurate */
    val isHeartRateStabilizing: StateFlow<Boolean> = connectionRepository.isHeartRateWarmingUp

    /** Live respiration rate value */
    val respirationRate: StateFlow<Float> = connectionRepository.respirationRate

    /** Low signal warning from respiration sensor */
    val respirationLowSignalWarning: StateFlow<LowSignalWarning> =
        connectionRepository.respirationLowSignalWarning

    /** Last disconnect/error reason from respiration sensor */
    val respirationDisconnectReason: StateFlow<String?> =
        connectionRepository.respirationDisconnectReason

    /** Notes text */
    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    /** Notes auto-save status indicator */
    private val _notesSaveStatus = MutableStateFlow(NotesSaveStatus.Idle)
    val notesSaveStatus: StateFlow<NotesSaveStatus> = _notesSaveStatus.asStateFlow()
    private var userHasEditedNotes = false
    private var savedDismissJob: Job? = null

    /** Session ending state */
    private val _isEndingSession = MutableStateFlow(false)
    val isEndingSession: StateFlow<Boolean> = _isEndingSession.asStateFlow()

    private val _endSessionResult = MutableStateFlow<EndSessionResult?>(null)
    val endSessionResult: StateFlow<EndSessionResult?> = _endSessionResult.asStateFlow()

    /** BLE discovered devices (for scan dialog) */
    val bleDiscoveredDevices: StateFlow<List<BleDevice>> = connectionRepository.bleDiscoveredDevices

    /** Whether BLE scan is active */
    val bleIsScanning: StateFlow<Boolean> = connectionRepository.bleIsScanning

    /** Whether Bluetooth is currently enabled on the device */
    val bluetoothEnabled: StateFlow<Boolean> = connectionRepository.bluetoothEnabled

    /** Whether BLE runtime permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT) are granted */
    private val _blePermissionsGranted = MutableStateFlow(false)
    val blePermissionsGranted: StateFlow<Boolean> = _blePermissionsGranted.asStateFlow()

    fun setBlePermissionsGranted(granted: Boolean) {
        _blePermissionsGranted.value = granted
    }

    /** Whether the BLE scan dialog is showing */
    private val _showBleScanDialog = MutableStateFlow(false)
    val showBleScanDialog: StateFlow<Boolean> = _showBleScanDialog.asStateFlow()

    /** BLE dialog state for warnings (location, connection timeout, low battery, disconnection) */
    private val _bleDialogState = MutableStateFlow<BleDialogState?>(null)
    val bleDialogState: StateFlow<BleDialogState?> = _bleDialogState.asStateFlow()

    /** Whether scan timeout was reached (for inline display in BleScanDialog) */
    private val _scanTimeoutReached = MutableStateFlow(false)
    val scanTimeoutReached: StateFlow<Boolean> = _scanTimeoutReached.asStateFlow()

    private var scanTimeoutJob: Job? = null
    private var scanTimeoutShown = false
    private var lowBatteryShownForConnection = false
    private var lastConnectedDevice: BleDevice? = null
    private var bleFirstDataJob: Job? = null

    /** Whether the current recording was triggered by a legacy VR biofeedback command */
    private val _vrTriggeredRecording = MutableStateFlow(false)
    val vrTriggeredRecording: StateFlow<Boolean> = _vrTriggeredRecording.asStateFlow()

    /** Latest legacy VR biofeedback event for UI feedback */
    private val _lastVrBiofeedbackEvent = MutableStateFlow<VrBiofeedbackEvent?>(null)
    val lastVrBiofeedbackEvent: StateFlow<VrBiofeedbackEvent?> = _lastVrBiofeedbackEvent.asStateFlow()

    /** Whether the legacy StressChamber scene is currently active (shared via ConnectionRepository) */
    val isStressChamberSceneActive: StateFlow<Boolean> = connectionRepository.isStressChamberSceneActive

    /** VR devices discovered via mDNS */
    val discoveredVrDevices: StateFlow<List<DiscoveredVrDevice>> = mdnsDiscovery.discoveredDevices

    /** Whether mDNS discovery is active */
    val isVrDiscovering: StateFlow<Boolean> = mdnsDiscovery.isDiscovering

    /** Whether Wi-Fi is available for VR discovery */
    val isVrWifiAvailable: StateFlow<Boolean> = mdnsDiscovery.isWifiAvailable

    /** Currently selected (connected) VR device */
    private val _selectedVrDevice = MutableStateFlow<DiscoveredVrDevice?>(null)
    val selectedVrDevice: StateFlow<DiscoveredVrDevice?> = _selectedVrDevice.asStateFlow()

    /** Whether VR connection is auto-reconnecting */
    val vrIsReconnecting: StateFlow<Boolean> = connectionRepository.vrIsReconnecting

    /** Scenarios for this session */
    val scenarios: StateFlow<List<ScenarioEntity>> = if (sessionId > 0) {
        scenarioRepository.getScenariosForSession(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        MutableStateFlow(emptyList())
    }

    /** Live eSense Pulse latest R-R interval (ms) */
    private val _pulseLatestRr = MutableStateFlow<Int?>(null)
    val pulseLatestRr: StateFlow<Int?> = _pulseLatestRr.asStateFlow()

    /** Recording UI state combining repository state with connection states */
    val recordingUiState: StateFlow<ScenarioRecordingUiState> = combine(
        sensorRecordingRepository.recordingState,
        sensorRecordingRepository.recordingDurationMs,
        sensorRecordingRepository.recordingMetadata,
        connectionRepository.bleConnectionState,
        connectionRepository.respirationState,
    ) { state, durationMs, metadata, bleState, respState ->
        val isHeartRateConnected = bleState == ConnectionState.CONNECTED
        val isRespirationConnected = respState == DeviceState.Streaming ||
                respState == DeviceState.Connected

        ScenarioRecordingUiState(
            recordingState = state,
            durationFormatted = formatDuration(durationMs),
            isHeartRateConnected = isHeartRateConnected,
            isRespirationConnected = isRespirationConnected,
            heartRateSampleCount = metadata?.heartRateSampleCount ?: 0,
            respirationSampleCount = metadata?.respirationSampleCount ?: 0,
            esenseRrIntervalSampleCount = metadata?.esenseRrIntervalSampleCount ?: 0,
            gsrSampleCount = metadata?.gsrSampleCount ?: 0,
            scenarioIdentifier = metadata?.scenarioIdentifier,
            isRecording = state == DataRecordingState.RECORDING,
            heartRateWasEnabled = metadata?.heartRateRecording ?: false,
            respirationWasEnabled = metadata?.respirationRecording ?: false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScenarioRecordingUiState())

    private var notesDebounceJob: Job? = null

    init {
        // Load session data
        if (sessionId > 0) {
            viewModelScope.launch {
                val session = sessionRepository.getSessionById(sessionId)
                _session.value = session
                _notes.value = session?.notes ?: ""
            }
        }

        // Start mDNS discovery for VR headset
        mdnsDiscovery.startDiscovery()

        // Unified BLE connection-state handler: dialog dismissal, HR-notification kickoff,
        // watchdog cancellation, and post-disconnect resets.
        viewModelScope.launch {
            connectionRepository.bleConnectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    if (_showBleScanDialog.value) {
                        _showBleScanDialog.value = false
                    }
                    connectionRepository.enableHeartRateNotifications()
                } else {
                    bleFirstDataJob?.cancel()
                    bleFirstDataJob = null
                    if (state == ConnectionState.DISCONNECTED) {
                        lowBatteryShownForConnection = false
                        _pulseLatestRr.value = null
                    }
                }
            }
        }

        // Legacy VR biofeedback bridge. Inert against the new logistics VR app (which
        // doesn't emit start_recording / stop_recording). Kept until the new scenario-
        // based protocol replaces it.
        viewModelScope.launch {
            vrWebSocketClient.messages.collect { message ->
                when (message) {
                    is WebSocketMessage.Event -> {
                        handleVrBiofeedbackEvent(
                            message.serverMessage.msg ?: "",
                            message.serverMessage.value
                        )
                    }
                    else -> { /* Ignore other message types */ }
                }
            }
        }

        // Clear legacy StressChamber scene lock when VR disconnects (safety valve)
        viewModelScope.launch {
            connectionRepository.vrConnectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                    connectionRepository.setStressChamberSceneActive(false)
                }
            }
        }

        // Collect BLE events for warnings (connection timeout, low battery, unexpected disconnection)
        viewModelScope.launch {
            connectionRepository.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.ConnectionTimeout -> {
                        _bleDialogState.value = BleDialogState.ConnectionTimeout(event.deviceName)
                    }
                    is BleEvent.BatteryLevelRead -> {
                        if (event.percent <= 30 && !lowBatteryShownForConnection) {
                            lowBatteryShownForConnection = true
                            _bleDialogState.value = BleDialogState.LowBattery(event.percent)
                        }
                    }
                    is BleEvent.UnexpectedDisconnection -> {
                        _bleDialogState.value = BleDialogState.UnexpectedDisconnection(
                            deviceName = event.deviceName,
                            reason = gattStatusToString(event.status)
                        )
                    }
                    is BleEvent.HeartRateNotificationEnabled -> {
                        startBleFirstDataTimeout()
                    }
                    else -> { /* no dialog */ }
                }
            }
        }

        // Cancel scan timeout when devices are found
        viewModelScope.launch {
            connectionRepository.bleDiscoveredDevices.collect { devices ->
                if (devices.isNotEmpty()) {
                    cancelScanTimeout()
                    _scanTimeoutReached.value = false
                }
            }
        }

        // Cancel scan timeout when scanning stops externally
        viewModelScope.launch {
            connectionRepository.bleIsScanning.collect { scanning ->
                if (!scanning) {
                    cancelScanTimeout()
                }
            }
        }

        // Auto-start scan when Bluetooth turns on while scan dialog is open
        viewModelScope.launch {
            connectionRepository.bluetoothEnabled.collect { enabled ->
                if (enabled && _showBleScanDialog.value && !connectionRepository.bleIsScanning.value) {
                    scanTimeoutShown = false
                    _scanTimeoutReached.value = false
                    connectionRepository.startBleScan()
                    startScanTimeout()
                }
            }
        }

        // Live R-R interval from eSense Pulse
        viewModelScope.launch {
            connectionRepository.bleRrIntervalSampleFlow.collect { rr ->
                _pulseLatestRr.value = rr.toInt()
            }
        }

        // Cancel first-data watchdog when first HR reading arrives
        viewModelScope.launch {
            connectionRepository.heartRate.collect { hr ->
                if (hr != null) {
                    bleFirstDataJob?.cancel()
                    bleFirstDataJob = null
                }
            }
        }
    }

    private fun handleVrBiofeedbackEvent(eventName: String, value: Int?) {
        @Suppress("UNUSED_PARAMETER") value
        when (eventName) {
            VR_EVENT_START_BIOFEEDBACK -> {
                _lastVrBiofeedbackEvent.value = VrBiofeedbackEvent(
                    type = VrBiofeedbackEventType.START,
                    timestamp = System.currentTimeMillis()
                )
                startRecordingFromVr()
            }
            VR_EVENT_STOP_BIOFEEDBACK -> {
                _lastVrBiofeedbackEvent.value = VrBiofeedbackEvent(
                    type = VrBiofeedbackEventType.STOP,
                    timestamp = System.currentTimeMillis()
                )
                stopRecordingFromVr()
            }
        }
    }

    private fun anySensorConnected(): Boolean {
        val bleConnected = connectionRepository.bleConnectionState.value == ConnectionState.CONNECTED
        val respState = connectionRepository.respirationState.value
        val respConnected = respState == DeviceState.Streaming || respState == DeviceState.Connected
        return bleConnected || respConnected
    }

    /**
     * Legacy code path: when an old-project VR build sends `start_recording`, create a
     * placeholder scenario and begin sensor capture. The new logistics VR app will
     * provide its own scenario_start / event / reaction / end protocol; this path is
     * inert against that app.
     */
    private fun startRecordingFromVr() {
        viewModelScope.launch {
            val session = _session.value ?: return@launch
            val currentState = sensorRecordingRepository.recordingState.value

            if (currentState == DataRecordingState.IDLE &&
                session.status == com.biometrix.operator.data.db.SessionStatus.ACTIVE &&
                anySensorConnected()
            ) {
                val scenario = scenarioRepository.createScenario(
                    sessionId = session.id,
                    scenarioCode = LEGACY_VR_PLACEHOLDER_SCENARIO
                )
                val scenarioIdentifier = "${session.sessionCode}-${LEGACY_VR_PLACEHOLDER_SCENARIO.officialCode}"
                _vrTriggeredRecording.value = true
                sensorRecordingRepository.startRecording(scenario.id, scenarioIdentifier)
            }
        }
    }

    private fun stopRecordingFromVr() {
        connectionRepository.setStressChamberSceneActive(false)
        viewModelScope.launch {
            val currentState = sensorRecordingRepository.recordingState.value

            if (currentState == DataRecordingState.RECORDING) {
                sensorRecordingRepository.stopRecording()
                _vrTriggeredRecording.value = false
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun setupNotesAutoSave() {
        notesDebounceJob?.cancel()
        notesDebounceJob = viewModelScope.launch {
            _notes
                .debounce(500)
                .distinctUntilChanged()
                .collect { text ->
                    if (sessionId > 0) {
                        sessionRepository.updateNotes(sessionId, text)
                        if (userHasEditedNotes) {
                            _notesSaveStatus.value = NotesSaveStatus.Saved
                            savedDismissJob?.cancel()
                            savedDismissJob = viewModelScope.launch {
                                delay(2000)
                                _notesSaveStatus.value = NotesSaveStatus.Idle
                            }
                        }
                    }
                }
        }
    }

    fun updateNotes(text: String) {
        userHasEditedNotes = true
        savedDismissJob?.cancel()
        _notesSaveStatus.value = NotesSaveStatus.Saving
        _notes.value = text
    }

    fun clearLastVrBiofeedbackEvent() {
        _lastVrBiofeedbackEvent.value = null
    }

    fun endSessionAndSave() {
        viewModelScope.launch {
            _isEndingSession.value = true
            try {
                // Stop recording if active
                if (sensorRecordingRepository.recordingState.value == DataRecordingState.RECORDING) {
                    sensorRecordingRepository.stopRecording()
                }

                sessionRepository.endSession(sessionId)

                _endSessionResult.value = EndSessionResult.Success(sessionId)
                vrWebSocketClient.suppressAutoReconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _endSessionResult.value = EndSessionResult.Error(e.message ?: "Failed to save session")
            } finally {
                _isEndingSession.value = false
            }
        }
    }

    fun discardSession() {
        viewModelScope.launch {
            if (sensorRecordingRepository.recordingState.value == DataRecordingState.RECORDING) {
                sensorRecordingRepository.stopRecording()
            }
            sessionRepository.deleteSession(sessionId)
        }
    }

    // --- Sensor connection methods ---

    fun onHeartRateCardClick() {
        val state = connectionRepository.bleConnectionState.value
        if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            if (!locationChecker.isLocationEnabled()) {
                _bleDialogState.value = BleDialogState.LocationServicesRequired
                return
            }
            scanTimeoutShown = false
            _scanTimeoutReached.value = false
            _showBleScanDialog.value = true
            if (connectionRepository.bluetoothEnabled.value) {
                connectionRepository.startBleScan()
                startScanTimeout()
            }
        }
    }

    fun dismissBleScanDialog() {
        _showBleScanDialog.value = false
        cancelScanTimeout()
        _scanTimeoutReached.value = false
        connectionRepository.stopBleScan()
    }

    fun connectToBleDevice(device: BleDevice) {
        cancelScanTimeout()
        _scanTimeoutReached.value = false
        connectionRepository.stopBleScan()
        connectionRepository.connectBleDevice(device)
        lastConnectedDevice = device
        _showBleScanDialog.value = false
    }

    fun onRespirationCardClick(context: Context) {
        val state = connectionRepository.respirationState.value
        if (state == DeviceState.Disconnected || state == DeviceState.Error) {
            connectionRepository.connectRespiration(context)
        }
    }

    fun dismissBleDialog() {
        _bleDialogState.value = null
    }

    fun onBleDialogAction(action: DialogAction) {
        when (action) {
            DialogAction.RetryConnection,
            DialogAction.Reconnect -> {
                dismissBleDialog()
                lastConnectedDevice?.let { connectToBleDevice(it) }
            }
            DialogAction.OpenLocationSettings,
            DialogAction.Dismiss -> dismissBleDialog()
        }
    }

    private fun startScanTimeout() {
        scanTimeoutJob?.cancel()
        _scanTimeoutReached.value = false
        scanTimeoutJob = viewModelScope.launch {
            delay(15_000L)
            if (connectionRepository.bleIsScanning.value &&
                connectionRepository.bleDiscoveredDevices.value.isEmpty() &&
                !scanTimeoutShown
            ) {
                scanTimeoutShown = true
                _scanTimeoutReached.value = true
            }
        }
    }

    private fun cancelScanTimeout() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
    }

    /** One-shot timer: starts after CCCD write succeeds; fires if first HR reading never arrives. */
    private fun startBleFirstDataTimeout() {
        bleFirstDataJob?.cancel()
        bleFirstDataJob = viewModelScope.launch {
            delay(10_000L)
            if (connectionRepository.bleConnectionState.value == ConnectionState.CONNECTED &&
                connectionRepository.heartRate.value == null
            ) {
                val deviceName = lastConnectedDevice?.displayName ?: "Sensor"
                _bleDialogState.value = BleDialogState.UnexpectedDisconnection(
                    deviceName = deviceName,
                    reason = "No sensor data received"
                )
                connectionRepository.disconnectBle()
            }
        }
    }

    // --- VR connection methods ---

    fun selectAndConnectVrDevice(device: DiscoveredVrDevice) {
        _selectedVrDevice.value = device
        mdnsDiscovery.stopDiscovery()
        vrWebSocketClient.connect(device.host)
    }

    fun rescanVrDevices() {
        vrWebSocketClient.disconnect()
        _selectedVrDevice.value = null
        mdnsDiscovery.startDiscovery()
    }

    fun disconnectVr() {
        vrWebSocketClient.disconnect()
        _selectedVrDevice.value = null
        mdnsDiscovery.startDiscovery()
    }

    fun sendTutorialCommand() {
        vrWebSocketClient.sendCommand(
            "trigger_event",
            mapOf("target" to "NCEvents", "eventName" to "StartTutorial")
        )
    }

    fun sendStartSceneCommand() {
        connectionRepository.setStressChamberSceneActive(true)
        vrWebSocketClient.sendCommand(
            "scene",
            mapOf("action" to "load", "sceneName" to "StressChamber")
        )
    }

    fun clearRespirationDisconnectReason() {
        connectionRepository.clearRespirationDisconnectReason()
    }

    fun clearEndSessionResult() {
        _endSessionResult.value = null
    }

    @Suppress("unused")
    private fun formatDurationStatic(ms: Long): String = formatDuration(ms)

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onCleared() {
        super.onCleared()
        mdnsDiscovery.stopDiscovery()
    }
}

sealed class EndSessionResult {
    data class Success(val sessionId: Long) : EndSessionResult()
    data class Error(val message: String) : EndSessionResult()
}

enum class VrBiofeedbackEventType {
    START, STOP
}

data class VrBiofeedbackEvent(
    val type: VrBiofeedbackEventType,
    val timestamp: Long
)
