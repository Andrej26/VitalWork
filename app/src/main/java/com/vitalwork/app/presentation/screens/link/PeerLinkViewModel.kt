package com.vitalwork.app.presentation.screens.link

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vitalwork.app.data.link.PeerLinkManager
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.data.link.model.PeerDevice
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.webrtc.ScreenShareController
import com.vitalwork.app.data.webrtc.WebRtcEngine
import com.vitalwork.app.data.webrtc.model.ShareState
import com.vitalwork.app.service.BackgroundConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import javax.inject.Inject

@HiltViewModel
class PeerLinkViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val linkManager: PeerLinkManager,
    private val screenShare: ScreenShareController,
    private val webRtcEngine: WebRtcEngine,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val role: PeerRole =
        if (savedStateHandle.get<String>("role") == "server") PeerRole.SERVER else PeerRole.CLIENT

    val connectionState: StateFlow<ConnectionState> = linkManager.connectionState
    val discoveredDevices: StateFlow<List<PeerDevice>> = linkManager.discoveredDevices
    val peerLabel: StateFlow<String?> = linkManager.peerLabel
    val isActive: StateFlow<Boolean> = linkManager.isActive

    // --- Screen monitoring (WebRTC) ---
    val shareState: StateFlow<ShareState> = screenShare.shareState
    val remoteVideoTrack: StateFlow<VideoTrack?> = screenShare.remoteVideoTrack
    /** Fires (client) when the server asks for this device's screen → UI launches the consent prompt. */
    val screenRequested: SharedFlow<Unit> = screenShare.screenRequested
    val eglBase: EglBase get() = webRtcEngine.eglBase

    /** Server: ask the connected peer to share its screen. */
    fun requestScreen() = screenShare.requestScreen()

    /** Client: the retained screen request has been delivered to the consent dialog — clear it. */
    fun consumeScreenRequest() = screenShare.consumeScreenRequest()

    /** Client: consent result from the system screen-capture dialog. */
    fun onScreenConsent(resultCode: Int, data: Intent) {
        // Hand to the service so it promotes to a mediaProjection FGS *before* capture starts.
        BackgroundConnectionService.startScreenShare(appContext, resultCode, data)
    }

    /** Client: user declined the consent dialog — tell the peer + reset. */
    fun onScreenConsentDenied() = screenShare.stopShare()

    /** Either side: stop monitoring/sharing. */
    fun stopShare() = screenShare.stopShare()

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

    fun disconnect() = linkManager.stop()

    override fun onCleared() {
        super.onCleared()
        // Preserve an established link (server hosting / client connected) when the screen closes;
        // only tear down a discovery-only session so the mDNS scan/multicast lock doesn't leak.
        if (!linkManager.isActive.value) linkManager.stop()
    }
}
