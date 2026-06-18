package com.vitalwork.app.presentation.screens.link

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vitalwork.app.data.link.PeerLinkManager
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.data.link.model.PeerDevice
import com.vitalwork.app.data.model.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PeerLinkViewModel @Inject constructor(
    private val linkManager: PeerLinkManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val role: PeerRole =
        if (savedStateHandle.get<String>("role") == "server") PeerRole.SERVER else PeerRole.CLIENT

    val connectionState: StateFlow<ConnectionState> = linkManager.connectionState
    val discoveredDevices: StateFlow<List<PeerDevice>> = linkManager.discoveredDevices
    val logLines: StateFlow<List<String>> = linkManager.logLines
    val peerLabel: StateFlow<String?> = linkManager.peerLabel
    val isActive: StateFlow<Boolean> = linkManager.isActive

    private var testCounter = 0

    init {
        // The server is started manually via [connect] (Connect button); only the client begins
        // automatically (discovery). Returning to the screen while already active leaves it as-is.
        if (role == PeerRole.CLIENT && linkManager.activeRole.value != PeerRole.CLIENT) {
            if (linkManager.activeRole.value != null) linkManager.stop()
            linkManager.startClientDiscovery()
        }
    }

    /** Server role: start hosting (manual — bound to the Connect button). */
    fun connect() = linkManager.startServer()

    fun onDeviceSelected(device: PeerDevice) = linkManager.connectTo(device)

    fun onSendTest() = linkManager.sendMessage("Test message #${++testCounter}")

    fun disconnect() = linkManager.stop()

    override fun onCleared() {
        super.onCleared()
        // Preserve an established link (server hosting / client connected) when the screen closes;
        // only tear down a discovery-only session so the mDNS scan/multicast lock doesn't leak.
        if (!linkManager.isActive.value) linkManager.stop()
    }
}
