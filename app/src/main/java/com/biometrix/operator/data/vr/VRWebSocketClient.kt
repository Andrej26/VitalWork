package com.biometrix.operator.data.vr

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.network.NetworkChecker
import com.biometrix.operator.data.vr.model.ServerMessage
import com.biometrix.operator.data.vr.model.WebSocketMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed class SendResult {
    data object Success : SendResult()
    data class Failure(val reason: String) : SendResult()
}

class VRWebSocketClient(
    private val networkChecker: NetworkChecker
) : VRConnectionManager {

    companion object {
        private const val PORT = 9090
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 0L // No timeout for WebSocket
        private const val PING_INTERVAL_SECONDS = 5L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 16000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    // Reconnection state
    private var lastConnectedIp: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var isManualDisconnect = false
    private var suppressReconnect = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<WebSocketMessage>(replay = 0)
    override val messages: SharedFlow<WebSocketMessage> = _messages.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    override val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    override fun connect(ipAddress: String) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            return
        }

        isManualDisconnect = false
        suppressReconnect = false
        _connectionState.value = ConnectionState.CONNECTING
        _lastError.value = null

        // Pre-flight network check
        if (!networkChecker.isLanAvailable()) {
            _lastError.value = "No WiFi/LAN connection available. Connect to the same network as the VR headset."
            _connectionState.value = ConnectionState.ERROR
            return
        }

        val url = "ws://$ipAddress:$PORT"
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastConnectedIp = ipAddress
                reconnectAttempt = 0
                _isReconnecting.value = false
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    parseMessage(text)?.let { message ->
                        _messages.emit(message)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val isNormalClose = code == 1000 || code == 1001
                if (!isNormalClose && !isManualDisconnect && !suppressReconnect && lastConnectedIp != null) {
                    _lastError.value = "Connection closed (code: $code, reason: ${reason.ifEmpty { "none" }})"
                    attemptReconnect()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    resetReconnectState()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _lastError.value = t.message ?: "Connection failed"
                if (!isManualDisconnect && !suppressReconnect && lastConnectedIp != null) {
                    attemptReconnect()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    resetReconnectState()
                }
            }
        })
    }

    override fun disconnect() {
        isManualDisconnect = true
        resetReconnectState()
        lastConnectedIp = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun suppressAutoReconnect() {
        suppressReconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.ERROR) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override fun sendCommand(command: String, params: Map<String, Any>): SendResult {
        val currentState = _connectionState.value
        if (currentState != ConnectionState.CONNECTED) {
            return SendResult.Failure("Not connected (state: $currentState)")
        }

        val socket = webSocket
            ?: return SendResult.Failure("WebSocket is null despite CONNECTED state")

        val jsonMessage = buildJsonObject {
            put("command", command)
            params.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Float -> put(key, value)
                    is Double -> put(key, value)
                    is Boolean -> put(key, value)
                }
            }
        }

        val jsonString = jsonMessage.toString()
        val sent = socket.send(jsonString)

        return if (sent) {
            SendResult.Success
        } else {
            SendResult.Failure("WebSocket send buffer full or connection closing")
        }
    }

    private fun attemptReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _isReconnecting.value = false
            _connectionState.value = ConnectionState.ERROR
            _lastError.value = "Connection lost after $MAX_RECONNECT_ATTEMPTS reconnect attempts"
            resetReconnectState()
            return
        }

        _isReconnecting.value = true
        _connectionState.value = ConnectionState.CONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempt))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
            delay(delayMs)

            reconnectAttempt++
            val ip = lastConnectedIp ?: return@launch

            if (!networkChecker.isLanAvailable()) {
                _lastError.value = "WiFi/LAN not available. Reconnect to the network and try again."
                _isReconnecting.value = false
                _connectionState.value = ConnectionState.DISCONNECTED
                resetReconnectState()
                return@launch
            }

            // Reset state so connect() guard allows re-entry
            _connectionState.value = ConnectionState.DISCONNECTED
            connect(ip)
        }
    }

    private fun resetReconnectState() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _isReconnecting.value = false
    }

    private fun parseMessage(text: String): WebSocketMessage? {
        return try {
            val serverMessage = json.decodeFromString(ServerMessage.serializer(), text)
            when (serverMessage.type) {
                "response" -> WebSocketMessage.Response(serverMessage)
                "event" -> WebSocketMessage.Event(serverMessage)
                else -> WebSocketMessage.Error("Unknown message type: ${serverMessage.type}")
            }
        } catch (e: Exception) {
            WebSocketMessage.Error("Failed to parse message: ${e.message}")
        }
    }

    fun cleanup() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
