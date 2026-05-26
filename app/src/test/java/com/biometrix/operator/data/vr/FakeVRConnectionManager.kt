package com.biometrix.operator.data.vr

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.vr.model.WebSocketMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeVRConnectionManager : VRConnectionManager {

    override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val messages = MutableSharedFlow<WebSocketMessage>()
    override val lastError = MutableStateFlow<String?>(null)
    override val isReconnecting = MutableStateFlow(false)

    var lastConnectedIp: String? = null
        private set
    var disconnectCallCount = 0
        private set
    var suppressAutoReconnectCallCount = 0
        private set
    var lastCommand: String? = null
        private set
    var lastParams: Map<String, Any>? = null
        private set
    var sendCommandResult: SendResult = SendResult.Success

    override fun connect(ipAddress: String) {
        lastConnectedIp = ipAddress
    }

    override fun disconnect() {
        disconnectCallCount++
    }

    override fun suppressAutoReconnect() {
        suppressAutoReconnectCallCount++
    }

    override fun sendCommand(command: String, params: Map<String, Any>): SendResult {
        if (connectionState.value != ConnectionState.CONNECTED) {
            return SendResult.Failure("Not connected (state: ${connectionState.value})")
        }
        lastCommand = command
        lastParams = params
        return sendCommandResult
    }
}
