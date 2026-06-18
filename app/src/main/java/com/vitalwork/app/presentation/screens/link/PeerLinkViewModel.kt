package com.vitalwork.app.presentation.screens.link

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vitalwork.app.data.link.PeerLinkManager
import com.vitalwork.app.data.link.model.PeerDevice
import com.vitalwork.app.data.model.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** "server" or "client" — supplied via the `role` nav argument. */
enum class PeerRole { SERVER, CLIENT }

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

    private var testCounter = 0

    init {
        when (role) {
            PeerRole.SERVER -> linkManager.startServer()
            PeerRole.CLIENT -> linkManager.startClientDiscovery()
        }
    }

    fun onDeviceSelected(device: PeerDevice) = linkManager.connectTo(device)

    fun onSendTest() = linkManager.sendMessage("Test message #${++testCounter}")

    override fun onCleared() {
        super.onCleared()
        linkManager.stop()
    }
}
