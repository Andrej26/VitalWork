package com.biometrix.operator.presentation.screens.sessions

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.biometrix.operator.data.sensor.watch.WatchBatteryAlert
import com.biometrix.operator.data.sensor.watch.WatchBatteryThresholds
import com.biometrix.operator.data.vr.VrEvent
import com.biometrix.operator.data.vr.VrEventReceiver
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
import kotlinx.coroutines.flow.map
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
    private val vrEventReceiver: VrEventReceiver,
    private val locationChecker: LocationChecker,
    private val readinessChecker: com.biometrix.operator.data.system.SystemReadinessChecker,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * Session prerequisites currently missing, for the backup readiness banner. The BLE entry is
     * driven by [blePermissionsGranted] (the screen's existing source of truth) so the two never
     * disagree; the other three are re-derived from live OS state via [refreshReadiness] on resume.
     */
    private val _missingPrerequisites =
        MutableStateFlow<Set<com.biometrix.operator.data.system.SessionPrerequisite>>(emptySet())
    val missingPrerequisites: StateFlow<Set<com.biometrix.operator.data.system.SessionPrerequisite>> =
        _missingPrerequisites.asStateFlow()

    private var readinessRefreshJob: Job? = null

    private fun computeMissingPrerequisites(): Set<com.biometrix.operator.data.system.SessionPrerequisite> {
        val live = readinessChecker.missingPrerequisites().toMutableSet()
        // Override the BLE entry with the screen's own grant state for consistency.
        val ble = com.biometrix.operator.data.system.SessionPrerequisite.BLUETOOTH
        if (_blePermissionsGranted.value) live.remove(ble) else live.add(ble)
        return live
    }

    /**
     * Re-derive readiness now, then re-check a couple of times over the next second. Some systems
     * (notably MIUI's battery-optimization flow) report the new value with a short lag after the
     * user confirms and returns, which previously left the banner stale until another tap.
     */
    fun refreshReadiness() {
        _missingPrerequisites.value = computeMissingPrerequisites()
        readinessRefreshJob?.cancel()
        readinessRefreshJob = viewModelScope.launch {
            for (delayMs in longArrayOf(350L, 800L)) {
                delay(delayMs)
                _missingPrerequisites.value = computeMissingPrerequisites()
            }
        }
    }

    val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _session = MutableStateFlow<SessionEntity?>(null)
    val session: StateFlow<SessionEntity?> = _session.asStateFlow()

    /** VR headset connection state (inferred from time-since-last-event) */
    val vrConnectionState: StateFlow<ConnectionState> = connectionRepository.vrConnectionState

    /** BLE sensor (eSense Pulse) connection state */
    val bleConnectionState: StateFlow<ConnectionState> = connectionRepository.bleConnectionState

    /** Audio sensor (eSense Respiration) state */
    val respirationState: StateFlow<DeviceState> = connectionRepository.respirationState

    /** Galaxy Watch (Data Layer) connection state */
    val watchConnectionState: StateFlow<ConnectionState> = connectionRepository.watchConnectionState

    /** Latest Galaxy Watch EDA value (µS), null until first reading */
    val watchEda: StateFlow<Float?> = connectionRepository.watchEda

    /** Galaxy Watch battery level (0-100), null until first reading */
    val watchBatteryLevel: StateFlow<Int?> = connectionRepository.watchBatteryLevel

    /**
     * Low-battery alert tier for the Galaxy Watch, derived from its last-known battery level so the
     * operator is warned before starting a long session on a dying watch (CRITICAL wins over WARNING).
     */
    val watchBatteryAlert: StateFlow<WatchBatteryAlert> = connectionRepository.watchBatteryLevel
        .map { level ->
            when {
                level == null -> WatchBatteryAlert.NONE
                level <= WatchBatteryThresholds.CRITICAL_PCT -> WatchBatteryAlert.CRITICAL
                level <= WatchBatteryThresholds.WARNING_PCT -> WatchBatteryAlert.WARNING
                else -> WatchBatteryAlert.NONE
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WatchBatteryAlert.NONE)

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

    /**
     * Shown when the operator hits End Session while the Galaxy Watch link is down. The watch may
     * still hold sleep-buffered EDA it hasn't delivered; prompting the operator to wake it (tap its
     * screen / turn Bluetooth on) lets that buffer flush before we finalize. Purely advisory — the
     * operator can always end anyway.
     */
    private val _showWatchRecoveryDialog = MutableStateFlow(false)
    val showWatchRecoveryDialog: StateFlow<Boolean> = _showWatchRecoveryDialog.asStateFlow()

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
        refreshReadiness()
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

    /** Whether the current recording was triggered (auto-started) by a VR scenario_start event */
    private val _vrTriggeredRecording = MutableStateFlow(false)
    val vrTriggeredRecording: StateFlow<Boolean> = _vrTriggeredRecording.asStateFlow()

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
            edaSampleCount = metadata?.edaSampleCount ?: 0,
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
            // Begin continuous Galaxy Watch EDA capture for the whole session. Idempotent: the watch
            // streams independently of scenarios and buffers in Doze, so we accumulate session-wide
            // and slice into scenarios at End Session. Safe even if no watch is connected.
            sensorRecordingRepository.startWatchEdaSession()
        }

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

        // VR scenario lifecycle from the Quest (HTTP events via VrEventReceiver). The receiver
        // writes the stop event's event/reaction timestamps itself (ack-after-write) before
        // emitting. Gating preserved: session ACTIVE + a sensor connected.
        viewModelScope.launch {
            vrEventReceiver.events.collect { event ->
                when (event) {
                    is VrEvent.ScenarioStart -> handleVrScenarioStart(event)
                    is VrEvent.ScenarioStop -> handleVrScenarioStop()
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

    private fun anySensorConnected(): Boolean {
        val bleConnected = connectionRepository.bleConnectionState.value == ConnectionState.CONNECTED
        val respState = connectionRepository.respirationState.value
        val respConnected = respState == DeviceState.Streaming || respState == DeviceState.Connected
        val watchConnected = connectionRepository.watchConnectionState.value == ConnectionState.CONNECTED
        return bleConnected || respConnected || watchConnected
    }

    /**
     * VR `scenario_start`: create the scenario row for the real [ScenarioCode] the Quest sent,
     * register it with the receiver (so subsequent event/reaction POSTs resolve to it), and begin
     * sensor capture. Gated on an ACTIVE session with at least one sensor connected.
     */
    private fun handleVrScenarioStart(event: VrEvent.ScenarioStart) {
        viewModelScope.launch {
            val session = _session.value ?: return@launch
            val currentState = sensorRecordingRepository.recordingState.value

            if (currentState == DataRecordingState.IDLE &&
                session.status == com.biometrix.operator.data.db.SessionStatus.ACTIVE &&
                anySensorConnected()
            ) {
                val scenario = scenarioRepository.createScenario(
                    sessionId = session.id,
                    scenarioCode = event.code
                )
                vrEventReceiver.setActiveScenario(session.id, scenario.id, event.code)
                val scenarioIdentifier = "${session.sessionCode}-${event.code.officialCode}"
                _vrTriggeredRecording.value = true
                sensorRecordingRepository.startRecording(scenario.id, scenarioIdentifier)
            }
        }
    }

    /**
     * Manual test start: spin up a scenario + recording exactly the way a VR `scenario_start` would,
     * so the operator can exercise capture on the phone before the VR link is wired up. Uses a fixed
     * test scenario code. Same gating as VR: ACTIVE session with at least one sensor connected.
     *
     * TODO(remove once VR drives recording): test-only manual control.
     */
    fun startManualRecording() {
        viewModelScope.launch {
            val session = _session.value ?: return@launch
            if (sensorRecordingRepository.recordingState.value != DataRecordingState.IDLE) return@launch
            if (session.status != com.biometrix.operator.data.db.SessionStatus.ACTIVE) return@launch
            if (!anySensorConnected()) return@launch

            val scenarioCode = com.biometrix.operator.data.db.ScenarioCode.FALLING_PALLET
            val scenario = scenarioRepository.createScenario(
                sessionId = session.id,
                scenarioCode = scenarioCode
            )
            vrEventReceiver.setActiveScenario(session.id, scenario.id, scenarioCode)
            val scenarioIdentifier = "${session.sessionCode}-${scenarioCode.officialCode}"
            _vrTriggeredRecording.value = false
            sensorRecordingRepository.startRecording(scenario.id, scenarioIdentifier)
        }
    }

    /** Manual test stop: mirrors a VR `scenario_stop`. TODO(remove once VR drives recording). */
    fun stopManualRecording() = handleVrScenarioStop()

    /** VR `scenario_stop`: stop sensor capture, finalize the scenario row, clear the active mirror. */
    private fun handleVrScenarioStop() {
        viewModelScope.launch {
            if (sensorRecordingRepository.recordingState.value == DataRecordingState.RECORDING) {
                sensorRecordingRepository.stopRecording()
            }
            vrEventReceiver.clearActiveScenario()
            _vrTriggeredRecording.value = false
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

    /**
     * Entry point for the End-Session button. If a Galaxy Watch was in use this session but its link
     * is currently down, show the recovery dialog first (the watch may hold undelivered sleep-buffered
     * EDA). Otherwise finalize immediately.
     */
    fun requestEndSession() {
        val watchWasInUse = connectionRepository.watchBatteryLevel.value != null
        val watchDown = watchConnectionState.value != ConnectionState.CONNECTED
        if (watchWasInUse && watchDown) {
            _showWatchRecoveryDialog.value = true
        } else {
            endSessionAndSave()
        }
    }

    /** Operator dismissed the recovery dialog by choosing to finalize regardless. */
    fun confirmEndSessionAnyway() {
        _showWatchRecoveryDialog.value = false
        endSessionAndSave()
    }

    /** Operator cancelled End Session from the recovery dialog (e.g. to go wake the watch). */
    fun dismissWatchRecoveryDialog() {
        _showWatchRecoveryDialog.value = false
    }

    fun endSessionAndSave() {
        viewModelScope.launch {
            _isEndingSession.value = true
            try {
                // Stop recording if active — this sets the last scenario's endedAt, closing its window.
                if (sensorRecordingRepository.recordingState.value == DataRecordingState.RECORDING) {
                    sensorRecordingRepository.stopRecording()
                }

                // If the watch reconnected, give its sleep-buffered EDA a brief moment to flush in
                // before we slice — bounded so a dead/absent watch never hangs End Session.
                if (watchConnectionState.value == ConnectionState.CONNECTED) {
                    delay(WATCH_FLUSH_GRACE_MS)
                }

                // Drain the session-long watch EDA buffer into scenarios BY TIMESTAMP WINDOW, before
                // endSession rolls up the per-type sample counts. Scenarios now all have endedAt set.
                val scenarios = scenarioRepository.getScenariosForSessionOnce(sessionId)
                sensorRecordingRepository.drainAndFinalizeWatchEda(scenarios)

                sessionRepository.endSession(sessionId)

                _endSessionResult.value = EndSessionResult.Success(sessionId)
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
            // Tear down the watch EDA session without persisting (empty windows → nothing written,
            // collector cancelled, buffers cleared) before the session is deleted.
            sensorRecordingRepository.drainAndFinalizeWatchEda(emptyList())
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

    private companion object {
        /** Bounded wait for a just-reconnected watch to flush its sleep buffer before slicing. */
        const val WATCH_FLUSH_GRACE_MS = 2_000L
    }
}

sealed class EndSessionResult {
    data class Success(val sessionId: Long) : EndSessionResult()
    data class Error(val message: String) : EndSessionResult()
}
