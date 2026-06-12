package com.vitalwork.app.presentation.screens.sessions

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.recording.ScenarioRecordingRepository
import com.vitalwork.app.data.system.LocationChecker
import com.vitalwork.app.data.recording.model.DataRecordingState
import com.vitalwork.app.data.repository.ConnectionRepository
import com.vitalwork.app.data.sensor.audio.LowSignalWarning
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.repository.SessionRepository
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.ble.BleEvent
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.watch.WatchBatteryAlert
import com.vitalwork.app.data.sensor.watch.WatchBatteryThresholds
import com.vitalwork.app.data.sensor.watch.WatchFlushState
import com.vitalwork.app.data.sensor.watch.WatchLinkStatus
import com.vitalwork.app.data.vr.VrEvent
import com.vitalwork.app.data.vr.VrEventReceiver
import com.vitalwork.app.presentation.components.BleDialogState
import com.vitalwork.app.presentation.components.DialogAction
import com.vitalwork.app.presentation.components.gattStatusToString
import com.vitalwork.app.presentation.screens.sessions.components.NotesSaveStatus
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val readinessChecker: com.vitalwork.app.data.system.SystemReadinessChecker,
    private val watchCommandSender: com.vitalwork.app.data.sensor.watch.WatchCommandSender,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * Session prerequisites currently missing, for the backup readiness banner. The BLE entry is
     * driven by [blePermissionsGranted] (the screen's existing source of truth) so the two never
     * disagree; the other three are re-derived from live OS state via [refreshReadiness] on resume.
     */
    private val _missingPrerequisites =
        MutableStateFlow<Set<com.vitalwork.app.data.system.SessionPrerequisite>>(emptySet())
    val missingPrerequisites: StateFlow<Set<com.vitalwork.app.data.system.SessionPrerequisite>> =
        _missingPrerequisites.asStateFlow()

    private var readinessRefreshJob: Job? = null

    private fun computeMissingPrerequisites(): Set<com.vitalwork.app.data.system.SessionPrerequisite> {
        val live = readinessChecker.missingPrerequisites().toMutableSet()
        // Override the BLE entry with the screen's own grant state for consistency.
        val ble = com.vitalwork.app.data.system.SessionPrerequisite.BLUETOOTH
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
     * Drives the End-Session dialog state machine: prompt to wake the watch (if dozing/gone) → show a
     * "Receiving data from watch…" spinner while the durable store flushes → green check → auto-finalize.
     * The watch store is only truncated (`FLUSH_ACK`) after the data is persisted, so nothing is lost
     * to a slow flush, and the flow can never hang (timeout → [EndSessionPhase.Failed] with escapes).
     */
    private val _endSessionPhase = MutableStateFlow<EndSessionPhase>(EndSessionPhase.Idle)
    val endSessionPhase: StateFlow<EndSessionPhase> = _endSessionPhase.asStateFlow()

    /** Operator chose "End without watch data": abort the wait and finalize with what's recorded. */
    private val _endWithoutWatch = MutableStateFlow(false)

    /** The single in-flight End-Session coroutine (guards against double-taps / re-entry). */
    private var endSessionJob: Job? = null

    /** Session ending state (drives the End-Session button spinner / disabled state). */
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
                session.status == com.vitalwork.app.data.db.SessionStatus.ACTIVE &&
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
            if (session.status != com.vitalwork.app.data.db.SessionStatus.ACTIVE) return@launch
            if (!anySensorConnected()) return@launch

            val scenarioCode = com.vitalwork.app.data.db.ScenarioCode.FALLING_PALLET
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
     * Entry point for the End-Session button. Runs the watch-aware finalize handshake (see
     * [runEndSession]). Re-entrant taps are ignored while a finalize is already in flight.
     */
    fun requestEndSession() {
        if (endSessionJob?.isActive == true) return
        endSessionJob = viewModelScope.launch { runEndSession() }
    }

    /** Kept as the public name some callers/tests use; identical to [requestEndSession]. */
    fun endSessionAndSave() = requestEndSession()

    /**
     * Operator chose "End without watch data" from the wake/transfer dialog. If the finalize coroutine
     * is waiting (for the watch to wake or the flush to complete), unblock it so it finalizes with what
     * is already recorded; if we're at a terminal [EndSessionPhase.Failed], start the finalize directly.
     * Either way **no `FLUSH_ACK` is sent**, so the watch keeps its stored data for a later session.
     */
    fun endWithoutWatchData() {
        if (endSessionJob?.isActive == true) {
            _endWithoutWatch.value = true
        } else {
            endSessionJob = viewModelScope.launch { wrapFinalize(ackThroughWatchTs = null) }
        }
    }

    /** Retry the whole handshake from a failed/timed-out state (e.g. after waking the watch). */
    fun retryWatchTransfer() {
        if (endSessionJob?.isActive == true) return
        endSessionJob = viewModelScope.launch { runEndSession() }
    }

    private suspend fun runEndSession() {
        _isEndingSession.value = true
        _endWithoutWatch.value = false
        try {
            // Stop recording if active — this sets the last scenario's endedAt, closing its window.
            if (sensorRecordingRepository.recordingState.value == DataRecordingState.RECORDING) {
                sensorRecordingRepository.stopRecording()
            }

            // No watch this session → nothing to transfer; finalize straight away.
            val watchWasInUse = connectionRepository.watchBatteryLevel.value != null
            if (!watchWasInUse) {
                finalize(ackThroughWatchTs = null)
                return
            }

            // If the watch is dozing/gone, prompt the operator to wake it and wait until it's LIVE
            // (or they choose to end without it). The phone can't reliably auto-wake a deeply-dozing
            // watch, so a manual tap on the wrist is the dependable trigger.
            if (connectionRepository.watchLinkStatus.value != WatchLinkStatus.LIVE) {
                _endSessionPhase.value = EndSessionPhase.AwaitingWatchWake
                when (awaitWatchWake()) {
                    WakeOutcome.ABORTED -> { finalize(ackThroughWatchTs = null); return }
                    WakeOutcome.TIMED_OUT -> {
                        _endSessionPhase.value = EndSessionPhase.Failed("The watch didn't come online.")
                        return
                    }
                    WakeOutcome.LIVE -> { /* fall through to transfer */ }
                }
            }

            transferAndFinalize()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _endSessionResult.value = EndSessionResult.Error(e.message ?: "Failed to save session")
            _endSessionPhase.value = EndSessionPhase.Idle
        } finally {
            _isEndingSession.value = false
        }
    }

    private enum class WakeOutcome { LIVE, ABORTED, TIMED_OUT }

    /** Suspend until the watch is LIVE, the operator aborts, or [WATCH_WAKE_TIMEOUT_MS] elapses. */
    private suspend fun awaitWatchWake(): WakeOutcome {
        val outcome = withTimeoutOrNull(WATCH_WAKE_TIMEOUT_MS) {
            merge(
                connectionRepository.watchLinkStatus
                    .filter { it == WatchLinkStatus.LIVE }
                    .map { WakeOutcome.LIVE },
                _endWithoutWatch.filter { it }.map { WakeOutcome.ABORTED }
            ).first()
        }
        return outcome ?: WakeOutcome.TIMED_OUT
    }

    /**
     * Send `FLUSH`, show the "Receiving data from watch…" spinner while the store streams in, and wait
     * for the watch's `FLUSH_COMPLETE` (or the operator's abort, or a timeout) before finalizing.
     */
    private suspend fun transferAndFinalize() {
        connectionRepository.beginWatchFlush()
        runCatching { watchCommandSender.sendFlush() }
        _endSessionPhase.value = EndSessionPhase.Transferring(received = 0, expected = null)

        val end = withTimeoutOrNull(WATCH_TRANSFER_TIMEOUT_MS) {
            merge(
                connectionRepository.watchFlushState
                    .onEach { st ->
                        if (st is WatchFlushState.InProgress) {
                            _endSessionPhase.value =
                                EndSessionPhase.Transferring(st.received, st.expected)
                        }
                    }
                    .filterIsInstance<WatchFlushState.Complete>()
                    .map { TransferEnd.Completed(it.maxWatchTimestampMs) },
                _endWithoutWatch.filter { it }.map { TransferEnd.Aborted }
            ).first()
        }

        when (end) {
            is TransferEnd.Completed -> finalize(ackThroughWatchTs = end.maxWatchTimestampMs)
            TransferEnd.Aborted -> finalize(ackThroughWatchTs = null)
            null -> _endSessionPhase.value =
                EndSessionPhase.Failed("Couldn't receive all data from the watch.")
        }
    }

    private sealed interface TransferEnd {
        data class Completed(val maxWatchTimestampMs: Long?) : TransferEnd
        data object Aborted : TransferEnd
    }

    /**
     * Persist the watch buffer split by scenario window, then — only if data was actually received —
     * `FLUSH_ACK` the watch (which truncates its store), then end the session. Acking strictly after
     * the drain is what makes a slow/partial flush non-destructive.
     */
    private suspend fun finalize(ackThroughWatchTs: Long?) {
        _endSessionPhase.value = EndSessionPhase.Finalizing
        val scenarios = scenarioRepository.getScenariosForSessionOnce(sessionId)
        sensorRecordingRepository.drainAndFinalizeWatchEda(scenarios)
        if (ackThroughWatchTs != null) {
            runCatching { watchCommandSender.sendFlushAck(ackThroughWatchTs) }
        }
        sessionRepository.endSession(sessionId)
        _endSessionPhase.value = EndSessionPhase.Complete(sessionId)
    }

    /** [finalize] wrapped with the standard ending-session state guards (for the direct-call path). */
    private suspend fun wrapFinalize(ackThroughWatchTs: Long?) {
        _isEndingSession.value = true
        try {
            finalize(ackThroughWatchTs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _endSessionResult.value = EndSessionResult.Error(e.message ?: "Failed to save session")
            _endSessionPhase.value = EndSessionPhase.Idle
        } finally {
            _isEndingSession.value = false
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
        /** How long to wait for the operator to wake a dozing/disconnected watch before giving up. */
        const val WATCH_WAKE_TIMEOUT_MS = 60_000L

        /** How long to wait for the woken watch to flush its whole store before declaring failure. */
        const val WATCH_TRANSFER_TIMEOUT_MS = 30_000L
    }
}

sealed class EndSessionResult {
    data class Success(val sessionId: Long) : EndSessionResult()
    data class Error(val message: String) : EndSessionResult()
}

/**
 * Drives the End-Session dialog. See [SessionControlViewModel.endSessionPhase].
 *  - [Idle]: no end in progress (no dialog).
 *  - [AwaitingWatchWake]: prompt the operator to wake the watch so it can transfer its stored data.
 *  - [Transferring]: `FLUSH` sent; receiving the store (`received` of `expected` chunks).
 *  - [Finalizing]: persisting + splitting by scenario window, then ending the session.
 *  - [Complete]: all done — show the green check, then navigate to review for [sessionId].
 *  - [Failed]: the watch never woke / the transfer didn't finish; offer Retry or End-without-watch-data.
 */
sealed interface EndSessionPhase {
    data object Idle : EndSessionPhase
    data object AwaitingWatchWake : EndSessionPhase
    data class Transferring(val received: Int, val expected: Int?) : EndSessionPhase
    data object Finalizing : EndSessionPhase
    data class Complete(val sessionId: Long) : EndSessionPhase
    data class Failed(val reason: String) : EndSessionPhase
}
