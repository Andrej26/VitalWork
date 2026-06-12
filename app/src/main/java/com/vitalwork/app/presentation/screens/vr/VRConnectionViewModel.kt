package com.vitalwork.app.presentation.screens.vr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.network.NetworkChecker
import com.vitalwork.app.data.repository.SessionRepository
import com.vitalwork.app.data.vr.VrEventReceiver
import com.vitalwork.app.data.vr.VrLinkLog
import com.vitalwork.app.data.vr.VrLinkManager
import com.vitalwork.app.data.vr.VrPairingManager
import com.vitalwork.app.presentation.log.LogEntry
import com.vitalwork.app.presentation.log.LogType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Read-only diagnostics for the VR link. Since the tablet is now the HTTP **server** (the Quest
 * connects to us and POSTs scenario events), there is nothing to dial out to or command from here.
 * This screen shows the tablet's address (to read to the VR colleague), the inferred connection
 * state, and a live log of received VR events.
 */
data class VRConnectionUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val tabletIpAddress: String? = null,
    val httpPort: Int = 8080,
    val pairingState: VrPairingManager.PairingState = VrPairingManager.PairingState.UNPAIRED,
    val candidate: VrPairingManager.VrCandidate? = null,
    /**
     * Whether the current bond has *ever* been CONNECTED (a heartbeat arrived). Lets the UI tell
     * "just bonded, waiting for the first heartbeat" (normal) apart from "was connected, now lost"
     * (a real warning) — without it, every fresh bond flashes the red "connection lost" card in the
     * gap before the first heartbeat. Reset whenever the bond drops.
     */
    val everConnectedSinceBond: Boolean = false,
    /** Whether the app-scoped VR link is running. Drives the Start/Stop button + pairing card copy. */
    val linkActive: Boolean = false,
    /**
     * Whether a session is currently ACTIVE. Stop is disabled while one runs — stopping mid-session
     * would clear the bond the recording depends on, so the operator must end the session first.
     */
    val sessionActive: Boolean = false,
    val logEntries: List<LogEntry> = emptyList()
)

@HiltViewModel
class VRConnectionViewModel @Inject constructor(
    private val vrEventReceiver: VrEventReceiver,
    private val vrPairingManager: VrPairingManager,
    private val vrLinkManager: VrLinkManager,
    private val vrLinkLog: VrLinkLog,
    private val sessionRepository: SessionRepository,
    private val networkChecker: NetworkChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VRConnectionUiState(tabletIpAddress = networkChecker.localIpv4())
    )
    val uiState: StateFlow<VRConnectionUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        observeConnectionState()
        observePairing()
        observeLog()
        observeLinkActive()
        observeSessionActive()
    }

    /**
     * Start the app-scoped VR link if it isn't already running. Unlike the old screen-scoped hold,
     * this is NOT released when the operator leaves VR Control — the link persists across the whole
     * app until [stopVrLink] (the Stop button). Idempotent, so re-entering the screen is harmless.
     */
    fun startVrLink() {
        vrLinkManager.start()
    }

    /**
     * Operator tapped Stop: cleanly disconnect the VR link before quitting the headset app, so the
     * tablet doesn't pop a false "connection lost" warning when heartbeats cease. Ignored while a
     * session is active (the UI also disables the button) — stopping then would clear the bond the
     * recording relies on.
     */
    fun stopVrLink() {
        if (_uiState.value.sessionActive) return
        vrLinkManager.stop()
    }

    private fun observeLinkActive() {
        viewModelScope.launch {
            vrLinkManager.active.collect { active ->
                _uiState.update { it.copy(linkActive = active) }
            }
        }
    }

    private fun observeSessionActive() {
        viewModelScope.launch {
            sessionRepository.activeSession.collect { session ->
                _uiState.update { it.copy(sessionActive = session != null) }
            }
        }
    }

    /**
     * Operator tapped Connect: bond to the candidate Quest. The bond reply (telling the Quest where
     * to POST) is sent by [VrLinkManager]'s pairing observer, which runs whenever the link is up.
     */
    fun confirmPairing() {
        vrPairingManager.confirm()
    }

    private fun observePairing() {
        viewModelScope.launch {
            vrPairingManager.pairingState.collect { state ->
                _uiState.update {
                    // Leaving BONDED (unpair / heartbeat-loss re-arm) clears the "ever connected"
                    // memory so the next bond starts in the neutral "connecting" state, not "lost".
                    val ever = if (state == VrPairingManager.PairingState.BONDED) it.everConnectedSinceBond else false
                    it.copy(pairingState = state, everConnectedSinceBond = ever)
                }
            }
        }
        viewModelScope.launch {
            vrPairingManager.candidate.collect { candidate ->
                _uiState.update { it.copy(candidate = candidate) }
            }
        }
    }

    private fun observeConnectionState() {
        // The headset indicator tracks heartbeat liveness (stays connected through quiet scenarios),
        // matching the app-wide VR status in ConnectionRepository.
        viewModelScope.launch {
            vrEventReceiver.heartbeatState.collect { state ->
                _uiState.update {
                    it.copy(
                        connectionState = state,
                        // Latch the first successful heartbeat for this bond; only after this does a
                        // later DISCONNECTED mean a genuine "connection lost".
                        everConnectedSinceBond = it.everConnectedSinceBond || state == ConnectionState.CONNECTED
                    )
                }
            }
        }
    }

    /**
     * Mirror the shared [VrLinkLog] into the UI. The whole VR link writes to it (pairing handshake,
     * heartbeats, scenario events, rejections), so the event log shows the full story even across
     * leaving and re-entering this screen — newest first.
     */
    private fun observeLog() {
        viewModelScope.launch {
            vrLinkLog.entries.collect { entries ->
                val mapped = entries.asReversed().map { it.toLogEntry() }
                _uiState.update { it.copy(logEntries = mapped) }
            }
        }
    }

    private fun VrLinkLog.Entry.toLogEntry(): LogEntry = LogEntry(
        timestamp = timeFormatter.format(Date(atMs)),
        type = when (level) {
            VrLinkLog.Level.INFO -> LogType.INFO
            VrLinkLog.Level.SUCCESS -> LogType.SUCCESS
            VrLinkLog.Level.WARNING -> LogType.NOTIFICATION
            VrLinkLog.Level.ERROR -> LogType.ERROR
        },
        message = message
    )

    /** Re-read the tablet's LAN IP (e.g. after the network changes). */
    fun refreshAddress() {
        _uiState.update { it.copy(tabletIpAddress = networkChecker.localIpv4()) }
    }

    fun clearLog() {
        vrLinkLog.clear()
    }
}
