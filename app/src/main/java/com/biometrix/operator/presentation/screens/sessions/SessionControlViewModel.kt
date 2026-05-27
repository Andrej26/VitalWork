package com.biometrix.operator.presentation.screens.sessions

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.SensorRecordingRepository
import com.biometrix.operator.data.system.LocationChecker
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.sensor.audio.LowSignalWarning
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.SudsRepository
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
    private val sensorRecordingRepository: SensorRecordingRepository,
    private val sessionRepository: SessionRepository,
    private val recordingRepository: RecordingRepository,
    private val sudsRepository: SudsRepository,
    private val vrWebSocketClient: VRConnectionManager,
    private val mdnsDiscovery: VrDeviceDiscovery,
    private val locationChecker: LocationChecker,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val VR_EVENT_START_BIOFEEDBACK = "start_recording"
        private const val VR_EVENT_STOP_BIOFEEDBACK = "stop_recording"
        private const val VR_EVENT_SUDS = "suds"
    }

    val sessionId: Long = savedStateHandle.get<Long>("testId") ?: -1L

    private val _test = MutableStateFlow<SessionEntity?>(null)
    val test: StateFlow<SessionEntity?> = _test.asStateFlow()

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

    /** Test ending state */
    private val _isEndingTest = MutableStateFlow(false)
    val isEndingTest: StateFlow<Boolean> = _isEndingTest.asStateFlow()

    private val _endTestResult = MutableStateFlow<EndTestResult?>(null)
    val endTestResult: StateFlow<EndTestResult?> = _endTestResult.asStateFlow()

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

    /** Whether the current recording was triggered by VR biofeedback command */
    private val _vrTriggeredRecording = MutableStateFlow(false)
    val vrTriggeredRecording: StateFlow<Boolean> = _vrTriggeredRecording.asStateFlow()

    /** Latest VR biofeedback event for UI feedback */
    private val _lastVrBiofeedbackEvent = MutableStateFlow<VrBiofeedbackEvent?>(null)
    val lastVrBiofeedbackEvent: StateFlow<VrBiofeedbackEvent?> = _lastVrBiofeedbackEvent.asStateFlow()

    /** Whether the StressChamber scene is currently active (shared via ConnectionRepository) */
    val isStressChamberSceneActive: StateFlow<Boolean> = connectionRepository.isStressChamberSceneActive

    /** SUDS events received in the current StressChamber session (unlock triggers on 3rd) */
    private var sudsCountInSession = 0

    /** True while tutorial is active — SUDS received in this window are not saved */
    private var isTutorialActive = false

    /** Last SUDs value received from VR during this session */
    private val _lastSudsValue = MutableStateFlow<Int?>(null)
    val lastSudsValue: StateFlow<Int?> = _lastSudsValue.asStateFlow()

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

    /** Recordings for this test */
    val recordings: StateFlow<List<RecordingEntity>> = if (sessionId > 0) {
        recordingRepository.getRecordingsForTest(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        MutableStateFlow(emptyList())
    }

    /** Live eSense Pulse latest R-R interval (ms) */
    private val _pulseLatestRr = MutableStateFlow<Int?>(null)
    val pulseLatestRr: StateFlow<Int?> = _pulseLatestRr.asStateFlow()

    /** Recording UI state combining repository state with connection states */
    val recordingUiState: StateFlow<RecordingUiState> = combine(
        sensorRecordingRepository.recordingState,
        sensorRecordingRepository.recordingDurationMs,
        sensorRecordingRepository.recordingMetadata,
        connectionRepository.bleConnectionState,
        connectionRepository.respirationState,
    ) { state, durationMs, metadata, bleState, respState ->
        val isHeartRateConnected = bleState == ConnectionState.CONNECTED
        val isRespirationConnected = respState == DeviceState.Streaming ||
                respState == DeviceState.Connected

        RecordingUiState(
            recordingState = state,
            durationFormatted = formatDuration(durationMs),
            isHeartRateConnected = isHeartRateConnected,
            isRespirationConnected = isRespirationConnected,
            heartRateSampleCount = metadata?.heartRateSampleCount ?: 0,
            respirationSampleCount = metadata?.respirationSampleCount ?: 0,
            esenseRrIntervalSampleCount = metadata?.esenseRrIntervalSampleCount ?: 0,
            recordingIdentifier = metadata?.recordingIdentifier,
            isRecording = state == DataRecordingState.RECORDING,
            heartRateWasEnabled = metadata?.heartRateRecording ?: false,
            respirationWasEnabled = metadata?.respirationRecording ?: false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingUiState())

    private var notesDebounceJob: Job? = null

    init {
        // Load test data
        if (sessionId > 0) {
            viewModelScope.launch {
                val test = sessionRepository.getSessionById(sessionId)
                _test.value = test
                _notes.value = test?.notes ?: ""
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

        // Listen for VR biofeedback commands to auto-start/stop recording
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

        // Clear StressChamber scene lock when VR disconnects (safety valve)
        viewModelScope.launch {
            connectionRepository.vrConnectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                    isTutorialActive = false
                    connectionRepository.setStressChamberSceneActive(false)
                    sudsCountInSession = 0
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
            VR_EVENT_SUDS -> {
                val sudsValue = value ?: return
                if (isTutorialActive) return
                if (connectionRepository.isStressChamberSceneActive.value) {
                    sudsCountInSession++
                    if (sudsCountInSession >= 3) {
                        connectionRepository.setStressChamberSceneActive(false)
                        sudsCountInSession = 0
                    }
                }
                viewModelScope.launch {
                    val test = _test.value ?: return@launch
                    sudsRepository.saveEvent(test.id, sudsValue)
                    _lastSudsValue.value = sudsValue
                }
            }
        }
    }

    private fun anySensorConnected(): Boolean {
        val bleConnected = connectionRepository.bleConnectionState.value == ConnectionState.CONNECTED
        val respState = connectionRepository.respirationState.value
        val respConnected = respState == DeviceState.Streaming || respState == DeviceState.Connected
        return bleConnected || respConnected
    }

    private fun startRecordingFromVr() {
        isTutorialActive = false
        viewModelScope.launch {
            val test = _test.value ?: return@launch
            val currentState = sensorRecordingRepository.recordingState.value

            if (currentState == DataRecordingState.IDLE &&
                test.status == com.biometrix.operator.data.db.SessionStatus.ACTIVE &&
                anySensorConnected()
            ) {
                _vrTriggeredRecording.value = true
                sensorRecordingRepository.startRecording(test.id, test.sessionIdentifier)
            }
        }
    }

    private fun stopRecordingFromVr() {
        connectionRepository.setStressChamberSceneActive(false)
        sudsCountInSession = 0
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

    fun endTestAndSave() {
        viewModelScope.launch {
            _isEndingTest.value = true
            try {
                // Stop recording if active
                if (sensorRecordingRepository.recordingState.value == DataRecordingState.RECORDING) {
                    sensorRecordingRepository.stopRecording()
                }

                val recordingCount = sessionRepository.getCompletedRecordingCount(sessionId)
                sessionRepository.endSession(sessionId, recordingCount)

                _endTestResult.value = EndTestResult.Success(sessionId)
                vrWebSocketClient.suppressAutoReconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _endTestResult.value = EndTestResult.Error(e.message ?: "Failed to save test")
            } finally {
                _isEndingTest.value = false
            }
        }
    }

    fun discardTest() {
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
        isTutorialActive = true
        vrWebSocketClient.sendCommand(
            "trigger_event",
            mapOf("target" to "NCEvents", "eventName" to "StartTutorial")
        )
    }

    fun sendStartSceneCommand() {
        isTutorialActive = false
        sudsCountInSession = 0
        connectionRepository.setStressChamberSceneActive(true)
        vrWebSocketClient.sendCommand(
            "scene",
            mapOf("action" to "load", "sceneName" to "StressChamber")
        )
    }

    fun clearRespirationDisconnectReason() {
        connectionRepository.clearRespirationDisconnectReason()
    }

    fun clearEndTestResult() {
        _endTestResult.value = null
    }

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

sealed class EndTestResult {
    data class Success(val sessionId: Long) : EndTestResult()
    data class Error(val message: String) : EndTestResult()
}

enum class VrBiofeedbackEventType {
    START, STOP
}

data class VrBiofeedbackEvent(
    val type: VrBiofeedbackEventType,
    val timestamp: Long
)
