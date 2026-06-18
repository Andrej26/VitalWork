package com.vitalwork.app.data.link

import android.os.Build
import android.util.Log
import com.vitalwork.app.data.link.model.PeerDevice
import com.vitalwork.app.data.link.model.PeerMessage
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.system.KeepAliveCoordinator
import com.vitalwork.app.data.system.KeepAliveReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PeerLinkManager] backed by the Java-WebSocket library (server + client) and [PeerMdnsService].
 *
 * Server role mirrors `CaustrOFFQuest`'s `CommandWebSocketBehavior`: on open it logs + sends a
 * greeting; on message it logs + emits. Client role mirrors ClaustrOFFOperator's `VRWebSocketClient`.
 *
 * Threading: Java-WebSocket fires callbacks on its own threads; [MutableStateFlow.update] is
 * thread-safe so updates are made directly, and teardown runs on an IO scope (server `stop()` blocks).
 */
@Singleton
class PeerLinkManagerImpl @Inject constructor(
    private val mdns: PeerMdnsService,
    private val keepAlive: KeepAliveCoordinator
) : PeerLinkManager {

    companion object {
        private const val TAG = "PeerLinkManager"
        const val PORT = 9090
        private const val MAX_LOG_LINES = 200
        private const val STOP_TIMEOUT_MS = 1000
        // Periodic WebSocket ping interval: keeps NAT/AP idle timers from dropping a backgrounded
        // socket, and detects a dead peer (→ onClose → terminal teardown).
        private const val CONNECTION_LOST_TIMEOUT_S = 30
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val deviceName = "VitalWork-${Build.MODEL}".replace(' ', '-')

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val discoveredDevices: StateFlow<List<PeerDevice>> = mdns.discoveredDevices

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    override val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _peerLabel = MutableStateFlow<String?>(null)
    override val peerLabel: StateFlow<String?> = _peerLabel.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _activeRole = MutableStateFlow<PeerRole?>(null)
    override val activeRole: StateFlow<PeerRole?> = _activeRole.asStateFlow()

    private var server: WebSocketServer? = null
    private var serverConn: WebSocket? = null
    private var client: WebSocketClient? = null

    override fun startServer() {
        stop()
        val advertised = "ws://${LanAddress.localIpv4() ?: "?"}:$PORT"
        _peerLabel.value = advertised
        log("Starting server on $advertised")

        val s = object : WebSocketServer(InetSocketAddress(PORT)) {
            override fun onStart() {
                log("Listening — waiting for a peer to connect")
            }

            override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                serverConn = conn
                _connectionState.value = ConnectionState.CONNECTED
                log("Peer connected: ${conn?.remoteSocketAddress?.address?.hostAddress}")
                sendInternal(greeting())
            }

            override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                log("Peer disconnected (code=$code ${reason.orEmpty()})")
                if (conn == serverConn) {
                    serverConn = null
                    // Still listening for a new client.
                    _connectionState.value = ConnectionState.CONNECTING
                }
            }

            override fun onMessage(conn: WebSocket?, message: String?) {
                handleIncoming(message)
            }

            override fun onError(conn: WebSocket?, ex: Exception?) {
                Log.e(TAG, "Server error", ex)
                log("Server error: ${ex?.message}")
            }
        }
        s.isReuseAddr = true
        s.connectionLostTimeout = CONNECTION_LOST_TIMEOUT_S
        server = s
        try {
            s.start()
            mdns.register(deviceName, PORT)
            _connectionState.value = ConnectionState.CONNECTING
            markActive(PeerRole.SERVER)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            log("Failed to start server: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    override fun startClientDiscovery() {
        stop()
        _connectionState.value = ConnectionState.DISCONNECTED
        mdns.startDiscovery()
        log("Scanning for peers…")
    }

    override fun connectTo(device: PeerDevice) {
        mdns.stopDiscovery()
        _connectionState.value = ConnectionState.CONNECTING
        _peerLabel.value = "${device.host}:${device.port}"
        log("Connecting to ${device.name} (${device.host}:${device.port})")

        val c = object : WebSocketClient(URI("ws://${device.host}:${device.port}")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                _connectionState.value = ConnectionState.CONNECTED
                log("Connected to ${device.host}")
                sendInternal(greeting())
            }

            override fun onMessage(message: String?) {
                handleIncoming(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                log("Disconnected (code=$code ${reason.orEmpty()})")
                // The server shut down / the link dropped — terminate the client's own connection.
                terminateClientLink()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Client error", ex)
                log("Client error: ${ex?.message}")
                terminateClientLink()
            }
        }
        c.connectionLostTimeout = CONNECTION_LOST_TIMEOUT_S
        client = c
        markActive(PeerRole.CLIENT)
        try {
            c.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            log("Failed to connect: ${e.message}")
            terminateClientLink()
        }
    }

    /**
     * Fully tears down the client side when its link drops (server gone, network lost, error): closes
     * the socket, clears the connected peer, releases keep-alive (so the foreground service stops),
     * and returns to a clean discovery state — `stopDiscovery`/`startDiscovery` drop the stale peer
     * list so the dead server doesn't linger, and rescan so the user can reconnect.
     *
     * Idempotent and skips the manual-disconnect path: [stop] nulls `client` first, so the follow-up
     * `onClose` finds it already cleared and returns without rescanning.
     */
    private fun terminateClientLink() {
        val old = client ?: return
        client = null
        _peerLabel.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        clearActive()
        scope.launch { runCatching { old.close() } }
        mdns.startDiscovery()
        log("Link lost — rescanning for peers")
    }

    override fun sendMessage(text: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            log("Not connected — message not sent")
            return
        }
        sendInternal(PeerMessage(type = "log", text = text, ts = System.currentTimeMillis()))
        log("Me: $text")
    }

    override fun stop() {
        mdns.unregister()
        mdns.stopDiscovery()
        val oldServer = server
        val oldServerConn = serverConn
        val oldClient = client
        server = null
        serverConn = null
        client = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _peerLabel.value = null
        clearActive()
        // server.stop() blocks up to the timeout — never on the caller's (possibly main) thread.
        scope.launch {
            runCatching { oldServerConn?.close() }
            runCatching { oldClient?.close() }
            runCatching { oldServer?.stop(STOP_TIMEOUT_MS) }
        }
    }

    /** Mark a persistent link active and acquire the app-wide keep-alive (starts the FGS). */
    private fun markActive(role: PeerRole) {
        _activeRole.value = role
        _isActive.value = true
        keepAlive.acquire(KeepAliveReason.LINK)
    }

    /** Clear active state and release keep-alive (lets the FGS stop if nothing else needs it). */
    private fun clearActive() {
        if (!_isActive.value && _activeRole.value == null) return
        _isActive.value = false
        _activeRole.value = null
        keepAlive.release(KeepAliveReason.LINK)
    }

    private fun greeting() =
        PeerMessage(type = "hello", text = "Hello from $deviceName", ts = System.currentTimeMillis())

    private fun sendInternal(message: PeerMessage) {
        val payload = json.encodeToString(PeerMessage.serializer(), message)
        try {
            serverConn?.takeIf { it.isOpen }?.send(payload)
            client?.takeIf { it.isOpen }?.send(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            log("Send failed: ${e.message}")
        }
    }

    private fun handleIncoming(message: String?) {
        if (message == null) return
        val parsed = runCatching { json.decodeFromString(PeerMessage.serializer(), message) }.getOrNull()
        if (parsed == null) {
            log("Recv (unparsed): $message")
            return
        }
        log("Peer: ${parsed.text}")
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        _logLines.update { (it + line).takeLast(MAX_LOG_LINES) }
    }
}
