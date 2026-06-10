package com.biometrix.operator.presentation.screens.vr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.network.NetworkChecker
import com.biometrix.operator.data.vr.VrDiscoveryListener
import com.biometrix.operator.data.vr.VrEvent
import com.biometrix.operator.data.vr.VrEventReceiver
import com.biometrix.operator.data.vr.VrHttpServer
import com.biometrix.operator.data.vr.VrPairingManager
import com.biometrix.operator.presentation.log.LogEntry
import com.biometrix.operator.presentation.log.LogType
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
    val logEntries: List<LogEntry> = emptyList()
)

@HiltViewModel
class VRConnectionViewModel @Inject constructor(
    private val vrEventReceiver: VrEventReceiver,
    private val vrPairingManager: VrPairingManager,
    private val vrDiscoveryListener: VrDiscoveryListener,
    private val vrHttpServer: VrHttpServer,
    private val networkChecker: NetworkChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VRConnectionUiState(tabletIpAddress = networkChecker.localIpv4())
    )
    val uiState: StateFlow<VRConnectionUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        observeConnectionState()
        observeEvents()
        observePairing()
    }

    /**
     * Run the VR link (discovery listener + HTTP server) while the VR Control screen is on-screen,
     * so the operator can pair and watch live events with no session running. Reference-counted: a
     * session holding its own reference keeps these alive when the operator leaves the screen.
     */
    fun acquireVrLink() {
        vrDiscoveryListener.acquire()
        vrHttpServer.acquire()
    }

    /** Release this screen's hold on the VR link (see [acquireVrLink]). */
    fun releaseVrLink() {
        vrDiscoveryListener.release()
        vrHttpServer.release()
    }

    /** Operator tapped Connect: bond to the candidate Quest. */
    fun confirmPairing() {
        vrPairingManager.confirm()
        // Tell the Quest where to POST. The session service does this via its own pairingState
        // observer, but that doesn't run without a session — so drive it here too. Idempotent: a
        // duplicate reply when a session is also active just re-informs the Quest of the address.
        vrDiscoveryListener.sendBondReply()
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
                val logType = if (state == ConnectionState.CONNECTED) LogType.SUCCESS else LogType.INFO
                addLogEntry(logType, "VR ${state.name.lowercase()}")
            }
        }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            vrEventReceiver.events.collect { event ->
                val label = when (event) {
                    is VrEvent.ScenarioStart -> "scenario_start ${event.code.officialCode}"
                    is VrEvent.StimulusEvent -> "event ${event.code.officialCode}"
                    is VrEvent.Reaction -> "reaction ${event.code.officialCode}"
                    is VrEvent.ScenarioStop -> "scenario_stop ${event.code.officialCode}"
                }
                addLogEntry(LogType.NOTIFICATION, label)
            }
        }
    }

    /** Re-read the tablet's LAN IP (e.g. after the network changes). */
    fun refreshAddress() {
        _uiState.update { it.copy(tabletIpAddress = networkChecker.localIpv4()) }
    }

    fun clearLog() {
        _uiState.update { it.copy(logEntries = emptyList()) }
    }

    private fun addLogEntry(type: LogType, message: String) {
        val timestamp = timeFormatter.format(Date())
        val entry = LogEntry(timestamp, type, message)
        _uiState.update { state ->
            state.copy(logEntries = (listOf(entry) + state.logEntries).take(100))
        }
    }
}
