package com.biometrix.operator.presentation.screens.vr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.vr.SendResult
import com.biometrix.operator.data.vr.VRConnectionManager
import com.biometrix.operator.data.vr.VrDeviceDiscovery
import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import com.biometrix.operator.data.vr.model.WebSocketMessage
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

data class VRConnectionUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val lastError: String? = null,
    val logEntries: List<LogEntry> = emptyList(),
    val isReconnecting: Boolean = false,
    val sceneName: String = "",
    val triggerTarget: String = "",
    val triggerEventName: String = "onClick",
    val discoveredDevices: List<DiscoveredVrDevice> = emptyList(),
    val isDiscovering: Boolean = false,
    val selectedDevice: DiscoveredVrDevice? = null,
    val isWifiAvailable: Boolean = true
)

@HiltViewModel
class VRConnectionViewModel @Inject constructor(
    private val webSocketClient: VRConnectionManager,
    private val mdnsDiscovery: VrDeviceDiscovery
) : ViewModel() {

    private val _uiState = MutableStateFlow(VRConnectionUiState())
    val uiState: StateFlow<VRConnectionUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        observeConnectionState()
        observeMessages()
        observeErrors()
        observeReconnecting()
        observeDiscovery()
        mdnsDiscovery.startDiscovery()
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            webSocketClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }

                val message = when (state) {
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.ERROR -> "Connection error"
                }

                val logType = when (state) {
                    ConnectionState.CONNECTED -> LogType.SUCCESS
                    ConnectionState.ERROR -> LogType.ERROR
                    else -> LogType.INFO
                }

                addLogEntry(logType, message)
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            webSocketClient.messages.collect { message ->
                when (message) {
                    is WebSocketMessage.Response -> {
                        val serverMessage = message.serverMessage
                        val logType = if (serverMessage.success) LogType.SUCCESS else LogType.ERROR
                        val text = if (serverMessage.success) {
                            "Response: ${serverMessage.msg ?: "success"}"
                        } else {
                            "Response: ${serverMessage.msg ?: "error"}"
                        }
                        addLogEntry(logType, text)
                    }
                    is WebSocketMessage.Event -> {
                        val serverMessage = message.serverMessage
                        addLogEntry(
                            LogType.NOTIFICATION,
                            "Event: ${serverMessage.msg ?: "unknown"}"
                        )
                    }
                    is WebSocketMessage.Error -> {
                        addLogEntry(LogType.ERROR, message.message)
                    }
                }
            }
        }
    }

    private fun observeErrors() {
        viewModelScope.launch {
            webSocketClient.lastError.collect { error ->
                _uiState.update { it.copy(lastError = error) }
                if (error != null) {
                    addLogEntry(LogType.ERROR, error)
                }
            }
        }
    }

    private fun observeReconnecting() {
        viewModelScope.launch {
            webSocketClient.isReconnecting.collect { reconnecting ->
                _uiState.update { it.copy(isReconnecting = reconnecting) }
                if (reconnecting) {
                    addLogEntry(LogType.INFO, "Attempting to reconnect...")
                }
            }
        }
    }

    private fun observeDiscovery() {
        viewModelScope.launch {
            mdnsDiscovery.discoveredDevices.collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
        }
        viewModelScope.launch {
            mdnsDiscovery.isDiscovering.collect { isDiscovering ->
                _uiState.update { it.copy(isDiscovering = isDiscovering) }
            }
        }
        viewModelScope.launch {
            mdnsDiscovery.isWifiAvailable.collect { available ->
                _uiState.update { it.copy(isWifiAvailable = available) }
            }
        }
    }

    fun selectAndConnect(device: DiscoveredVrDevice) {
        _uiState.update { it.copy(selectedDevice = device) }
        mdnsDiscovery.stopDiscovery()
        webSocketClient.connect(device.host)
    }

    fun rescan() {
        webSocketClient.disconnect()
        _uiState.update { it.copy(selectedDevice = null) }
        mdnsDiscovery.startDiscovery()
    }

    fun disconnect() {
        webSocketClient.disconnect()
        _uiState.update { it.copy(selectedDevice = null) }
        mdnsDiscovery.startDiscovery()
    }

    fun updateSceneName(name: String) {
        _uiState.update { it.copy(sceneName = name) }
    }

    fun updateTriggerTarget(target: String) {
        _uiState.update { it.copy(triggerTarget = target) }
    }

    fun updateTriggerEventName(eventName: String) {
        _uiState.update { it.copy(triggerEventName = eventName) }
    }

    fun sendReloadSceneCommand() {
        sendCommand("scene", mapOf("action" to "reload"))
    }

    fun sendLoadSceneCommand() {
        val sceneName = _uiState.value.sceneName.trim()
        if (sceneName.isEmpty()) {
            addLogEntry(LogType.ERROR, "Scene name is empty")
            return
        }
        sendCommand("scene", mapOf("action" to "load", "sceneName" to sceneName))
    }

    fun sendTriggerEventCommand() {
        val target = _uiState.value.triggerTarget.trim()
        if (target.isEmpty()) {
            addLogEntry(LogType.ERROR, "Trigger target is empty")
            return
        }
        val eventName = _uiState.value.triggerEventName.trim().ifEmpty { "onClick" }
        sendCommand("trigger_event", mapOf("target" to target, "eventName" to eventName))
    }

    private fun sendCommand(command: String, params: Map<String, Any>) {
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) {
            addLogEntry(LogType.ERROR, "Not connected")
            return
        }

        when (val result = webSocketClient.sendCommand(command, params)) {
            is SendResult.Success -> {
                addLogEntry(LogType.SENT, "Sent: $command $params")
            }
            is SendResult.Failure -> {
                addLogEntry(LogType.ERROR, "Send failed: ${result.reason}")
            }
        }
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

    override fun onCleared() {
        super.onCleared()
        mdnsDiscovery.stopDiscovery()
    }
}
