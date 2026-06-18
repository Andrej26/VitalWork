package com.vitalwork.app.data.link

import com.vitalwork.app.data.link.model.PeerDevice
import com.vitalwork.app.data.model.ConnectionState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the device-to-device WebSocket link. One device runs as [startServer],
 * the other [startClientDiscovery]s and [connectTo]s a discovered peer. Both directions then
 * exchange [com.vitalwork.app.data.link.model.PeerMessage]s.
 *
 * Phase 1 scope: pairing + bidirectional test logs. WebRTC signaling is layered on later.
 */
interface PeerLinkManager {
    val connectionState: StateFlow<ConnectionState>

    /** Discovered peers (client role). Empty in server role. */
    val discoveredDevices: StateFlow<List<PeerDevice>>

    /** Human-readable log of sent/received/lifecycle lines for the UI. */
    val logLines: StateFlow<List<String>>

    /** Address being advertised (server) or the connected peer (client), for display. */
    val peerLabel: StateFlow<String?>

    /** Host a WebSocket server on the fixed port and advertise it via mDNS. */
    fun startServer()

    /** Begin discovering peers; results appear in [discoveredDevices]. */
    fun startClientDiscovery()

    /** Connect to a discovered peer (client role). */
    fun connectTo(device: PeerDevice)

    /** Send a text "log" message to the connected peer. */
    fun sendMessage(text: String)

    /** Tear down server/client/mDNS and reset state. */
    fun stop()
}
